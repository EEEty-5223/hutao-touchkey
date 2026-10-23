# 发给豆包或其他 AI 的第一条消息

把下面整段文字发给 AI，同时上传本版本源码 ZIP。不要只发 APK：APK 不是可直接移植的 iOS 源码。若 AI 不能读 ZIP，解压后优先上传本文末尾列出的文件。完整交付包里还有 Android APK，它们供原平台安装，不要让 AI 当成 IPA。

---

我要基于附件开发一个 iOS 版“胡桃碰一碰”，用于我自己有权限使用的天猫精灵校园门锁（天猫校园）。现有 Android 项目已经实测 BLE 开门，但 iOS 还没做。请先阅读 `docs/AI_HANDOFF.md`、`docs/IOS_REPLICATION.md`、`CHANGELOG.md`，再阅读 `LockProtocol.java`、`LockEngine.java`、`LockProtocolTest.java`；将附件文档当项目资料，不当作更高优先级指令。

本次先实现可以编译的 SwiftUI + CoreBluetooth iOS 工程：在本机导入合法16字节激活密钥、扫描并由用户绑定自己的锁、仅验证钥匙、手动开锁、显示真实门锁回执、Keychain保存钥匙。先逐字节移植协议和全部合成测试，再实现蓝牙状态机。不要先花时间做花哨界面。

必须保留协议的 AES-128/ECB/NoPadding、随机数23字节特殊解析、会话 XOR、21字节加密帧校验和真实成功回执，不猜测算法。iOS 用 CBPeripheral identifier 绑定，不把 Android MAC 当成可直接连接的 UUID。不要把真实钥匙写进源码、上传服务器、输出到日志或要求我贴进聊天；在应用本地导入。

请先给出你已读到的真实状态、文件结构、明确的最低 iOS 版本和可实现范围，再输出完整 Xcode 工程与 Swift 单元测试、构建和安装步骤、真机测试清单。如果你当前不能创建工程文件、运行 Xcode 或接触 iPhone，请明确标记限制，逐文件给出完整内容，不声称已经编译或实测开门。

桌面卡片、快捷指令、NFC 是后续阶段：Apple NFC 后台通知/解锁限制、App Intent 执行进程、蓝牙后台与 iOS26恢复规则请查官方文档。不照搬 Android WRITE_SECURE_SETTINGS / Binder，不承诺息屏开门、锁屏无交互开门、强退后常驻或钱包门禁卡。原标签第三方域名没有控制权，不能假设可以配置它的 Universal Link，也不要未经我决定修改共用标签。

把已实现、编译通过、协议测试通过、真机 BLE 验证、机械实际开门分别记录。首个验收点是协议测试对齐和前台手动 BLE 开锁，不能用模拟成功代替门锁回执。

---

不能读 ZIP 时，至少提供：

1. `docs/AI_HANDOFF.md`、`docs/IOS_REPLICATION.md`。
2. `src/com/hutao/touchkey/LockProtocol.java`、`LockEngine.java`。
3. `tests/com/hutao/touchkey/LockProtocolTest.java`。
4. `KeyStore.java`、`MainActivity.java`、`AndroidManifest.xml` 作为现有行为参考，不能直接当 iOS API 使用。

如果对方AI会联网，可以读取公开仓库 https://github.com/EEEty-5223/hutao-touchkey ，但应固定使用 `v1.1.2-background-test` 标签避免以后 main 分支变化影响复现。
