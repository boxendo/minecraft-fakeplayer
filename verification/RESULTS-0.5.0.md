# 0.5.0-paper26.3 验证记录

日期：2026-10-23（Asia/Shanghai）。本次对应选定功能 1、2、5、6，包含 0.4.1 的原有修复。

- 交付 JAR SHA-256：`22224ddcc9e81ace65bd7e88990c23521d6665e5bd1db47dd28e64226fa01ce5`
- 环境：Paper 26.3 build 205 / a90108f、CommandAPI 12.1.0、JDK 26.0.2.1。
- 仅本机独立超平坦测试服，offline-mode、和平难度；没有改动用户实际服务器。
- 源码基线：`7f35b72`，测试时位于开发分支 `feat/paper26.3-management`；维护代码的发布目标为 `master`。
- 开始修改前已对源码和说明生成逐文件 SHA-256 校验的可恢复备份。
- 发布 JAR 的 153 个项目类与当前编译输出逐个一致；测试辅助插件不进入发布 JAR。

本次报告位于 `v0.5.0/`，每个阶段都删除旧输出后重新生成，绑定到上述 JAR。0.4.1 的旧报告保留在原目录。
原始 `.log` 日志仅保留在本地，不纳入 Git；仓库包含测试源码、断言报告和构建身份摘要。

| 项目 | 结果与报告 |
| --- | --- |
| 普通玩家权限 | 自己的驻留和诊断可访问，他人的内容和记录删除被阻止，见 `management.txt` |
| 非 OP 管理员 | manage-others、server diagnostics、quota bypass 相互独立，按需授权，见 `management.txt` |
| 撤权/入口一致性 | 原选择不能越权；无效显式名称不退回自己的假人；右键/API 背包入口检查权限和归属，见 `management.txt` |
| UUID 归属 | 创建者改名仍能访问；不同 UUID 使用相同名字不能认领有 UUID 的记录，见 `management.txt` |
| 忘记驻留 | 记录移除，在线假人继续在线，实际原生 `.dat` 文件保留，见 `management.txt` |
| 诊断证据 | 真实传送取消、登录拒绝、踢出取消的结果及原因被记录，正确标记失败阶段，见 `conflicts.txt` |
| 诊断重启 | 失败阶段及事件证据两次重启后仍在，历史操作不会被称为正在进行，见 `journal-restored-first.txt`、`journal-restored-second.txt` |
| 生成预算 | 8 个生成任务按每 tick/并发预算执行，目标区块请求每 tick 受限，完成后无残留任务或票据，见 `budgets.txt` |
| 满队列与钩子 | 满队列拒绝并清理；被拒绝请求不执行生成前命令，接纳请求只执行一次，见 `budgets.txt` |
| 世界上限 | 排队请求占用预留额度，在线后不超额；跨世界传送不能绕过满额世界，见 `budgets.txt` |
| 取消竞态 | 慢登录取消后立即重建，旧回调不移除新登记、不污染新操作诊断，见 `race.txt` |
| 关闭队列 | 取消 future 完成，释放队列与票据，随后不迟到上线，见 `closing.txt` |
| 即时反馈 | spawn 立即确认排队，随后实际上线，见 `queue-ack.txt` |
| 重名离线记录 | 明确报告占用，不踢出占用者；释放名称后可手动恢复，见 `offline-management-first.txt` |
| 三次恢复失败 | 三次真实登录拒绝后记录仍保留；去除拒绝原因，手动恢复可重置尝试并上线，见 `offline-management-first.txt` |
| 世界未加载 | 保留记录且不消耗失败次数；加载世界后恢复，见 `offline-management-first.txt` |
| 背包和经验回归 | 背包、双手、装备、末影箱、手持格、123 点经验、饥饿与 expme 转移/防重复领取，见 `state.txt` |
| 登录回归 | 延迟登录、大小写重名、拒绝登录后的清理、提前取消，见 `pending.txt`、`cancel.txt` |
| 实际动作 | 实际跳跃及挖掘石头，见 `actions.txt` |
| 死亡和复活 | 普通死亡实际掉落并清空经验，保留开启后零掉落并保留物品经验，主动复活后可继续动作，见 `death-drop.txt`、`death-keep.txt`、`respawn.txt` |
| 皮肤/再生 | 在线皮肤不阻塞生成，默认再生在连接建立后应用，见 `skin-configured.txt` |
| 连续两次完整重启 | 原位恢复、动作、物品、经验、创建者 UUID 均保持，见 `restored-first.txt`、`restored-second.txt` |
| 重启后的实际跳跃 | 第二次重启后连续位置采样，见 `resident-motion.txt` |

## 证据与边界

`diagnose-server.txt`、`diagnose-failed-teleport.txt`、`diagnose-after-restart.txt` 保存命令的实际输出。
`release-a.log`、`release-b.log`、`release-c.log` 为当前构建运行日志；其中 retrybot 的三次恢复拒绝是预期测试条件。
`build.log`、`package.txt`、`build-identity.json` 为构建和身份记录。

测试使用辅助监听器制造拒绝/取消。没有对 AuthMe、WorldGuard、HuskSync 等第三方插件组合做兼容性认证；已安装插件和监听名单只是线索，不能视为责任归因。
没有真实玩家客户端的渲染、Tab 或背包 GUI 验收；测试捕获假人收到的服务端系统消息检查权限输出。
仅测 Paper build 205；未测试其他 build、代理、真实在线账号改名、断电恢复或大规模生产 TPS 基准。
目标区块请求预算不等同于 Paper 内部总区块生成上限，也不保证每 tick 的 CPU/IO 耗时。
周期保存分批执行，正常关闭完整保存；不宣称断电零丢失。

排查中的编译/辅助用例问题未作为发布通过证据：同机创建者触发 IP 共享配额时，管理测试显式隔离 detect-ip，玩家数量配额仍单独验证；存档路径断言改由 NMS 存储接口获得，以适配 26.3 的 `players/data` 布局。
