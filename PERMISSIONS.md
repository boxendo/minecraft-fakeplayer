# FakePlayer 权限体系

> 适用版本：`0.6.0-paper26.3` 及以上
> 节点来源：`fakeplayer-core/src/main/resources/plugin.yml`

## 1. 命名空间约定

| 前缀 | 用途 | 示例 |
| --- | --- | --- |
| `fakeplayer.command.*` | 单条命令（或子命令） | `fakeplayer.command.spawn.location` |
| `fakeplayer.config.*` | 功能开关的配置权限 | `fakeplayer.config.autofish` |
| `fakeplayer.admin.*` | 管理员能力（不是命令） | `fakeplayer.admin.manage-others` |
| `fakeplayer.<组名>` | 权限组，本身不可执行 | `fakeplayer.basic` |

共 **51 个叶子节点 + 8 个权限组**。

## 2. 推荐授权方式

| 对象 | 授予 |
| --- | --- |
| 普通玩家 | `fakeplayer.basic` |
| 服主 / 管理 | `fakeplayer.basic` + `fakeplayer.management` |
| 全权（含管理命令） | `fakeplayer.*` |

> 根命令 `/fp` 注册时带有 `fakeplayer.command.spawn`，
> 因此**任何**子命令都额外需要它；`fakeplayer.basic` 已包含。

## 3. 权限组

### `fakeplayer.basic`（普通玩家，`default: false`）

只包含“操作自己的假人”所需权限，共 17 项：

```
fakeplayer.command.spawn              fakeplayer.command.spawn.name
fakeplayer.command.spawn.location     fakeplayer.command.kill
fakeplayer.command.list               fakeplayer.command.select
fakeplayer.command.selection          fakeplayer.command.distance
fakeplayer.command.status             fakeplayer.command.skin
fakeplayer.command.invsee             fakeplayer.command.hold
fakeplayer.command.respawn            fakeplayer.command.resident
fakeplayer.tp                         fakeplayer.exp
fakeplayer.action
```

其中 `fakeplayer.tp` = tp / tphere / tps，`fakeplayer.exp` = expme，
`fakeplayer.action` 见下文。

### `fakeplayer.management`（进阶管理，`default: false`）

```
fakeplayer.command.config             fakeplayer.command.set
fakeplayer.command.drop               fakeplayer.command.dropstack
fakeplayer.command.dropinv            fakeplayer.command.resident.restore
fakeplayer.command.resident.forget
```

### `fakeplayer.tp`

```
fakeplayer.command.tp  fakeplayer.command.tphere  fakeplayer.command.tps
```

### `fakeplayer.exp`

```
fakeplayer.command.expme
```

### `fakeplayer.action`

```
fakeplayer.command.attack     fakeplayer.command.mine
fakeplayer.command.use        fakeplayer.command.jump
fakeplayer.command.sneak      fakeplayer.command.sprint
fakeplayer.command.look       fakeplayer.command.turn
fakeplayer.command.move       fakeplayer.command.ride
fakeplayer.command.swap       fakeplayer.command.sleep
fakeplayer.command.wakeup     fakeplayer.command.hold
fakeplayer.command.stop       fakeplayer.config.replenish
fakeplayer.config.replenish.chest  fakeplayer.config.autofish
```

### `fakeplayer.admin`（默认 op）

```
fakeplayer.admin.manage-others      # 操作别人的假人
fakeplayer.admin.diagnostics        # /fp diagnose 的服务端级诊断
fakeplayer.admin.bypass-limits      # 绕过旧版创建数量限制
fakeplayer.command.killall
fakeplayer.command.reload
fakeplayer.command.reload-translation
fakeplayer.command.debug
fakeplayer.command.diagnose
fakeplayer.command.resident.restore
fakeplayer.command.resident.forget
```

### `fakeplayer.spawn`（兼容旧版）

