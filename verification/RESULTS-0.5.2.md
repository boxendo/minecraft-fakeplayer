# 0.5.2-paper26.3 退出命令验证记录

日期：2026-10-23（Asia/Shanghai）。修复删除假人后 `after-quit-commands` 不执行的问题。

- 基线提交：`0df65c94383902295f4921b57e7582b285c983c6`。
- 测试时分支：`fix/paper26.3-quit-hooks`；本地候选改动。
- 交付 JAR SHA-256：`ab704ce71e4eab00ac1f89ec0f7588db84e9eb78a319c4101779292264ebb294`。
- 环境：Paper 26.3 build 205 / a90108f、CommandAPI 12.1.0、JDK 26.0.2.1。
- 仅本机隔离超平坦测试服；没有修改用户实际服务器或实际 AuthMe 数据。
- 开始前备份 249 个跟踪文件并逐文件校验 SHA-256；原版本的交付和验证资料继续保留。
- Maven 构建成功，158 个项目类与交付 JAR 一致；测试服 JAR 与交付 JAR 哈希一致。

## 根因和修复

旧版的退出清理在 PlayerQuitEvent 的 LOW 阶段删除假人登记，而退出后的命令监听在 MONITOR 阶段判断是否是假人，因此误判为普通玩家并跳过。即使进入延迟任务，原派发方法也会重新查找已经被删除的创建者信息。

现在 LOWEST 阶段保存格式化后的命令和名称/UUID/创建者，MONITOR 确认观察到退出，下一服务器 tick 派发。新的生成流程在派发同名生成前命令之前先处理待执行的旧退出命令，并取消旧延迟任务。每条命令的异常独立记录。正常插件禁用时尝试派发尚未执行的退出命令。

## 实测

未修改的 0.5.1 JAR（SHA-256 `fffd14b8e56233592f19fae8943666c3e7720459136593690eb5af2c78573cf7`）实际复现“退出中命令正常，退出后命令缺失”，见 `v0.5.2/baseline-missing-hook.txt`。该文件中的 FAIL 是修复前的预期复现，不计入修复后通过结果。

修复版共 22 个集成阶段、192 条阶段内断言通过；另有 7 条写盘队列故障注入断言，以及关服内置命令检查。

| 内容 | 证据 |
| --- | --- |
| 删除后登记已清理，退出中和退出后命令各执行一次 | `quit-hooks.txt` |
| 退出后仍正确替换名称、原 UUID、创建者；派发时玩家已离线 | `quit-hooks.txt` |
| 删除并立即以不同大小写重建，派发顺序为 pre → post → after → pre，旧任务不重复派发 | `quit-hooks.txt` |
| 外部踢出触发命令；取消踢出不触发命令；后续真正退出仍正常 | `quit-hooks.txt` |
| 配置在等待期间变更，已保存的退出命令不被改变 | `quit-hooks.txt` |
| 一条退出命令抛异常，后续命令继续派发 | `quit-hooks.txt` |
| 正常禁用时内置计分板命令执行一次；重启后分数仍为 1 | `quit-shutdown.txt`、`quit-shutdown-before-stop.txt` |
| 驻留关服重启后保留物品、经验、位置、动作和归属，诊断历史保留 | `restored.txt`、`journal-restored.txt` |
| 0.5.1 的补货保护、大小写存档、最近目标、动作故障停止和快速驻留取消回归 | `integrity-*.txt` |
| 生成预算、满队列、世界上限、慢登录替换、取消队列和权限隔离回归 | `budgets.txt`、`race.txt`、`pending.txt`、`cancel.txt`、`closing.txt`、`management.txt` |
| 物品/经验/expme、死亡保留开关、复活、真实跳跃和挖掘、皮肤/再生回归 | `state.txt`、`death-drop.txt`、`death-keep.txt`、`respawn.txt`、`actions.txt`、`skin-configured.txt` |
| 独立写盘线程、快照隔离、合并、重试、最新数据优先和关服保存失败报告 | `yaml-queue.txt` |

## 范围和 AuthMe 说明

`quit-hooks` 使用真实 Paper 事件与控制台命令，但注册数据库由测试命令的内存集合模拟。没有安装用户实际版本的 AuthMe，不代表已验证其数据库操作结果、配置或其他插件组合。原始日志保留本地，未纳入 Git。

命令派发成功只表示命令处理器接受了调用。上游 AuthMe 的 [PurgePlayerCommand](https://github.com/AuthMe/AuthMeReloaded/blob/master/authme-core/src/main/java/fr/xephi/authme/command/executable/authme/PurgePlayerCommand.java) 异步执行；本修复保证 fakeplayer 的命令派发顺序，不等待外部插件的数据库操作完成。极快重建仍应以 AuthMe 的实际完成回执为准。

上游 [PurgeExecutor](https://github.com/AuthMe/AuthMeReloaded/blob/master/authme-core/src/main/java/fr/xephi/authme/task/purge/PurgeExecutor.java) 会按配置清理注册以外的玩家文件或权限。若只要注销注册，可核对实际版本后使用 `authme unregister %p`，参考 [UnregisterAdminCommand](https://github.com/AuthMe/AuthMeReloaded/blob/master/authme-core/src/main/java/fr/xephi/authme/command/executable/authme/UnregisterAdminCommand.java)。没有自动修改用户的 AuthMe 配置。

正常关服检查使用服务端内置命令；第三方命令所属插件若已先禁用，停服期间仍可能无法执行。没有做真实客户端 GUI、其他 Paper build、代理或生产压力测试。
