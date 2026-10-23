# 给豆包和 iOS 开发者的复刻规范

日期：2026-10-23。对应 Android 1.1.2-background-test，交接 HT-20261023-02。**这是实现规范，不是已完成的 iOS 软件；没有编译过的 Xcode 工程或 IPA。** 目标是复用已实测的天猫精灵校园门锁 BLE 协议，在 iPhone 上先完成前台手动开锁，再逐项评估快捷入口。

## 一、复用范围和系统边界

| Android 现有部分 | iOS 实现方式 / 边界 |
| --- | --- |
| AES、握手帧、回执判定 | 可移植成纯 Swift 模块，先用合成测试逐字节比对 |
| 按已知 MAC 连接 | CoreBluetooth 先发现并绑定对应 CBPeripheral；不能照搬 Android getRemoteDevice(mac) |
| 本地加密密钥 | 存入 Keychain；Android Keystore 密文不能跨系统导入 |
| 手动开锁 | SwiftUI + CoreBluetooth，先做这个最小可用版本 |
| 桌面不跳主页的开锁卡片 | WidgetKit + App Intents 需单独验证执行进程、蓝牙可用性与系统时限；可先做打开 App 后开锁的可靠入口，不冒称完全无界面 |
| NFC 标签自动触发 | Core NFC 前台会话，或兼容 URL 的系统后台标签入口；iOS 通常需要点通知，锁定时需解锁后交付数据 |
| Android 亮屏锁屏实验监听 | WRITE_SECURE_SETTINGS、Binder INfcUnlockHandler 不能移植到 iOS。不可承诺不解锁即 NFC 自动开门 |
| 持续前台服务与任务卡隐藏 | 无直接等价实现；CoreBluetooth 后台模式与状态恢复是条件机制 |
| 钱包 NFC 卡、息屏碰开 | 没有实现；普通 Wallet pass 不等于可向该 BLE 门锁认证的 NFC 门禁凭据 |

