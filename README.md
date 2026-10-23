# 胡桃碰一碰

**面向本人获授权的天猫校园 BLE 门锁的 Android 开锁替代入口。** 实机体验为快速开锁：应用内手动、桌面“一键开门”卡片和 NFC 碰一碰均已成功开门。这里的“快速”描述实际使用体验，没有发布毫秒级测速数据。

这是一款独立客户端：使用用户自己导入的门锁密钥，通过蓝牙连接已登记的门锁；不修改天猫校园，也不在源码或 APK 中保存个人密钥。

## 实机结果

- 原 iQOO 13（Android 16）：用户确认手动开锁、桌面卡片开锁和 NFC 碰一碰均成功；碰标签后由本应用处理，没有再跳转天猫校园。
- 第二台 iQOO 13：已确认应用内开锁和桌面卡片开锁成功；该手机的 NFC 实体碰锁测试尚未确认。
- NFC 标签保留原网址与天猫校园 Android Application Record，并把本应用入口放在前面。应用内提供恢复原标签内容的功能。
- 已登记的锁地址和标签编号用于匹配目标；其他门锁不会因此自动开锁。

## 功能

- 手动开锁与仅验证密钥、不发送开锁指令。
- 桌面“一键开门”卡片：直接连接门锁，操作完成后返回桌面，不打开应用主页。
- NFC 碰一碰：屏幕亮起并解锁时识别已登记的标签，自动执行开锁。
- NFC 标签原内容恢复。
- 密钥通过 Android Keystore 与 AES/GCM 加密保存在本机；应用不申请联网权限。

## 源码结构

- `src/com/hutao/touchkey/MainActivity.java`：主界面、密钥导入、NFC 标签读取/配置/恢复。
- `src/com/hutao/touchkey/UnlockWidget.java`、`UnlockService.java`：桌面开锁卡片与后台连接服务。
- `src/com/hutao/touchkey/LockEngine.java`、`LockProtocol.java`：BLE 通讯和协议处理。
- `src/com/hutao/touchkey/KeyStore.java`：本机加密密钥存储。
- `tests/com/hutao/touchkey/LockProtocolTest.java`：协议检查代码。

## 构建

项目使用 Python 3、JDK 21、Android API 36 和 Android Build Tools 36，不依赖第三方 Java 库。原开发环境将 Android 工具放在项目父目录的 `android-tools/sdk/` 下：平台库位于 `android-36/android.jar`，Build Tools 解压目录名为 `android-16`。

```powershell
python build.py
python build.py --release
```

调试版生成于 `build/hutao-touchkey-test.apk`，正式版生成于 `build/hutao-touchkey.apk`。签名证书和签名配置留在源码目录之外；正式版更新必须继续使用原签名证书。首次在其他环境构建时，需要按自己的 JDK、SDK 和 Build Tools 安装位置调整 `build.py` 中的路径。

## 安全与使用说明

本项目只针对已获授权、并由使用者自行配置密钥的门锁。仓库不包含任何门锁密钥、个人标签记录、签名私钥、手机日志或安装包。请勿把自己的密钥提交到 GitHub。

碰一碰开锁需要打开 NFC 与蓝牙，并保持屏幕亮起、手机解锁。若使用者没有安装本应用或天猫校园，配置后的标签可能无法再打开原先的天猫校园应用商店下载页；标签内容可在应用内恢复。

本项目没有声明天猫校园官方关联，也没有把天猫校园应用移除或停用。
