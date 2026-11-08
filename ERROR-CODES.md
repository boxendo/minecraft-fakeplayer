# FakePlayer 错误码对照表

插件所有面向玩家的失败提示、以及会写进控制台的失败日志，都带一个 `[FP-XXXX]` 前缀。
按编号查本表即可定位原因。

```
[FP-3001] 名称最少 3 个字符
```

## 编号规则

| 段位 | 类别 | 典型问题 |
| --- | --- | --- |
| `1xxx` | 权限 | 缺命令/参数权限 |
| `2xxx` | 目标假人 | 找不到、没选中、太远、状态不符 |
| `3xxx` | 名称 | 名字长度、字符、重名、被真人占用 |
| `4xxx` | 配额与生成 | 数量上限、被登录事件拦截、传送失败 |
| `6xxx` | 执行与操作 | 假人执行命令失败、皮肤、背包、动作异常 |
| `7xxx` | 驻留 | 记录不存在、恢复/保存失败 |
| `8xxx` | 配置 | 配置项/取值非法、配置版本过旧 |
| `9xxx` | 内部/存储 | 生成异常、SQLite/used-uuids 读写失败 |

> 错误码是稳定编号，升级插件不会改变含义。

---

## 1xxx 权限

| 码 | 消息键 | 触发 | 原因与处理 |
| --- | --- | --- | --- |
| `1001` | `fakeplayer.command.spawn.error.no-permission-name` | `/fp spawn <名字> ...` | 缺 `fakeplayer.command.spawn.name`。授予权限，或改用 `/fp spawn - ...` 自动命名 |
| `1002` | `...spawn.error.no-permission-location` | 指定了世界/坐标 | 缺 `fakeplayer.command.spawn.location` |
| `1003` | `...cmd.error.no-permission` | `/fp cmd ...` | 执行者缺 `fakeplayer.command.cmd`，或 `allow-commands` 为空 |
| `1004` | `...cmd.error.fakeplayer-has-no-permission` | `/fp cmd` 执行时 | 假人本身没有该命令的权限，给它对应的权限组 |
| `1005` | `...config.set.error.no-permission` | `/fp set`、`/fp config set` | 缺该 feature 对应权限（如 `fakeplayer.config.autofish`） |

## 2xxx 目标假人

| 码 | 消息键 | 触发 | 原因与处理 |
| --- | --- | --- | --- |
| `2001` | `...generic.error.non-fake-player` | 任意需要假人的命令 | 你还没有假人，先 `/fp spawn` |
| `2002` | `...generic.error.non-matching-fake-player` | 带条件的命令（如 `respawn`） | 没有满足条件的假人（例如没有死亡的假人） |
| `2003` | `...generic.error.name-required` | 有多个假人 | 命令末尾追加假人名字，或先 `/fp select` |
| `2004` | `...selection.error.none` | `/fp selection` | 还没有选中假人，先 `/fp select <名字>` |
| `2005` | `...kill.error.non-removed` | `/fp kill` | 名字不匹配或已被移除 |
| `2006` | `...distance.error.too-far` | `/fp distance` | 离假人太远 |
| `2007` | `...ride.me.error.too-far` | `/fp ride me` | 假人离你太远 |
| `2008` | `...ride.entity.error.too-far` | `/fp ride entity` | 目标实体离假人太远 |
| `2009` | `...ride.entity.error.ride-self` | `/fp ride entity <假人自己>` | 不能让假人骑自己 |
| `2010` | `...invsee.error.not-the-same-world` | `/fp invsee` | 你和假人不在同一世界 |
| `2011` | `...expme.error.non-experience` | `/fp expme` | 假人没有可转移的经验 |
| `2013` | `...generic.error.no-access` | 指定了假人名字 | 假人不存在，或它不是你创建的（非 OP 只能操作自己的假人） |

## 3xxx 名称

