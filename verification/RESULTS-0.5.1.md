# 0.5.1-paper26.3 验证记录

日期：2026-10-23（Asia/Shanghai）。本轮实现优化清单中的 1、2、3、4、7：补货物品保护、名称存档一致性、最近目标选择、后台 YAML 保存、动作故障停止。

- JAR SHA-256：`fffd14b8e56233592f19fae8943666c3e7720459136593690eb5af2c78573cf7`
- 源码基线：`12071ea303583bdaea782f57f998789b87ea7990`。
- 测试时开发分支：`fix/paper26.3-integrity`，测试对象为提交前的候选改动；发布目标为 fork 的 `master`。
- 环境：Paper 26.3 build 205 / a90108f、CommandAPI 12.1.0、JDK 26.0.2.1。
- 独立超平坦测试服，仅监听本机；offline-mode、和平难度。未使用用户实际服务器。
- 修改前备份 216 个跟踪文件并逐文件核对 SHA-256；保留 0.4.1、0.5.0 的验证记录。
- Maven 构建成功，156 个项目类与候选 JAR 逐个一致；测试服 JAR 与交付 JAR 哈希一致。

`v0.5.1/` 包含 22 份阶段报告、188 条阶段内断言；另有 7 条写盘队列故障注入断言、日志次数和打包核对。阶段输出均删除旧文件后重新生成。

| 检查 | 证据 |
| --- | --- |
| 延迟补货正常移动一组物品，重复请求不重复转移 | `integrity-refill.txt` |
| 等待期间换手持物品、换选中格、换窗口、关闭补货、退出均保留物品 | `integrity-refill.txt` |
| 事件取消、事件改源物品、事件改手持物品后不覆盖、不复制 | `integrity-refill.txt` |
| 三种英文大小写重登使用同一 UUID，背包/经验/装备/双手/手持格/末影箱不变 | `integrity-case.txt` |
| 旧大小写冲突保留独立 UUID；非原始拼写拒绝；数据库禁止新重复 | `integrity-case.txt` |
| 最近实体不依赖遍历顺序，排除自身和无效实体，空集合安全 | `integrity-nearest.txt` |
| 动作带冷却连续失败三次后停止，其他动作继续；诊断有原因，驻留快照排除故障动作 | `integrity-action.txt` |
| 替换动作清理旧 ticker，手动开始新动作和立即停止有效 | `integrity-action.txt` |
| 三次失败只产生一份堆栈和一条停止摘要 | `action-logging.txt` |
| YAML 独立线程、深层快照隔离、100 次待写合并、自动重试、新值优先、关服刷新及失败报告 | `yaml-queue.txt` |
| 快速开启/取消驻留无残留；关服文件也核对无 Toggle51 | `integrity-resident-toggle.txt`、`resident-after-shutdown.txt` |
| 本次诊断尝试时间戳实际写入磁盘，重启后保留失败证据 | `conflicts.txt`、`journal-restored.txt` |
| 正常关服重启后驻留位置、动作、物品、123 点经验、离线创建者及 UUID 归属保持 | `resident.txt`、`restored.txt` |
| 名称占用不踢占用者；三次恢复失败保留记录；手动重试；世界未加载时不消耗次数 | `offline-management.txt` |
| 既有生成、背包/经验/expme、拒绝/取消登录、动作、死亡保留开关、复活回归 | `state.txt`、`pending.txt`、`cancel.txt`、`actions.txt`、`death-drop.txt`、`death-keep.txt`、`respawn.txt` |
| 权限隔离、生成/区块预算、世界上限、旧登录回调隔离、队列关闭及即时反馈 | `management.txt`、`budgets.txt`、`race.txt`、`closing.txt`、`queue-ack.txt` |
| 在线皮肤不阻塞生成，默认再生生效 | `skin-configured.txt` |

原始服务端日志 `server-1.log`、`server-2.log`、`server-3.log` 和构建日志仅保留在本地，不纳入 Git。失败的旧测试缓存启动日志单独保留，未作为通过证据。

隔离测试清理说明：复用测试服时，旧 conflictbot 测试曾用不同 UUID 恢复，名称缓存与测试档案不一致，导致测试辅助插件无法启动。正常停服、备份并清除三个测试角色的缓存后重跑，离线恢复阶段通过；未为此放宽正式插件的真实玩家名称保护。

## 验证范围

以上是实际 Paper 服务端状态和事件检查；没有真实客户端渲染、Tab 或背包 GUI 验收，也没有认证 AuthMe、WorldGuard、HuskSync 等第三方插件组合。

仅测试 build 205；未做其他 Paper build、代理、生产规模 TPS/MSPT 基准或断电测试。后台保存仅涵盖驻留/诊断 YAML 编码和文件 IO；原生玩家存档与 Bukkit 状态采集仍在主线程执行。

故障注入验证保存失败的重试/报告，不代表磁盘永久故障时仍能成功保存。正常关服等待最终写入；强制终止仍可能丢失上次自动保存之后的状态。旧版本已经分叉的两份大小写存档不会自动合并。
