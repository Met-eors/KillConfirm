# KillConfirm

Folia 插件（Minecraft 26.1.2 ~ 26.3，Java 25）：玩家输入 `/kill` 时弹出确认对话框，点「确定」自杀、点「取消」或按 ESC 取消。只允许杀死自己，禁止对其他玩家或实体使用。

## 下载

编译好的插件：[`release/KillConfirm-1.0.0.jar`](release/KillConfirm-1.0.0.jar)，放进服务器 `plugins/` 文件夹后重启即可（服务器需用 Java 25 运行）。

## 权限

| 权限 | 默认 | 说明 |
|---|---|---|
| `killconfirm.use` | 所有人 | 允许通过 `/kill` 弹窗自杀 |
| `killconfirm.others` | OP | 允许使用原版 `/kill <目标>` |
| `killconfirm.bypasscooldown` | OP | 无视冷却 |

## 配置（`plugins/KillConfirm/config.yml`）

- `confirm-timeout-seconds`：确认框有效时间，默认 30 秒
- `cooldown-seconds`：两次打开确认框的冷却，默认 3 秒

## 自行编译

```bash
./gradlew build
```