| 码 | 消息键 | 触发 | 原因与处理 |
| --- | --- | --- | --- |
| `3001` | `...name.too-short` | 名字 < 3 字符 | 换更长的名字 |
| `3002` | `...name.too-long` | 名字 > 16 字符 | 换更短的名字 |
| `3003` | `...name.invalid` | 不符合 `name-pattern` | 改 config.yml 的 `name-chars`，或换名字 |
| `3004` | `...start-with-illegal-character` | 名字以 `-` 开头 | 换名字（`-` 单独使用表示自动命名） |
| `3005` | `...name.used` | 名字属于真实玩家 | 换名字 |
| `3006` | `...name.online` | 同名玩家在线 | 换名字，或等其下线 |
| `3007` | `...name.online-dead` | 同名假人在线但已死亡 | 用提示的 `/fp respawn` 复活 |
| `3008` | 控制台日志 | 自动命名连续失败 10 次 | 已用随机名兜底；检查 `name-prefix`/`name-template` 是否过长 |

## 4xxx 配额与生成

| 码 | 消息键 | 触发 | 原因与处理 |
| --- | --- | --- | --- |
| `4001` | `...spawn.error.server-limit` | 生成时 | 全服假人数达到 `server-limit` |
| `4002` | `...spawn.error.player-limit` | 生成时 | 你创建的假人数达到 `player-limit` |
| `4003` | `...spawn.error.ip-limit` | 生成时 | 同 IP 假人数达到上限（`detect-ip: true`） |
| `4004` | `...spawn.error.disallowed` | `AsyncPlayerPreLoginEvent` / `PlayerLoginEvent` 返回非 ALLOWED | 被其他插件（白名单、登录插件、反作弊）拦截，看提示里的原因 |
| `4005` | `...spawn.error.unknown` | 生成流程抛异常 | 查控制台同一时间的 `[FP-9001]` 堆栈 |
| `4006` | `...spawn.error.teleport-failed` | 生成后传送 | 被领地/传送类插件取消 |
| `4007` | `...spawn.error.no-mob-spawning-ability` | 生成后 | 假人没有跨维度，刷怪能力未激活；按提示 `tphere` 到另一维度再回来 |
| `4008` | `fakeplayer.listener.login.deny-used-uuid` | 假人登录 | UUID 与真实玩家冲突，删除 `used-uuids.txt` 中对应行或换名字 |
| `4009` | `fakeplayer.manager.remove-all-on-low-tps` | 全服广播 | TPS 低于 `kale-tps`，非驻留假人被自动清理 |

## 6xxx 执行与操作

| 码 | 消息键 | 触发 | 原因与处理 |
| --- | --- | --- | --- |
| `6001` | `...cmd.error.execute-failed` | `/fp cmd` | 命令写错，或假人缺少对应权限 |
| `6002` | `...teleport.error.canceled` | `/fp tp`/`tphere`/`tps` | 被其他插件取消传送 |
| `6003` | `...skin.error.too-many-operations` | `/fp skin` | 频繁复制离线玩家皮肤被限流，稍后重试 |
| `6004` | 控制台日志 | 假人执行命令失败 | 检查命令拼写与权限 |
| `6005` | 控制台日志 | `post-spawn-commands` 等派发失败 | 检查配置里的命令 |
| `6006` | 控制台日志 | 动作 tick 抛异常 | 附堆栈，记录动作类型后反馈 |
| `6007` | 控制台日志 | OpenInv 打开背包失败 | 检查 OpenInv 版本/兼容性 |
| `6009` | 控制台日志 | 无法从 Mojang 获取皮肤档案 | 网络问题或该玩家不存在 |
| `6010` | 控制台日志 | 更新在线皮肤失败 | 目标不是真实玩家 |

## 7xxx 驻留

