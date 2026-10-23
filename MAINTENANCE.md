# Paper 26.3 维护说明

当前维护版本：`0.5.0-paper26.3`，维护代码的发布目标为 fork 的 `master`。
本次开发分支为 `feat/paper26.3-management`，基于 `7f35b72`。

## 安装和配置

1. 正常停止服务器，备份原插件 JAR、`plugins/fakeplayer/` 和世界目录。26.3 的玩家数据通常在世界根目录的 `players/data/`，以新诊断命令读取的原生目录为准。
2. 用 `target/fakeplayer-0.5.0-paper26.3.jar` 替换旧插件，保留 CommandAPI。
3. 编辑 `plugins/fakeplayer/config.yml`，不要编辑会被覆盖的 `config.tmpl.yml`。
4. 启动服务器，先用一个已有假人验收背包和经验，再验证关服重启驻留。

```yaml
# 正常删除/重新生成时保存玩家数据。
persistent-data: true
# 正常删除时保留背包。
drop-inventory-on-quiting: false
# true：仅假人死亡时保留背包、装备、经验，不生成这些掉落。
# false（默认）：遵循世界及其他插件的正常死亡规则。
keep-inventory-on-death: true
```

旧拼写 `persist-data` 仍兼容；同时存在时以 `persistent-data` 为准。
已有配置中的 `drop-inventory-on-quiting: true` 仍会生效；希望删除后保留物品必须设为 false。
死亡保留开关独立于是否启用驻留，也不会改变真实玩家的死亡规则。

```text
/fp spawn bot1
/fp resident true bot1
/fp jump continuous bot1
/fp resident false bot1
```

同一假人名称对应已有 UUID，删除后重新生成会读取该 UUID 的原生玩家存档。
`/fp kill` 表示让假人离线，同时取消它的驻留；不会删除原生玩家存档。
`/fp resident true` 才表示服务器重启后自动恢复；只重新生成不等于自动重新执行上次动作。
此前已经被旧版本覆盖掉的物品或经验不能凭空恢复，需要使用服务器原有备份。

## 修复内容

- 名称检查不再通过主线程的 `getOfflinePlayer(String)` 联网查询；在线皮肤查询也异步完成。
- 生成流程直接由服务器任务完成 future，替换会占用 common-pool 线程等待的调度包装；30 秒超时会明确失败并清理。
- 登录中的假人保留登记；拒绝重名请求、失败后释放登记，取消登录后不会再次上线。
- 26.3 的 `placeNewPlayer` 已不负责读取存档：补上原生加载，保留背包、经验、末影箱、装备、当前手持格和饥饿等数据。
- 配置模板与读取键名统一，默认退出不掉背包。
- 驻留假人跳过创建者离线清理；按创建者 UUID 保存和识别，反复重启不丢失归属。
- 驻留快照与原生玩家数据配套保存；恢复失败、世界未加载的记录继续保留。世界可用后重试，每次启动对可尝试记录最多尝试三次。
- 驻留文件使用临时文件替换，并保留 `.bak`；解析出错时禁止自动覆盖原文件。
- 连续动作正确显示为正在运行，已完成的有限次数动作不再恢复；生成时的超强再生设置正确应用。
- 死亡先完成普通死亡/复活结算，再按配置退出，避免旧版取消死亡事件或提前退出导致的异常结算。
- 打包前强制重建 dist 基础 JAR，避免重复 `mvn package` 把旧类留在新产物中。

## 构建和验证

沿用 [BUILD-26.3.md](BUILD-26.3.md) 的 NMS 编译库准备方式。
需要 JDK 25 或更高版本、Maven、`lib/paper-26.3-compile.jar`。

```sh
mvn package
python3 tools/verify-package.py target/fakeplayer-0.5.0-paper26.3.jar
```

打包核对会逐个比对当前编译输出与发布 JAR 内的类。
JitPack 的 devtools SNAPSHOT 和 Maven 下载需要网络。本次下载的 88 个 devtools 类与用户原 JAR 完全一致。

真实服务端集成测试、测试辅助插件源码和复现方式见 [tools/integration/README.md](tools/integration/README.md)。
0.5.0 的实际测试结果和构建哈希见 [verification/RESULTS-0.5.0.md](verification/RESULTS-0.5.0.md)。
原修复版的记录仍保留在 [verification/RESULTS.md](verification/RESULTS.md)。
测试辅助插件不可安装到正式服；NMS 编译库和服务端文件不可打进插件或源码发布包。

## 本次新增的管理功能

以下实现对应选定的 1、2、5、6；物品存档多版本备份/导入导出和动作预设（原 3、4）未加入。

### 诊断

```text
/fp diagnose
/fp diagnose bot1
/fp diagnose <UUID>
```

生成请求会立即确认排队，完成后再提示成功。诊断可显示等待生成、目标区块准备、生成前命令、登录检查、读取存档、加入、传送、在线、失败、取消等状态，以及最近保存/恢复、失败阶段、事件结果。
普通玩家只看到自己的诊断；服务器诊断额外显示预算、各世界在线及预留数量、保存设置、原生命名存档路径和文件时间、监听插件及相关插件线索。
原生存档目录由 NMS 适配器读取，不拼接旧版 `world/playerdata` 路径。