不要把“有蓝牙后台模式”解释为无限常驻或一定从强退恢复。Apple 的 TN3115 当前对用户强退等情境列出限制，且注明从 iOS/iPadOS 26 起部分恢复路径与 AccessorySetupKit 配件设置有关。接手时必须逐条核对目标系统、API 可用性及配件兼容性，不能仅加 background modes 就保证恢复。[Apple TN3115](https://developer.apple.com/documentation/technotes/tn3115-bluetooth-state-restoration-app-relaunch-rules)

Apple 官方说明后台 NFC 在用户点通知后才把数据交给 App；手机锁定时先要求解锁。这个入口不等价于本项目 Android 的锁屏监听。[后台标签读取](https://developer.apple.com/documentation/corenfc/adding-support-for-background-tag-reading)

## 二、必须先读的真实源码

以 `src/com/hutao/touchkey/LockProtocol.java` 为字节协议唯一实现依据，以 `LockEngine.java` 为当前握手顺序依据，以 `tests/com/hutao/touchkey/LockProtocolTest.java` 为合成测试依据。以下说明帮助移植，发现冲突时先核对源码，不猜测新格式。

### GATT

- 可写特征 UUID：`00002760-08c2-11e1-9073-0e8ac72e0001`
- 通知特征 UUID：`00002760-08c2-11e1-9073-0e8ac72e0002`
- Android 使用的 CCCD：`00002902-0000-1000-8000-00805f9b34fb`
- **尚未建立通用服务 UUID / 广播识别规则**。原代码遍历发现的服务以寻找上述两个特征，不得把特征 UUID 当作已证实的广播服务过滤条件。
- iOS 调用 `setNotifyValue(true, for:)` 并等成功回调，不照搬 Android 手写 CCCD 的流程。
- 原引擎优先 Write Without Response；否则 Write With Response。iOS 据特征属性选择，等待可写/回调并保持串行发送。保守按 20 字节分片，21 字节加密帧发送 20+1，原实现片间 70ms。优化前先保证对齐真实门锁。

### 流程和协议（全部为十六进制字节，无文本编码）

1. 连接并发现服务/特征，开启通知。Android 连接超时 12s、发现 8s、通知配置 3s，可作为初始参数。
2. 写入明文随机数请求 `55 AA FF 0D 00 00 0B`。
3. 从流中寻找前缀 `55 AA FF 0D 00 11` 的 **23 字节**帧。取零起始偏移 `[7,23)` 的 16 字节随机数。偏移 6 是协议字段；不能把最后一个随机数字节当尾校验！此帧不执行普通外层求和校验。当前等待 2500ms。
4. 激活密钥是用户自己导入的 **16 原始字节 / 32 十六进制字符**。`session[i] = nonce[i] XOR activationKey[i]`。每次连接取新随机数，不硬编码会话密钥。
5. 写入明文 LINK 请求 `55 AA FF 13 00 00 11`。等待前缀 `55 AA 00 10` 的 **21 字节**加密应答（2500ms）。
6. 加密应答帧结构：4 字节头 `55AA0010` + 16 字节密文 + 1 字节校验。校验为前 20 字节按无符号值求和 `& 0xFF`。取 `[4,20)`，用会话密钥执行 **AES-128 / ECB / NoPadding** 解密。
7. LINK 解密明文必须以前缀 `FF130001` 开始才算钥匙验证通过。“只验证”到此断开，不发开锁。
8. 开锁明文是 16 字节：`FF0B0000090000000000000000000000`。前四字节 `FF0B0000`，第五字节 `09` 来自 `sum(55AAFF0B0000) & 0xFF`，余下全零。用会话密钥 AES-ECB 无填充加密，套第6步同样的 21 字节封装和外层校验。
9. 开锁应答仍为 21 字节加密帧，校验、解密后前缀必须为 `FF0B0001000A` 才报门锁确认成功（3500ms）。不凭“写入成功”或“连接成功”显示已开门。超时应显示“指令已尝试发送，未收到确认，请观察门锁”，不要盲目无限重发。
10. 所有出口取消超时并断开/释放连接。每次操作独立状态，序列化通知处理、写入与状态推进，避免残留应答混进下一阶段。

通知是任意碎片，不一定一回调一整帧。原实现接收缓冲上限 4096 字节，在缓冲中扫描合法前缀和完整帧；帧不够时继续等待，加密帧校验失败不能直接认成功。Android 新旧通知回调可能重复，其去重逻辑不应在 iOS 上错误丢弃合法相同分片。每阶段开始清理接收缓冲；Swift 应用串行 actor/队列实现，不用 Android CountDownLatch 阻塞主线程。

### AES 实现注意

协议使用 ECB 是现有门锁线协议要求，不能擅自换成 AES-GCM、CBC、PKCS7，否则无法互通。Swift 可用 CommonCrypto 的 CCCrypt，AES128、ECB option、无 PKCS7、长度16字节、无 IV；检查返回码和输出长度。不要把激活密钥字符串的 UTF-8 当作原始16字节。CryptoKit 的 AES-GCM 不能直接代替此线协议。Keychain 本地存储与线协议是两层不同需求。

### 可公开合成测试（不是真实门锁密钥）

| 测试 | 输入与预期 |
| --- | --- |
| AES 标准向量 | key=`000102030405060708090A0B0C0D0E0F`，plain=`00112233445566778899AABBCCDDEEFF`，cipher=`69C4E0D86A7B0430D8CDB78070B4C55A` |
| 随机数帧 | `55AAFF0D00110000112233445566778899AABBCCDDEEFF` 长度23；nonce=`00112233445566778899AABBCCDDEEFF` |
| 截断帧 | 将以上帧截到22字节，必须拒绝 |
| 会话 XOR | 以上 nonce XOR 上述 key → `00102030405060708090A0B0C0D0E0F0` |
| 开锁往返 | 用上述合成 key 封装再解密，明文=`FF0B0000090000000000000000000000`，包长21 |
| 校验与噪声 | 合法帧前加噪声仍可找到；尾校验翻一位必须拒绝 |

移植原 Java 的全部9项检查，再增加通知任意切分/粘包、迟到通知、取消、重复触发、错误钥匙及超时的 Swift 测试。9 项 Java 通过不代表任何 iOS 代码已通过。

## 三、iOS 最小工程建议

先输出可以用 Xcode 打开的工程，而不只有单页示例。建议模块：

```text
HutaoTouchkey/
  App/HutaoTouchkeyApp.swift
  Features/LockView.swift
  Domain/LockConfiguration.swift
  Protocol/LockProtocol.swift
  Bluetooth/LockCentral.swift
  Security/LockKeychain.swift
  NFC/TagReader.swift                 # 第二阶段
  Intents/UnlockDoorIntent.swift      # 第三阶段
  Widget/UnlockWidget.swift           # 第三阶段单独扩展 target
HutaoTouchkeyTests/LockProtocolTests.swift
```

1. 选择明确的最低 iOS 版本，在工程中写清支持矩阵；为 Bluetooth 配置 `NSBluetoothAlwaysUsageDescription`，NFC 需要其用途说明与对应 capability。后台蓝牙、Associated Domains、App Groups、Keychain Sharing 仅按真实用途增加，不为了“保活”堆权限。
2. 导入界面接收16字节激活密钥及用户的锁标识；Keychain 保存并默认不云同步，不在日志、通知、剪贴板回显密钥。默认可选 `WhenUnlockedThisDeviceOnly`；若未来确有后台需求，再说明 `AfterFirstUnlockThisDeviceOnly` 的安全取舍并由用户决定。不是保证锁屏执行的开关。
3. 使用 CBCentralManager 扫描附近设备。iOS 外设 identifier 是系统提供的 UUID，与原 MAC 不可简单互算。先收集已授权设备的广播/服务，在用户靠近门锁且明确选择后，发现目标特征并仅验证钥匙；成功后存 identifier。不能只凭名称/RSSI 强弱自动绑定或自动开门。不同 iPhone 不能直接复用 identifier。
4. 第一阶段只做前台“验证钥匙”和“手动开锁”，带明确状态、取消和失败提示；先真机验证。物理回执与用户观察都记录，不做模拟成功 UI。
5. 以后才加 NFC 和桌面卡片。App Intent 的执行进程与 Widget 扩展不同，需核对蓝牙操作所在进程、Keychain 可访问性、后台时间预算和停止状态。不要假扮音频/定位等无关模式来常驻。锁屏小组件操作可能要求用户认证，不承诺绕过。[Apple 交互组件说明](https://developer.apple.com/documentation/widgetkit/adding-interactivity-to-widgets-and-live-activities)

CoreBluetooth 外设识别与 GATT 接口依据：[CBPeripheral](https://developer.apple.com/documentation/corebluetooth/cbperipheral)。

## 四、NFC 标签兼容尤其容易误做

现有标签有原门锁 HTTPS URL 和 Android AAR。Android 应用匹配 `https://biz.confong.cn/nfc/lock`，查询参数包含门锁地址；真实参数、UID、原始备份均属用户配置，不在本公开包内。Android 可写 AAR 优先本包，**AAR 不能让 iOS 直接启动本 App**。

iOS 后台 Universal Link 路由需要开发者控制域名，部署 apple-app-site-association，App 的 Associated Domains 对应匹配。不能因为能读第三方域名 URL 就声称拥有其 Universal Link。项目没有该原域名控制权。[Apple Associated Domains](https://developer.apple.com/documentation/xcode/supporting-associated-domains)

可先做 Core NFC 前台扫描识别标签内容，再通过已绑定外设开门；不要未经用户决定重写共用门锁标签。若未来使用自有 HTTPS 域名或个人快捷指令，需要单独测试 URL记录顺序、Android兼容、通知点击/解锁流程，以及恢复原标签能力。不要把私人密钥放在 URL 或 NFC 标签里。

## 五、交付验收与问题处理

- 阶段1：Swift 纯协议全部对齐、Xcode真编译、真机发现正确外设、只验证成功，再由用户授权并观察实际开门。
- 阶段2：前台 NFC 与取消、错误标签、错误钥匙、离锁过远等失败场景均有结果，不误报开门。
- 阶段3：桌面/快捷指令逐一说明是否跳 App、是否需认证、前台/后台/锁屏/强退/重启情境，不以 API 文档替代实测。
- 没有 Mac、Xcode、iPhone、合法钥匙或真实门锁时，交付源码和测试计划并标记未验证，不输出“实测成功”。Android APK不能在iPhone安装。
- 本包不提供真实钥匙。不要请求用户把钥匙公开发给AI；导入和绑定在用户本地完成。不复刻钥匙提取、账号绕过或获取他人门锁配置。

所有平台规则查询日期 2026-10-23；新开发时重新查上述 Apple 官方资料。豆包若只能聊天写代码，无法替代开发者本地编译、签名与真机验收。