| 码 | 消息键 | 触发 | 原因与处理 |
| --- | --- | --- | --- |
| `7001` | `fakeplayer.resident.not-found` | `/fp resident status/restore/forget <记录>` | 记录不存在、已删除，或不属于你 |
| `7002` | `fakeplayer.resident.unreadable` | 读取驻留记录 | 记录文件损坏/无权限，查控制台 `[FP-7003]`~`[FP-7006]` |
| `7003` | 控制台日志 | 启动恢复某条记录失败 | 记录保留；按日志里的 reason 处理（世界未加载等） |
| `7004` | 控制台日志 | 记录格式非法 | 记录保留，修正 `resident` 数据后 `/fp resident restore <名字>` |
| `7005` | 控制台日志 | 保存某条驻留失败 | 检查磁盘/权限 |
| `7006` | 控制台日志 | 整批恢复流程异常 | 看堆栈，通常伴随 `7003` |

## 8xxx 配置

| 码 | 消息键 | 触发 | 原因与处理 |
| --- | --- | --- | --- |
| `8001` | `...config.set.error.invalid-key` | `/fp set`、`/fp config set` | feature 名拼错，用 tab 补全 |
| `8002` | `...config.set.error.invalid-value` | 同上 | 取值不在该 feature 的候选内（多数是 `true`/`false`） |
| `8003` | `fakeplayer.configuration.out-of-date` | 启动/重载 | config.yml 版本旧，删除后让插件重新生成，或按提示迁移 |
| `8004` | `/fp spawn` 世界参数 | `/fp spawn <名字> <世界> ...` | 世界名不存在；提示里会列出当前可用世界（用 `world`、`overworld`，不要用 `minecraft:` 前缀） |

## 9xxx 内部与存储

| 码 | 位置 | 含义 | 处理 |
| --- | --- | --- | --- |
| `9001` | 控制台 | 生成流程未捕获异常 | 带堆栈反馈；先确认 26.3 版本与依赖插件兼容 |
| `9002` | 控制台 | 保存假人数据失败 | 检查数据库/磁盘 |
| `9003` | 控制台 | `used-uuids.txt` 有非法 UUID | 删除该行 |
| `9004` | 控制台 | 读取 `used-uuids.txt` 失败 | 检查文件权限 |
| `9005` | 控制台 | 写入 `used-uuids.txt` 失败 | 检查磁盘空间/权限 |
| `9006` | 控制台 | 检查更新失败 | 网络问题，可忽略或关闭 `check-for-updates` |

---

## 没有错误码的提示

以下两类不属于“运行错误”，因此没有编号：

| 提示 | 含义 |
| --- | --- |
| `Unknown or incomplete command` / `错误的命令参数` | 命令没写完（缺少必填参数）或参数类型不对，例如 `/fp cmd` 少了命令、`/fp ride entity` 少了实体。用 `/fp help` 查看完整参数 |
| `This command has no implementations for craftremoteconsolecommandsender` | 该命令只能由玩家执行（`tp`/`tphere`/`tps`/`invsee`/`expme`/`config` 等），控制台/RCON 无法使用 |

---

## 排查顺序建议

1. 看到 `1xxx` → 先补权限（注意 `/fp` 根命令还需要 `fakeplayer.command.spawn`）。
2. 看到 `2xxx` → 用 `/fp list` 确认假人是否存在、是否选中、距离是否过远。
3. 看到 `3xxx`/`4xxx` → 多半是名字或配额，按表格直接调整。
4. 看到 `4xxx` 的 `4004`/`4006` → 大概率是**其他插件**拦截，结合 `[FP-...]` 提示里的原因排查。
5. 看到 `7xxx` → 检查 `plugins/fakeplayer/` 下的驻留记录，必要时 `/fp resident list` / `restore`。
6. 看到 `9xxx` → 需要看完整堆栈，用 `/fp diagnose` 导出的信息一起反馈。

## 反馈问题时请附上

- 错误码（如 `FP-4006`）
- `/fp diagnose` 的输出
- 出错前后的控制台日志（含 `[FP-xxxx]` 行）
