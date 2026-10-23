# 0.4.1-paper26.3 验证记录

日期：2026-10-23（Asia/Shanghai）。报告只描述以下构建的本地独立服务端验证。

- 交付 JAR：`target/fakeplayer-0.4.1-paper26.3.jar`
- SHA-256：`a40f29487ed149145bc8850adc695a7403004f2804b5d4f23f2204cca1bda2ec`
- 用户原 JAR SHA-256：`3e153bb9cebfb11882e34acb54444fd9a768bad640cd3dd2389a2f547e65780d`
- 源码基线：fork 的 `7f35b72`，修复保存在本地 `fix/paper26.3-state-and-spawn` 分支工作区。
- 实测服务端：Paper 26.3 build 205 / a90108f，CommandAPI 12.1.0，JDK 26.0.2.1。
- 独立超平坦世界、和平难度、本机 RCON、offline-mode；使用测试插件创建玩家身份的创建者。
- 141 个项目类与当前编译输出逐一一致；88 个 devtools 类与用户原 JAR 相同。

## 原版复现与定位

给原 JAR 的同名假人 7 个钻石和 123 点经验（8 级），删除后再生成，`XpLevel` 为 0，`Inventory` 为 `[]`。
见 `baseline-before-restart.log`。26.3 将原生玩家存档加载移出 `placeNewPlayer`，旧移植版未补上该步骤。

原版在控制台/RCON 创建假人的简单重启用例能恢复；因此不能据此认定所有驻留用例失败。
代码检查发现创建者离线清理不跳过驻留，原版的控制台测试不足以覆盖玩家创建的实际场景。
此外，恢复失败或世界不可用的记录会在后续全量快照中丢失。

主线程名称档案查询和等待 common-pool 的调度包装均存在生成延迟风险。没有用户实际服日志，不能认定这就是该服所有生成失败的唯一原因。

## 当前构建回归

`current/` 中每个测试报告均由本次候选 JAR 运行后重新生成，不复用旧报告。

| 项目 | 结果与证据 |
| --- | --- |
| 删除再生成 | 同名 UUID、背包、主副手、装备、末影箱、手持格、123 点经验、饥饿值通过，见 `current/state.txt` |
| expme | 全额转移、源经验归零、重复操作不重复领取通过，见 `current/state.txt` |
| 生成中查询/重名 | 延迟登录仍登记，大小写重名被拒绝，登录拒绝后释放登记，见 `current/pending.txt` |
| 取消生成 | 登录完成前删除，不再产生幽灵登录，见 `current/cancel.txt` |
| 在线皮肤/再生 | 开启在线皮肤时生成约 0.07 秒完成，不等待网络完成；默认超强再生实际生效，见 `current/skin-spawn.txt` |
| 真实动作 | 跳跃实际改变位置、挖掘实际破坏石头，见 `current/actions.txt` |
| 普通死亡 | 保留关闭时产生实际掉落，再生成背包与经验清空，见 `current/death-drop.txt` |
| 死亡保留 | 开启时实际掉落实体数为 0，背包、装备、双手、经验恢复，见 `current/death-keep.txt` |
| 主动复活 | 不踢出模式中保持死亡状态，可主动复活并继续实际跳跃，见 `current/respawn.txt` |
| 玩家创建者离线 | 两次执行离线清理仍保留驻留假人，连续动作显示为活动，见 `current/resident.txt` |
| 完整重启 | 对保存位置、动作、物品、经验及创建者 UUID 做两次连续正常重启核对，见 `current/restored-first.txt`、`current/restored-second.txt` |
| 世界延后加载 | 不可用世界的记录经保存仍存在，世界加载后恢复，见 `current/unavailable-first.txt` |
| 重启后的实际动作 | 对重启后位置进行连续采样，见 `current/resident-motion.txt` |
| 打包一致性 | 141 个项目类逐个比对通过，见 `current/package.txt` |

## 验证边界

这些结果覆盖服务端数据与动作；没有真实玩家客户端的渲染、Tab、背包 GUI 验证，也没有用户实际服或其他插件组合的兼容性验证。
没有验证其他 Paper build、代理/跨服背包同步、真实在线账号改名、崩溃断电后的文件系统持久性。
原子文件替换及解析异常保护已做代码检查；不能将正常停服重启测试等同于断电恢复测试。

`diagnostics/` 保留排查中的中间记录，不能作为当前版本的通过证据。曾出现以下测试/构建问题并已分别处理：

- 打包复用旧类：强制重建 dist JAR，并增加逐类比较。
- 默认再生在连接建立前应用会抛异常：移至连接建立后，重新测试通过。
- 挖掘用例被附近假人挡住射线：移动至独立位置。
- 驻留假人拾取死亡测试的正常经验掉落，使精确经验断言受到干扰：分离驻留位置与死亡测试位置。

本地运行日志位于 `current/release-a.log`、`current/release-b.log`、`current/release-c.log`，原始 `.log` 日志不纳入 Git；构建身份见 `current/build-identity.json`。
