package cn.killconfirm;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 拦截玩家的 /kill，改为弹出确认对话框，只允许玩家杀死自己。
 *
 * <p>安全设计：
 * <ul>
 *   <li>玩家输入 /kill、/minecraft:kill（不区分大小写）全部被拦截，原版指令不会执行；</li>
 *   <li>带目标参数时，只有 @s 或自己的名字视为自杀；其余目标一律拒绝（除非有 killconfirm.others 权限，才交还原版处理）；</li>
 *   <li>每次弹窗生成一次性随机令牌，回调时校验：点击者 UUID == 弹窗所属玩家、令牌匹配、未过期，且只能消费一次；</li>
 *   <li>玩家死亡、退出、切换世界、重新输入 /kill 都会使旧确认框失效；</li>
 *   <li>真正的处死操作在玩家所属区域线程（EntityScheduler）上执行，符合 Folia 线程模型。</li>
 * </ul>
 */
public final class KillConfirmListener implements Listener {

    private static final Set<String> KILL_LABELS = Set.of("kill", "minecraft:kill");

    private final KillConfirmPlugin plugin;
    private final long timeoutSeconds;
    private final long cooldownNanos;

    /** 玩家 UUID -> 当前有效的确认请求 */
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    /** 玩家 UUID -> 上次打开确认框的时间（nanoTime） */
    private final Map<UUID, Long> lastOpen = new ConcurrentHashMap<>();

    private record Pending(UUID token, long expiresAtNanos) {
        boolean expired() {
            return System.nanoTime() - expiresAtNanos > 0;
        }
    }

    public KillConfirmListener(KillConfirmPlugin plugin, long timeoutSeconds, long cooldownSeconds) {
        this.plugin = plugin;
        this.timeoutSeconds = timeoutSeconds;
        this.cooldownNanos = Duration.ofSeconds(cooldownSeconds).toNanos();
    }

    void invalidateAll() {
        pending.clear();
        lastOpen.clear();
    }

    // ------------------------------------------------------------------ 指令拦截

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        if (raw.length() < 2 || raw.charAt(0) != '/') {
            return;
        }
        String[] parts = raw.substring(1).trim().split("\\s+");
        String label = parts[0].toLowerCase(Locale.ROOT);
        if (!KILL_LABELS.contains(label)) {
            return;
        }

        Player player = event.getPlayer();
        boolean selfTarget = isSelfTarget(player, parts);

        if (!selfTarget) {
            if (player.hasPermission("killconfirm.others")) {
                // 管理员：放行给原版 /kill（原版本身仍会检查 OP 权限等级）
                return;
            }
            event.setCancelled(true);
            player.sendMessage(Component.text("你只能使用 /kill 杀死自己，禁止对其他玩家或实体使用！", NamedTextColor.RED));
            return;
        }

        // 自杀请求：原版指令一律不执行，改为弹窗
        event.setCancelled(true);

        if (!player.hasPermission("killconfirm.use")) {
            player.sendMessage(Component.text("你没有权限使用 /kill。", NamedTextColor.RED));
            return;
        }
        if (player.isDead()) {
            return;
        }

        UUID id = player.getUniqueId();
        long now = System.nanoTime();
        if (cooldownNanos > 0 && !player.hasPermission("killconfirm.bypasscooldown")) {
            Long last = lastOpen.get(id);
            if (last != null && now - last < cooldownNanos) {
                long left = Math.max(1, Duration.ofNanos(cooldownNanos - (now - last)).toSeconds());
                player.sendMessage(Component.text("操作过于频繁，请 " + left + " 秒后再试。", NamedTextColor.RED));
                return;
            }
        }
        lastOpen.put(id, now);

        // 新令牌会覆盖旧令牌，旧确认框随即失效
        UUID token = UUID.randomUUID();
        pending.put(id, new Pending(token, now + Duration.ofSeconds(timeoutSeconds).toNanos()));