保留原有 18 项（`spawn`、`kill`、`list`、`selection`、`drop*`、`skin`、
`invsee`、`status`、`respawn`、`config`、`set`、`resident`），供旧配置继续使用。
新部署请改用 `basic` + `management`。

### `fakeplayer.*`

包含：`fakeplayer.basic`、`fakeplayer.management`、`fakeplayer.spawn`、
`fakeplayer.tp`、`fakeplayer.exp`、`fakeplayer.action`、
`fakeplayer.command.cmd`、`fakeplayer.admin`。

## 4. 命令 → 权限

| 命令 | 权限 |
| --- | --- |
| `/fp spawn` | `spawn`（+`spawn.name` / `spawn.location`） |
| `/fp kill` | `kill` |
| `/fp list` | `list` |
| `/fp select` / `/fp selection` | `select` / `selection` |
| `/fp distance` | `distance` |
| `/fp status` | `status` |
| `/fp skin` | `skin` |
| `/fp invsee` | `invsee` |
| `/fp hold` | `hold` |
| `/fp respawn` | `respawn` |
| `/fp resident` | `resident` |
| `/fp resident restore` / `forget` | `resident.restore` / `resident.forget` |
| `/fp set` | `set` + 对应 feature 权限 |
| `/fp config` | `config` |
| `/fp tp` / `tphere` / `tps` | `tp` / `tphere` / `tps` |
| `/fp expme` | `expme` |
| `/fp drop` / `dropstack` / `dropinv` | 同名 |
| `/fp attack` / `mine` / `use` / `jump` | 同名 |
| `/fp sneak` / `sprint` / `look` / `turn` / `move` / `ride` / `swap` / `sleep` / `wakeup` / `stop` | 同名 |
| `/fp cmd` | `cmd`（还需 `allow-commands` 非空） |
| `/fp diagnose` | `diagnose`（服务端级诊断还需 `admin.diagnostics`） |
| `/fp killall` | `killall` |
| `/fp reload` / `/fp reload-translation` | `reload` / `reload-translation` |
| `/fp debug ...` | `debug`（还需 `debug: true`） |

## 5. 从旧版本迁移（0.6.0 权限拆分）

| 变化 | 旧 | 新 |
| --- | --- | --- |
| 新增普通玩家组 | — | `fakeplayer.basic` |
| 新增管理组 | — | `fakeplayer.management` |
| `/fp killall` 节点 | `fakeplayer.admin.killall` | `fakeplayer.command.killall` |
| `/fp reload` 节点 | `fakeplayer.admin.reload` | `fakeplayer.command.reload` |
| 翻译重载 | 复用 `reload` | `fakeplayer.command.reload-translation` |
| `/fp debug` | 硬编码 `OP` | `fakeplayer.command.debug` |
| `fakeplayer.spawn` 组 | 含 `diagnose`、`resident.restore`、`resident.forget` | 已移除（这些是 op 级） |

> 如果你之前直接给玩家授过 `fakeplayer.admin.killall` / `.reload`，
> 请改为新节点，或直接授 `fakeplayer.admin` 组。

## 6. 注意事项

1. **根命令门槛**：所有 `/fp` 子命令都需要 `fakeplayer.command.spawn`。
2. **只能操作自己的假人**：非 OP 默认只能管理自己创建的假人；
   `fakeplayer.admin.manage-others` 可突破。
3. **仅玩家命令**：`tp`/`tphere`/`tps`/`invsee`/`expme`/`config` 在控制台执行会提示无实现。
4. **默认值**：多数叶子节点未声明 `default`（等价于 op）；
   `fakeplayer.basic`、`fakeplayer.management` 显式 `default: false`，必须主动授予。

## 7. LuckPerms 示例

```bash
# 普通玩家：只用自己的假人
lp group default permission set fakeplayer.basic true

# 服主：额外允许改配置/丢背包/管理驻留记录
lp group admin permission set fakeplayer.basic true
lp group admin permission set fakeplayer.management true

# 全权
lp group owner permission set fakeplayer.* true
```
