# 胡桃碰一碰 · 天猫精灵校园门锁

**面向本人获授权的天猫精灵校园门锁（天猫校园）BLE 开锁替代客户端。实测极速开锁。** 应用内手动、桌面“一键开门”卡片和 NFC 碰一碰均已成功开门。“极速”描述用户实机体验，没有发布毫秒级测速数据；当前只验证特定门锁与手机，不能代表所有校园门锁兼容。

当前交付：**1.2.1-nfc-sound / versionCode 12**，2026-10-24。名称仍保留实验版标识，不代表所有机型均支持锁屏功能。

- [下载 APK 和源码](https://github.com/EEEty-5223/hutao-touchkey/releases/tag/v1.2.1-nfc-sound)
- [更新日志](CHANGELOG.md)
- [可交给其他 AI 的项目记忆](docs/AI_HANDOFF.md)
- [iOS 复刻说明与 BLE 协议](docs/IOS_REPLICATION.md)
- [给豆包的开场提示词](docs/DOUBAO_PROMPT.md)
- [安装和构建说明](docs/INSTALL_BUILD.md)

这是一款独立客户端：使用用户自己导入的门锁密钥，通过蓝牙连接已登记的门锁；不修改天猫校园，也不在源码或 APK 中保存个人密钥。

## 实机结果

2026-10-23 新增锁屏实验功能，第一台 iQOO 13 已实测：屏幕亮起、手机保持未解锁时碰登记标签，门实际打开，没有跳转天猫校园；应用显示“锁屏开锁成功，门锁已回执确认”。独立诊断也取得了 `locked=true, interactive=true` 的匹配标签事件。完全息屏没有收到事件，暂不支持。第二台手机尚未更新或验证锁屏功能。

- 原 iQOO 13（Android 16）：用户确认手动开锁、桌面卡片开锁和 NFC 碰一碰均成功；碰标签后由本应用处理，没有再跳转天猫校园。
- 第二台 iQOO 13：已确认应用内开锁和桌面卡片开锁成功；该手机的 NFC 实体碰锁测试尚未确认。
- NFC 标签保留原网址与天猫校园 Android Application Record，并把本应用入口放在前面。应用内提供恢复原标签内容的功能。
- 已登记的锁地址和标签编号用于匹配目标；其他门锁不会因此自动开锁。

## 功能

- 1.2.1 NFC 识别语音：前台读标签和亮屏锁屏监听匹配已配置门锁后，立即播放内置语音，不等待蓝牙连接或开锁结果；使用媒体音量，播放期间不叠加。已完成构建、原签名校验与 iQOO 13 覆盖安装，语音实际播放仍待用户明确反馈。完全息屏 NFC 不支持。
- 保留 1.2.0 手机端小米手环通信入口：手环按钮通过手机中继开门；本次未更新手环安装包，也未重新验收手环开门。

- 手动开锁与仅验证密钥、不发送开锁指令。
- 桌面“一键开门”卡片：直接连接门锁，操作完成后返回桌面，不打开应用主页。
- NFC 碰一碰：屏幕亮起并解锁时识别已登记的标签，自动执行开锁。
- NFC 标签原内容恢复。
- 实验开关“锁屏碰一碰”：限定已验证的 iQOO 13 Android 16 固件，需电脑授予 `WRITE_SECURE_SETTINGS` 并允许通知，保持前台监听服务。仅在亮屏、未解锁时匹配已登记标签及门锁 URI，然后使用原 BLE 引擎；不解锁手机、不写系统安全设置。完全息屏暂不支持。该权限覆盖系统安全设置，超出普通 NFC 权限；功能开关只停止监听，不撤销系统权限，撤权需通过电脑或卸载应用。
- 1.1.1 保活实验：服务使用 `START_STICKY` 请求系统在回收后重建，任务移除不主动停止服务；仅在用户原来已开启监听且所需权限和配置完整时，于开机首次解锁、覆盖更新后尝试恢复，打开应用时也检查恢复。用户关闭开关或点击通知“关闭监听”后不自动重启。系统强行停止、厂商限制和权限关闭仍可能阻止恢复。覆盖更新后自动恢复已观察成功；真实重启恢复尚未测试，vivo 自启动列表未显示本应用，不能写成厂商自启动权限已开启。
- 1.1.2 后台卡片隐藏：用户选择隐藏最近任务卡片，保留应用图标、监听通知及停止入口。应用内可关闭“隐藏最近任务卡片”恢复显示。实机发现本系统单独上滑移除卡片会执行 `single-cleaner / FORCE STOP`，即使任务锁定也无效；隐藏卡片用于避免这种误清理，不绕过强行停止。用户清理其他后台后等待十秒，观察到进程号及监听服务保持不变；不承诺所有清理器或长期运行场景。
- 密钥通过 Android Keystore 与 AES/GCM 加密保存在本机；应用不申请联网权限。

## 源码结构

- `NfcAudio.java`、`res/raw/nfc_recognized.ogg`：门锁 NFC 识别语音。
- `WristActivity.java`、`WristService.java`、`WristSession.java`：手机端手环通信和会话管理。

- `src/com/hutao/touchkey/MainActivity.java`：主界面、密钥导入、NFC 标签读取/配置/恢复。
- `src/com/hutao/touchkey/UnlockWidget.java`、`UnlockService.java`：桌面开锁卡片与后台连接服务。
- `src/com/hutao/touchkey/LockEngine.java`、`LockProtocol.java`：BLE 通讯和协议处理。
- `src/com/hutao/touchkey/KeyStore.java`：本机加密密钥存储。
- `LockedNfc.java`、`LockScreenService.java`：锁屏实验入口与持续监听。内部 NFC 交易号从测试固件实际读取，代码按完整固件指纹限制运行，更新系统后需重新验证，不承诺其他型号或版本兼容。
- `tests/com/hutao/touchkey/LockProtocolTest.java`：协议检查代码。

## 构建

项目使用 Python 3、JDK 21、Android API 36 和 Android Build Tools 36，手机手环通信依赖 Xiaomi Wearable SDK 1.4。先执行 `python fetch_wearable_sdk.py` 获取官方 SDK 并校验内容摘要；SDK 不放入仓库。原开发环境将 Android 工具放在项目父目录的 `android-tools/sdk/` 下：平台库位于 `android-36/android.jar`，Build Tools 解压目录名为 `android-16`。

```powershell
python fetch_wearable_sdk.py
python build.py
python build.py --release
```

调试版生成于 `build/hutao-touchkey-test.apk`，正式版生成于 `build/hutao-touchkey.apk`。签名证书和签名配置留在源码目录之外；正式版更新必须继续使用原签名证书。首次在其他环境构建时，需要按自己的 JDK、SDK 和 Build Tools 安装位置调整 `build.py` 中的路径。

## 安全与使用说明

本项目只针对已获授权、并由使用者自行配置密钥的门锁。源码不包含任何真实门锁密钥、个人标签记录、签名私钥或手机日志；安装包通过 Releases 分发。请勿把自己的密钥提交到 GitHub。

普通碰一碰开锁需要打开 NFC 与蓝牙，并保持屏幕亮起、手机解锁。使用锁屏实验功能时，可在完成额外授权并开启监听后，亮屏且保持未解锁碰标签；完全息屏暂不支持。若使用者没有安装本应用或天猫校园，配置后的标签可能无法再打开原先的天猫校园应用商店下载页；标签内容可在应用内恢复。

本项目没有声明天猫校园官方关联，也没有把天猫校园应用移除或停用。
