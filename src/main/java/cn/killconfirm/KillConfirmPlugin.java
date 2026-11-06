package cn.killconfirm;

import org.bukkit.plugin.java.JavaPlugin;

public final class KillConfirmPlugin extends JavaPlugin {

    private KillConfirmListener listener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        long timeout = clamp(getConfig().getLong("confirm-timeout-seconds", 30), 5, 300);
        long cooldown = clamp(getConfig().getLong("cooldown-seconds", 3), 0, 3600);

        listener = new KillConfirmListener(this, timeout, cooldown);
        getServer().getPluginManager().registerEvents(listener, this);

        getLogger().info("KillConfirm 已启用：确认超时 " + timeout + " 秒，冷却 " + cooldown + " 秒");
    }

    @Override
    public void onDisable() {
        if (listener != null) {
            // 让所有尚未点击的确认框全部失效
            listener.invalidateAll();
        }
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