        player.showDialog(buildDialog(id, token));
    }

    /** 无参数，或唯一参数为 @s / 自己的名字 / 自己的 UUID，才算自杀。 */
    private static boolean isSelfTarget(Player player, String[] parts) {
        if (parts.length == 1) {
            return true;
        }
        if (parts.length != 2) {
            return false;
        }
        String arg = parts[1];
        return arg.equals("@s")
                || arg.equalsIgnoreCase(player.getName())
                || arg.equalsIgnoreCase(player.getUniqueId().toString());
    }

    // ------------------------------------------------------------------ 对话框

    private Dialog buildDialog(UUID owner, UUID token) {
        ClickCallback.Options options = ClickCallback.Options.builder()
                .uses(1)
                .lifetime(Duration.ofSeconds(timeoutSeconds))
                .build();

        ActionButton confirm = ActionButton.create(
                Component.text("确定", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("点击后你将立即死亡", NamedTextColor.RED),
                120,
                DialogAction.customClick((_, audience) -> onConfirm(audience, owner, token), options)
        );
        ActionButton cancel = ActionButton.create(
                Component.text("取消", NamedTextColor.GREEN, TextDecoration.BOLD),
                Component.text("关闭对话框，什么也不会发生", NamedTextColor.GRAY),
                120,
                DialogAction.customClick((_, audience) -> onCancel(audience, owner, token), options)
        );

        DialogBase base = DialogBase.builder(Component.text("确认自杀", NamedTextColor.RED, TextDecoration.BOLD))
                .canCloseWithEscape(true)   // 按 ESC 等同于点击“取消”
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(List.of(
                        DialogBody.plainMessage(Component.text("你确定要自杀吗？", NamedTextColor.WHITE)),
                        DialogBody.plainMessage(Component.text("死亡后可能会掉落物品与经验！", NamedTextColor.GOLD)),
                        DialogBody.plainMessage(Component.text("本确认在 " + timeoutSeconds + " 秒后失效。", NamedTextColor.GRAY))
                ))
                .build();

        return Dialog.create(builder -> builder.empty()
                .base(base)
                .type(DialogType.confirmation(confirm, cancel)));
    }

    // ------------------------------------------------------------------ 回调

    private void onConfirm(Audience audience, UUID owner, UUID token) {
        // 1. 点击者必须是弹窗所属的那名玩家本人
        if (!(audience instanceof Player clicker) || !clicker.getUniqueId().equals(owner)) {
            return;
        }
        if (!plugin.isEnabled()) {
            return;
        }
        // 2. 在玩家所属区域线程上执行，满足 Folia 线程要求
        clicker.getScheduler().run(plugin, _ -> {
            // 3. 原子地消费令牌：必须存在、匹配、未过期，且只能用一次
            Pending current = pending.get(owner);
            if (current == null || !current.token().equals(token) || !pending.remove(owner, current)) {
                clicker.sendMessage(Component.text("该确认已失效，请重新输入 /kill。", NamedTextColor.RED));
                return;
            }
            if (current.expired()) {
                clicker.sendMessage(Component.text("确认已超时，请重新输入 /kill。", NamedTextColor.RED));
                return;
            }
            if (!clicker.isOnline() || clicker.isDead()) {
                return;
            }
            // 4. 只对点击者本人造成伤害，等价于原版 /kill（generic_kill 无视无敌与不死图腾）
            clicker.damage(Float.MAX_VALUE, DamageSource.builder(DamageType.GENERIC_KILL).build());
            if (!clicker.isDead()) {
                clicker.sendMessage(Component.text("自杀失败（可能被其他插件阻止）。", NamedTextColor.RED));
            }
        }, null);
    }

    private void onCancel(Audience audience, UUID owner, UUID token) {
        if (!(audience instanceof Player clicker) || !clicker.getUniqueId().equals(owner)) {
            return;
        }
        if (!plugin.isEnabled()) {
            return;
        }
        clicker.getScheduler().run(plugin, _ -> {
            Pending current = pending.get(owner);
            if (current != null && current.token().equals(token)) {
                pending.remove(owner, current);
                clicker.sendMessage(Component.text("已取消自杀。", NamedTextColor.GREEN));
            }
        }, null);
    }

    // ------------------------------------------------------------------ 失效处理

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        pending.remove(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pending.remove(id);
        lastOpen.remove(id);
    }
}
