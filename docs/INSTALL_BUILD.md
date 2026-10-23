# 安装、构建与文件核对

当前推荐 APK：`hutao-touchkey-1.2.1-nfc-sound-vc12.apk`，versionCode 12。仍为实验版本，锁屏功能只限已验证固件。

SHA256：`1f4811190dd8963a5a0c0d8651ffc029d19f5ef385a6dbba438f832a12405f40`

历史可用 APK：`hutao-touchkey-1.0.apk`，versionCode 6，仅基础功能；SHA256：`1c2157bc183de21b568861dc5e6c5b3c32f87ae5f117bb126bf98350f6643e2d`。

原 APK 签名证书 SHA256：`13e5e579105835fdd007bfd2f2fb13e1e5a74ca85891a1dd6ea784fd2a18232e`。公证书摘要不是签名私钥。

## 安装与使用

1.2.1 在识别已配置门锁 NFC 时播放内置语音，跟随媒体音量；前台和亮屏锁屏两个入口均已接入。安装后已确认锁屏监听恢复，实际语音播放尚待明确反馈。

同包同签名可从 1.0 覆盖升级到 1.2.1；不要先卸载，卸载会清除本机钥匙与设置。1.0 版本号较低，不能当作无损降级包直接覆盖新版本；如需换回旧版，应先确认自己有合法配置备份，避免卸载后丢失。

新手机自行安装、允许蓝牙连接权限，导入自有门锁配置，先在门旁验证钥匙并手动开门。随后添加桌面“一键开门”卡片；需要 NFC 时先读取对应标签，再依应用提示配置并保留原标签恢复数据。包内没有钥匙，也不自动连接任意门锁。

仅在完整固件匹配、用户愿意授权时启用实验锁屏监听：

```text
adb shell pm grant ht.key android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant ht.key android.permission.POST_NOTIFICATIONS
```

第一项权限范围包括系统安全设置。应用当前仅用于 NFC 监听注册与撤销，不修改安全设置，不解除手机锁。通知权限也可在系统界面允许。完成标签配置、钥匙验证、打开自动开锁后，再开启“锁屏碰一碰”；保留持续通知。亮屏仍锁定时碰自己的标签可测试，息屏不支持。勿把其他型号上的授权成功当作功能兼容。

在应用或通知中关闭监听即可停止；电脑撤权命令：

```text
adb shell pm revoke ht.key android.permission.WRITE_SECURE_SETTINGS
```

后台设置：在系统的后台耗电管理中允许本应用，必要时加入标准电池优化白名单（原设备做过 `adb shell dumpsys deviceidle whitelist +ht.key`，撤销用 `-ht.key`）。这些设置不自动随 APK 转移。隐藏任务卡片用于避免误划强停，不隐藏桌面图标；设置中的强行停止仍会终止监听。真机重启恢复未验证。

## 从源码构建

需要 Windows、Python 3、JDK 21、Android API 36 / Build Tools 36，以及 Xiaomi Wearable SDK 1.4。先运行 `python fetch_wearable_sdk.py` 获取官方 SDK 并校验摘要，生成在 `android-tools/vendor/xiaomi-wearable-1.4.jar`。现有脚本会用 Oracle javapath 的 java.exe 查询 JAVA_HOME；在其他环境请修改 `JAVA`、`TOOLS`、`BT`、`ANDROID` 的路径。

预期目录：

```text
workspace/
  touchkey/                # 本源码根目录
  android-tools/
    sdk/android-36/android.jar
    sdk/android-16/        # 原开发环境目录名；实际为 Build Tools 36
      aapt2.exe, zipalign.exe, lib/d8.jar, lib/apksigner.jar
    signing/               # 本机私有，不发布
```

```powershell
python fetch_wearable_sdk.py
python build.py
python build.py --release
```

调试 APK：`build/hutao-touchkey-test.apk`；release APK：`build/hutao-touchkey.apk`。脚本 release 从清单 versionCode 11 加一生成 12，并关闭 debuggable；debug 保留清单中的 11。因此不要只看源码清单就误称发布 APK 为 versionCode 11。

若私有 signing 目录不存在，脚本会在源码外生成新签名配置。这份新签名无法覆盖本项目已安装的原签名 APK；需要保留原应用时，应使用不同 applicationId，且用户重新导入自己的合法钥匙。禁止把签名文件和密码提交仓库。

只运行协议测试（JDK 路径自行配置）：

```powershell
New-Item -ItemType Directory -Force test-build
javac -encoding UTF-8 -d test-build src/com/hutao/touchkey/LockProtocol.java tests/com/hutao/touchkey/LockProtocolTest.java
java -cp test-build com.hutao.touchkey.LockProtocolTest
```

预期 `9 protocol checks passed`。这是纯协议校验，不能代替 BLE 真机和机械开门测试。SDK、JDK、签名私钥不在源码包内。