`plugins/fakeplayer/diagnostics.yml` 保留最近的历史诊断，默认 200 条，活动中的生成不计入历史额度。重启后的历史条目标为历史状态，不会声称仍在进行旧生成；驻留恢复会更新当前状态。
日志事件使用 UTC 时间；命令中的摘要时间按服务器本地时区显示。

事件“被拒绝/被取消”是已观察结果。监听插件名单、已安装的登录/领地/同步插件仅是排查线索，不能据此认定哪个插件负责。
登录后 1 秒的背包变化也可能来自正常拾取或动作；更晚的外部同步不保证被观察到。诊断不会改动其他插件的配置，也不会自动解除它们的限制。

### 驻留记录管理

```text
/fp resident list
/fp resident list 2
/fp resident status bot1
/fp resident restore bot1
/fp resident forget bot1
```

这些命令在没有在线假人时也可用。名称和 UUID 均可定位记录，补全只列出可访问的记录。

- `list` 每页 10 条；`status` 显示归属、世界、状态、尝试次数和保存/恢复时间。
- `restore` 对单条记录手动重试，重置本次启动的失败尝试。已经在线/排队的记录不会重复生成。
- 世界未加载、名称/UUID 被占用、数量额度不足时，保留记录并给出原因；不会踢出占用者。
- 真正开始恢复后的登录/放置失败，每次启动自动尝试最多 3 次；原因消除后可手动重试。等待世界或额度不消耗失败次数。
- `forget` 只取消驻留。已在线假人继续在线；尚在恢复队列中的请求被取消；原生背包/经验存档保留。
- `/fp kill` 仍表示离线并取消驻留，包括尚未完成的恢复请求。
- 无法读取的 `resident.yml` 禁止自动覆盖和记录删除；先修复文件，再重启加载。

旧驻留文件可直接读取。缺少创建者 UUID 的旧记录仍兼容存储的创建者名字；有 UUID 的记录始终按 UUID 校验，角色改名不改变归属。

### 生成与保存预算

旧配置不补新字段也会使用以下默认值。可通过 `/fp reload` 重新读取：

```yaml
spawning:
  per-tick: 1
  max-concurrent: 4
  chunk-loads-per-tick: 1
  max-concurrent-chunk-loads: 2
  max-queued: 128
  max-players-per-world: 0
  world-limits: {}
  resident-saves-per-tick: 4

diagnostics:
  history-limit: 200
```

手动生成与驻留恢复共用队列；排队中的假人也占用目标世界的预留额度。
`max-players-per-world: 0` 表示不限，可以按 Bukkit 世界名覆盖，例如：

```yaml
spawning:
  max-players-per-world: 50
  world-limits:
    world: 100
    world_nether: 20
```

世界上限同样检查跨世界传入，不能通过管理员权限绕过。降低上限不会主动删除已在线假人，但会阻止继续加入。
目标区块由 Paper 异步准备，并在生成结束/取消后释放本插件的临时区块票据；这不是整个 Paper 内部区块生成数量或每 tick 毫秒耗时的硬上限。
队列满会立即拒绝请求；拒绝和启动前取消的请求不执行生成前命令。已经开始的 Paper 区块加载无法靠取消假人撤销。
真正开始处理后的单次生成有 30 秒超时；排队等待不计入该超时。

周期性驻留快照按 `resident-saves-per-tick` 分批保存。正常关服仍完整保存；这些设置不提供断电数据零丢失保证。

### 权限

保留现有权限节点和 `fakeplayer.spawn` 组，并增加：

| 权限 | 作用 |
| --- | --- |
| `fakeplayer.command.diagnose` | 访问自己假人的诊断 |
| `fakeplayer.command.resident` | 原驻留开关、记录列表与详情 |
| `fakeplayer.command.resident.restore` | 手动恢复自己的记录；同时需要 resident |
| `fakeplayer.command.resident.forget` | 取消自己的记录；同时需要 resident |
| `fakeplayer.admin.manage-others` | 允许相应操作访问他人假人和记录 |
| `fakeplayer.admin.diagnostics` | 服务器版本、设置、事件监听、存档路径等诊断详情 |
| `fakeplayer.admin.bypass-limits` | 绕过原有玩家/服务器/IP 配额，不绕过生成预算或世界上限 |
| `fakeplayer.admin.killall` | 使用 killall |
| `fakeplayer.admin.reload` | 重新读取配置和翻译 |

新增管理员能力默认只授予 OP。`fakeplayer.admin` 包含以上管理员节点，`fakeplayer.*` 包含该组。
`fakeplayer.spawn` 组包含自己的诊断、驻留恢复和取消记录；自定义生成名称仍遵循原有的 `fakeplayer.command.spawn.name` 权限。

管理他人的能力、服务器诊断、配额绕过彼此独立；授予 `manage-others` 不会自动授予 invsee、resident、cmd 等命令权限。
只授予记录管理或诊断时，无须额外授予 spawn。补全、显式名称、默认选择和右键背包入口使用同一归属规则；撤销权限后，旧选择不能绕过检查。
