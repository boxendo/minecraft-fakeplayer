# Paper 集成回归

这里的 Java 插件仅用于独立测试服。它调用真实 Paper 的玩家、物品、经验、死亡与动作实现，不是模拟服务端测试。

环境：Paper 26.3 build 205、CommandAPI 12.1.0、JDK 26.0.2.1。
使用独立超平坦世界、和平难度，仅监听 127.0.0.1。不要连接正式服或将辅助插件用于正式环境。

1. 先构建正式插件，并按主构建说明准备 `lib/paper-26.3-compile.jar`。
2. 用该编译库、模块编译输出和插件依赖组成 classpath，编译 `VerificationPlugin.java`。
3. 将编译后的 `verification/` 类目录与这里的 `plugin.yml` 打成测试 JAR，放入独立服务器。
4. 配置本机 RCON；通过 `FP_TEST_RCON_PORT`、`FP_TEST_RCON_PASSWORD` 指定凭据。
5. 启动后运行 `python3 tools/integration/run-phase.py <phase> <server-directory>`。

| phase | 验证内容 |
| --- | --- |
| state | 同名 UUID、删除再生成、背包/装备/双手/末影箱/经验/饥饿、expme 转移及防重复领取 |
| pending | 延迟登录期间登记稳定、大小写重名拒绝、拒绝登录后释放登记 |
| cancel | 登录完成前删除，不再产生幽灵上线 |
| actions | 实际跳跃和挖掘石头；使用与其他假人隔离的位置以避免射线被遮挡 |
| death | 在 keep-inventory-on-death 开/关时分别运行，检查实际掉落实体、再生成的物品和经验 |
| resident | 玩家身份创建驻留假人，创建者离线并运行清理，保存背包和持续动作 |
| restored | 正常关服重启后，核对数据、位置、动作和创建者 UUID；可重复重启后再运行 |
| unavailable | 在启动前加入 late_world 的待恢复记录，检查保存不会删记录，并加载世界验证重试 |
| respawn | 设置 kick-on-dead=false、keep-inventory-on-death=true，验证主动复活及复活后动作 |

每次 phase 会删除旧报告，并等待新报告完成；结果保存在测试服 `plugins/FakeplayerVerification/`。
phase 应依次运行，避免并发测试共用报告或假人。驻留用例使用 (100, -60, 100)，与普通生成、死亡和挖掘位置分开，避免拾取其他用例的经验掉落。
`resident` 后必须真正正常停止并重新启动服务器，再运行 `restored`。
`unavailable` 的待恢复记录应在停服后写入 YAML，不能修改运行中的插件数据文件。
测试端的 `seed` 为手工调试入口，常规测试不使用。

RCON 只能验证服务端状态，不能证明真实玩家客户端渲染、背包 GUI、代理或其他插件组合的兼容性。


## 0.5.0 管理功能回归

新增阶段：

- `management`：读取/删除归属隔离、无需 spawn 的个人管理、非 OP 管理员、独立诊断和配额权限、撤权后的默认选择、背包查看权限、UUID 改名与原生存档保留。
- `conflicts`：真实登录拒绝、传送取消、踢出取消、失败阶段及监听线索、诊断落盘。
- `budgets`：多目标区块、每 tick/并发限制、满队列拒绝、生成前命令只执行一次且拒绝请求不执行、世界预留和跨世界传入限制。
- `race`：取消已开始的慢登录后立刻重新生成，旧回调不能移除新登记或污染新诊断。
- `skin-configured`：在线皮肤不阻塞生成，默认再生在连接建立后生效。
- `closing`：关闭队列完成取消 future 并释放票据，不出现迟到上线；必须放在本次进程最后。
- `journal-restored`：重启后保留失败阶段和事件证据，旧操作不会显示为正在运行。
- `offline-management`：世界不可用、名称占用、3 次真实失败、手动重试、取消驻留后保留原生数据。

`management` 隔离关闭 detect-ip，避免同机伪造创建者与控制台地址重合；玩家配额仍保留并单独断言。
测试辅助插件捕获假人收到的系统消息，以检查命令实际输出和他人数据不泄漏；这不等同于真实客户端渲染测试。

`offline-management` 需要在停服后，以 residentbot 的快照为模板添加以下隔离记录：parkedbot（late_world）、conflictbot（world）、retrybot（world）。三条记录使用新的 UUID，位置远离 residentbot。
同时在测试插件的数据目录创建 `spawn-conflict.flag` 和 `deny-retry.flag`。辅助插件会先占用 conflictbot 名字，并拒绝 retrybot 的登录。该阶段验证冲突解除、重试耗尽与手动恢复后自行清理测试记录。

所有发布验证必须重新运行 `tools/verify-package.py` 并将报告绑定到实际测试 JAR 的 SHA-256；不要覆盖 0.4.1 的验证目录。


## 0.5.1 数据保护回归

- `integrity-case`：标准英文名称三种大小写重登复用 UUID，保留完整物品/经验状态；旧冲突保持独立档案，模糊拼写被拒绝，新重复档案不能插入。
- `integrity-nearest`：顺序颠倒、自己、已移除实体及空候选集合。
- `integrity-refill`：正常补货、重复请求、更换手持格/物品/窗口、关闭补货、事件取消、事件修改源物品/手持物品及退出期间的补货。
- `integrity-action`：注入带冷却的抛错动作，检查三次失败后停止、其他动作继续、诊断、驻留快照排除及替换动作清理。
- `integrity-resident-toggle`：快速开启/取消驻留，最终关服文件不得残留 Toggle51。

`integrity-case` 需要先在停止的隔离测试服 `data.db` 的 `fake_player_profile` 表中准备 `LegacyCase51`、`legacycase51` 两行，UUID 不同。这模拟旧版本存档；只在测试库中暂时删除 `fake_player_profile_case_insert` 触发器插入，启动插件会重新建立保护。绝不能对正式服做此测试准备。

复用旧测试服运行 `offline-management` 时，旧测试曾以不同 UUID 恢复过 conflictbot，可能留下与档案不一致的名称缓存。停服后先备份测试服 `usercache.json` 并移除 conflictbot、retrybot、parkedbot 的缓存条目，再准备驻留记录与 flag；这仅是隔离测试清理步骤。

`tools/tests/YamlSaveQueueTest.java` 是无需服务器的写盘队列故障注入测试。用与辅助插件相同的编译 classpath 编译后，运行 `io.github.hello09x.fakeplayer.core.util.YamlSaveQueueTest`。覆盖深层快照隔离、待写合并、自动重试、新值优先、关服刷新和失败报告。

诊断写盘测试等待当前尝试时间戳落盘，不使用上一次测试遗留的文件作为通过证据。每个发布 JAR 都应重新运行构建类核对，并保存真实关服/重启阶段的报告。
