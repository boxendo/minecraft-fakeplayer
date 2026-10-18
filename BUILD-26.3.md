# fakeplayer for Paper 26.3

把 [tanyaofei/minecraft-fakeplayer](https://github.com/tanyaofei/minecraft-fakeplayer) 移植到 **Minecraft / Paper 26.3**（Java 25）的工程。

- 上游版本：0.3.19（master，最高只到 1.21.10）
- 本工程版本：**0.4.0-paper26.3**
- 构建产物：`target/fakeplayer-0.4.0.jar`
- 实测环境：Paper 26.3 build 205（stable）+ JDK 25 + CommandAPI 12.1.0

---

## 1. 为什么不是"改个版本号"

上游是按 Minecraft 版本拆模块的 NMS 插件：`NMSServerPlayer`、`FakeConnection`、`ServerGamePacketListenerImpl` 子类等直接继承/调用服务端内部类。26.3 相对 1.21.10 有三类不兼容：

| 类别 | 具体变化 | 影响 |
| --- | --- | --- |
| NMS 签名 | `swing()`、`interactOn()`、`interactAt()`、`startRiding()`、`drop()`、`ClientboundSetEntityMotionPacket`、`Entity#hurtMarked`、`PlayerAdvancements#stopListening` | 需要改代码，编译器会报错 |
| 运行时库 | Paper 26.x 内置 **Adventure 5**，删除了 `UTF8ResourceBundleControl` 和 `TranslationRegistry`（devtools 依赖它们） | 插件一启用就崩 |
| 插件生态 | CommandAPI 12 改了 `Argument` 构造器；旧的 11.x 不支持 26.3 的 NMS | 自定义参数类无法编译/运行 |

另外 26.3 的断开流程改成了 `connection.send(disconnectPacket, PacketSendListener.thenRun(() -> connection.disconnect(details)))`，而假人的 `FakeChannel` 是空实现、`isOpen()` 永远返回 `true`，导致 `/fp kill` 静默失效——这是本次移植中唯一需要"读懂服务端行为"才能修的 bug，下面第 5 节说明。

---

## 2. 工程结构

```
fakeplayer-263/
├── pom.xml                  # 只保留 4 个模块
├── fakeplayer-api/          # SPI 接口（未改动）
├── fakeplayer-core/         # 插件主体 + Adventure 5 兼容垫片
├── fakeplayer-v26_3/        # 26.3 的 NMS 实现（由 v1_21_6 移植）
├── fakeplayer-dist/         # shade 打包
└── lib/
    └── paper-26.3-compile.jar   # 编译用：Paper 26.3 服务端 + 全部依赖（需自行生成）
```

移除了 `fakeplayer-v1_20_x` ~ `fakeplayer-v1_21_10` 共 16 个模块：它们各自需要 BuildTools 生成的 remapped-mojang jar，且对 26.3 无意义。

除 `lib/paper-26.3-compile.jar` 外 **不需要任何本地 jar**：

- PlaceholderAPI 通过 Maven 仓库 `repo.extendedclip.com` 解析（`me.clip:placeholderapi:2.11.6`）；
- OpenInv 只发布在 GitHub Releases、没有 Maven 坐标，因此改为反射调用 `IOpenInv`，不再需要 `lib/OpenInv.jar`。

---

## 3. 构建

### 3.1 准备编译用的服务端 jar

Paper 从 1.20.5 起运行时就是 **Mojang 映射**，所以 26.3 不需要 BuildTools / specialsource 重映射，直接对着 Paper 自己的服务端 jar 编译即可。

```bash
# 1) 下载 Paper 26.3（本工程实测 build 205）
#    https://fill.papermc.io/v3/projects/paper/versions/26.3/builds/latest
java -jar paper-26.3-205.jar --nogui     # 首次启动会生成 versions/26.3/paper-26.3.jar
#    看到 "Done" 后 Ctrl+C 即可，仅为了生成这个 jar

# 2) 把服务端 jar 连同 libraries/ 下的依赖合并成编译 classpath
#    见 tools/merge-classpath.py（Python 3，无需第三方库）
python tools/merge-classpath.py \
    --server versions/26.3/paper-26.3.jar \
    --libraries libraries \
    --out lib/paper-26.3-compile.jar
```

> `lib/paper-26.3-compile.jar` 约 105 MB，只用于编译（`system` scope，不会打进插件），
> 属于 Mojang 版权代码，不要随插件分发。

### 3.2 编译

```bash
export JAVA_HOME=/path/to/jdk-25
mvn -DskipTests package
# 产物：target/fakeplayer-0.4.0.jar（约 1.4 MB）
```

因为 `pom.xml` 里 `paper-api` 固定为 `26.3.build.205-stable`，如需换 build 号，改 `paper.version` 即可。

---

## 4. 运行

1. 服务端：**Paper 26.3**（Java 25）
2. 必需插件：**CommandAPI 12.1.0+**（本工程用 12.1.0-Paper 验证）
3. 可选：PlaceholderAPI、OpenInv
4. 把 `fakeplayer-0.4.0.jar` 丢进 `plugins/`，重启

```
[Server thread/INFO]: [fakeplayer] Enabling fakeplayer v0.4.0
[Server thread/INFO]: [fakeplayer] Enabled in 883 ms
> fp spawn bot1
[Server thread/INFO]: bot1 joined the game
> fp status bot1
> fp kill bot1
[Server thread/INFO]: bot1 lost connection: [fakeplayer] command kill
```

### 假人驻留（重启保留）

```
> fp resident true bot1        # 开启，立刻写入 resident.yml
> fp list                      # 该假人后面会显示“驻留（重启保留）”
> fp resident false bot1       # 关闭，删除记录
```

另有更细粒度的入口：`/fp set resident true bot1`（单个假人）、
`/fp config set resident true`（该玩家以后生成的假人都默认驻留）。
命令需要权限 `fakeplayer.command.resident`（默认包含在 `fakeplayer.spawn` 组里）。

---

## 5. 代码改动清单

### 5.1 NMS（`fakeplayer-v26_3`，由 `v1_21_6` 复制后改名）

| 文件 | 改动 |
| --- | --- |
| 全部 | 包名 `v1_21_6` → `v26_3`；`org.bukkit.craftbukkit.v1_21_R5.*` → `org.bukkit.craftbukkit.*`（Paper 不重定位 CraftBukkit） |
| `spi/NMSBridgeImpl` | `SUPPORTS = Set.of("26.3")` |
| `spi/NMSServerPlayerImpl` | `drop(stack, flag, flag1)` → `drop(stack, flag, Prediction.SERVER_ONLY, flag1, null)`；`startRiding(e, force)` → `startRiding(e, force, true)` |
| `action/AttackAction`、`MineAction`、`UseAction` | `swing(hand)` → `swing(hand, SwingAnimation.DEFAULT, true)` |
| `action/UseAction` | `entity.interactAt(player, pos, hand)` → `entity.interact(player, hand, pos)`（26.3 把 interactAt 合并进 interact）；`player.interactOn(entity, hand)` → `interactOn(entity, hand, pos)` |
| `network/FakePlayerAdvancements` | 删除 `stopListening()` 重写（26.3 已移除该方法） |
| `network/FakeServerGamePacketListenerImpl` | `ClientboundSetEntityMotionPacket#getId/getXa` → record 的 `id()/movement()`；`Entity#hurtMarked` 已删除，直接 `lerpMotion(movement)`；`DiscardedPayload#data()` 直接返回 `byte[]` |

### 5.2 连接 / 断开（`fakeplayer-core` + `v26_3`）

26.3 的踢人流程：

```java
// ServerCommonPacketListenerImpl#disconnect0
connection.send(new ClientboundDisconnectPacket(reason),
                PacketSendListener.thenRun(() -> connection.disconnect(details)));
connection.setReadOnly();
```

而假人这边：

1. `FakeConnection#send(Packet, ChannelFutureListener)` 原本是空方法 → **监听器永远不回调** → `connection.disconnect()` 不执行；
2. `FakeChannelPipeline#close()` 直接返回成功、不调用 `doClose()` → channel 永远 `isOpen() == true`；
3. `FakeConnection#isConnected()` 恒为 `true` → `Connection#tick()` 里 `if (!isConnected()) handleDisconnection()` 永不成立。

三者叠加 = `/fp kill` 提示成功但假人还在。修复：

- `FakeChannel`：`isOpen()/isActive()` 真实反映 `closed` 状态；重写 `close()`/`close(promise)` 设置 `closed`。
- `FakeConnection`：`isConnected()` 改为 `channel != null && channel.isOpen()`；`send()` 立即以成功 future 回调 `ChannelFutureListener`；重写 `disconnect(details)`，在 `super` 之后主动 `handleDisconnection()`（假人没有 netty 的 `channelInactive` 事件）。

修复后日志：`bot1 lost connection: [fakeplayer] command kill` / `bot1 left the game`。

### 5.3 Adventure 5 兼容层（`fakeplayer-core`，新增）

Paper 26.x 用 Adventure 5，删掉了 devtools 依赖的两个类。没有改 devtools（JitPack 产物），而是补了同签名垫片：

| 新增文件 | 作用 |
| --- | --- |
| `net/kyori/adventure/util/UTF8ResourceBundleControl.java` | 以 UTF-8 读取 `.properties` 语言文件 |
| `net/kyori/adventure/translation/TranslationRegistry.java` | 必须是**接口**（devtools 字节码用 `InterfaceMethodref` 调用 `create`），内部 `SimpleTranslationRegistry` 做 locale 查找与默认 locale 回落 |

### 5.4 CommandAPI 12

- 依赖 `dev.jorel:commandapi-paper-core:11.0.0` → `12.1.0`
- `FakePlayerCommandArgument`：`super(name, StringArgumentType.greedyString())` → `super(name, () -> StringArgumentType.greedyString())`

### 5.5 其它

- `plugin.yml`：`api-version: '1.20'` → `'26.3'`
- 根 `pom.xml`：`revision` 0.4.0、`paper.version`、`lombok` 1.18.48（支持 JDK 25）、`authlib` 10.0.77
- `fakeplayer-dist`：只 shade core / api / v26_3，去掉拷贝到 `server-1.20.x` 的 antrun 配置
- 去掉本地 jar 依赖：PlaceholderAPI 改为 Maven 依赖 `me.clip:placeholderapi:2.11.6`（provided），
  `OpenInvInvseeManagerImpl` 改为反射调用 OpenInv，`lib/` 下不再需要任何第三方 jar

### 5.6 新增：假人驻留（重启保留）

| 文件 | 说明 |
| --- | --- |
| `core/manager/ResidentManager.java` | 新增。`resident.yml` 快照 / 恢复 / 定时兜底保存 |
| `core/entity/ResidentState.java` | 新增。保存世界、坐标、朝向、动作、状态标志 |
| `core/command/impl/ResidentCommand.java` | 新增。`/fp resident <true\|false> [假人]` |
| `core/util/OfflineCommandSender.java` | 新增。重启后创建者不在线时的占位 sender（**无任何权限**） |
| `core/entity/Fakeplayer.java` | 新增 `resident` 字段；`isCreatedBy` 允许离线创建者按名字匹配 |
| `core/entity/SpawnOption.java` | 新增 `resident` 字段，作为新假人的默认驻留配置 |
| `core/manager/FakeplayerManager.java` | 关闭前先快照再踢人；低 TPS 清理跳过驻留假人；假人退出时取消驻留 |
| `core/manager/action/ActionManager.java` | 新增 `getActions(Player)`，用于导出正在执行的动作 |
| `core/Main.java` | 启用后延迟 1 秒恢复驻留假人 |
| `core/repository/model/Feature.java` | 新增 `resident` 配置项（`/fp set`、`/fp config` 可用） |
| `core/command/impl/ListCommand.java` | `/fp list` 显示“驻留（重启保留）”标记 |

**行为**

1. `/fp resident true bot1`（或 `/fp set resident true bot1`）立即写入 `plugins/fakeplayer/resident.yml`；
2. 插件关闭时先快照全部驻留假人，再走正常踢人流程，所以不会丢记录；
3. 服务器下次启动、插件启用 1 秒后，按保存的世界 / 坐标 / 朝向重建假人，并恢复：
   正在执行的动作（含剩余次数）、疾跑 / 潜行、无敌 / 碰撞 / 拾取、金刚狼、自动补货、自动钓鱼；
4. `/fp resident false` 或 `/fp kill`、假人死亡会删除对应记录，下次不再恢复；
5. 低 TPS 自动清理会跳过驻留假人；
6. 每 10 秒自动快照一次，防止异常宕机丢失最后状态。

**权限**

| 权限 | 作用 |
| --- | --- |
| `fakeplayer.command.resident` | 使用 `/fp resident`；同时是 `Feature.resident` 的配置权限（`/fp set`、`/fp config set`） |
| `fakeplayer.command.spawn` | `/fp` 根命令的既有权限（所有子命令仍然需要） |
| `fakeplayer.command.set` / `fakeplayer.command.config` | 走 `/fp set` / `/fp config` 路径时额外需要 |

- `fakeplayer.command.resident` 已加入 `plugin.yml` 的 `fakeplayer.spawn` 权限组；
- 非 OP 玩家只能操作自己创建的假人（沿用 `fakeplayer(name)` 参数的创建者过滤）；
- 恢复时的 `OfflineCommandSender` **不继承任何权限**（`isOp()`、`hasPermission()` 均为 `false`），
  仅用于让“创建者是谁”的过滤逻辑继续成立，避免通过创建者身份提权。

---

## 6. 实测结果（本机，Paper 26.3 build 205）

| 项目 | 结果 |
| --- | --- |
| 插件加载 / 启用 | ✅ `Enabled in 883 ms` |
| 中文语言文件与占位符 | ✅ `/fp help`、`/fp status` 正常渲染 |
| `/fp spawn <name>` | ✅ `joined the game`，分配 entity id |
| 假人 tick（移动/物理） | ✅ 位置从 `0,-60,0` 变化到 `-1,-60,-1.5` |
| `/fp list`、`/fp status` | ✅ |
| `/fp sneak`、`/fp sprint`、`/fp stop` | ✅ |
| `/fp kill`、`/fp killall` | ✅ `lost connection` + `left the game`，列表清空 |
| `/fp respawn` | ⚠️ 需要假人死亡（默认无敌），未触发 |
| 关服清理 | ✅ `bot2 lost connection: [fakeplayer] Plugin disabled` |
| `/fp attack`、`/fp mine`、`/fp use` | ⚠️ 需要附近目标/方块，仅验证命令注册与参数解析，未测实际命中 |
| 皮肤 / PlaceholderAPI / OpenInv / BungeeCord 转发 | ⚠️ 未测（编译期依赖已就绪） |
| **驻留**：`/fp resident true` + 重启 | ✅ 写入 `resident.yml`，重启后 `Restored ... at x,y,z` 原位恢复 |
| **驻留**：动作继承 | ✅ 重启前 `fp jump continuous`，重启后假人仍在原地跳跃（y 在 -60 / -59 之间变化） |
| **驻留**：`/fp resident false` + 重启 | ✅ 记录被删除，重启后不再出现 |
| **驻留**：`/fp list` 标记 | ✅ 显示“驻留（重启保留）” |

> 测试环境：flat 世界、无真人玩家，命令通过 RCON 以控制台身份执行；
> 因此 `fp tp`、`fp invsee` 这类 `executesPlayer` 的命令无法覆盖。

---

## 7. 已知限制

1. 只支持 26.3。要兼容别的版本，需要按同样方式新增 `fakeplayer-vXX` 模块。
2. 必须安装 **CommandAPI 12.1.0+**。CommandAPI 11 不支持 26.3 的 NMS。
3. 26.3 的 `Entity#hurtMarked` 已被移除，击退改为直接 `lerpMotion`；如果后续版本恢复客户端预测语义，需要重新核对。
4. 假人仍然没有真实客户端，`readExtraData`、advancements 屏蔽等行为依赖服务端内部实现，跨大版本时需要复查。
5. 编译用的 `lib/paper-26.3-compile.jar` 含 Mojang 代码，仅限本地构建，不要分发。
6. 驻留只保存**位置、朝向、正在执行的动作和常用状态开关**，不保存背包、经验、血量；
   一次性动作（`remains` 已耗尽）不会被恢复。
7. 驻留恢复依赖世界已加载；如果保存的世界被删除/改名，该假人会被跳过并输出警告。

---

## 8. 一键复现（本机工作区路径）

工作区里已经准备好 JDK 25 与 Maven，可直接构建并跑测试服：

```bash
cd fakeplayer-263

# 构建（JDK / Maven 在上级 tools/）
JAVA_HOME=../tools/jdk25/jdk-25.0.4.1+1 \
  ../tools/apache-maven-3.9.11/bin/mvn -DskipTests package

# 本地测试服
cp target/fakeplayer-0.4.0.jar ../server-26.3/plugins/
../tools/jdk25/jdk-25.0.4.1+1/bin/java -jar ../server-26.3/paper.jar --nogui
```
