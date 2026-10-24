# 安装、构建与文件核对

> 当前实验版 1.3.0-multikey / vc13 已使用原签名构建并覆盖安装。1.3.0 支持添加多把独立钥匙；NFC 按门锁 MAC 和已登记 UID 匹配；桌面/手环按默认钥匙开门。每把锁需使用自己的合法密钥并分别验收，不能仅凭 NFC 标签获得开锁密钥。覆盖更新保留本机数据，勿卸载。


当前推荐 APK：`hutao-touchkey-1.3.0-multikey-vc13.apk`，versionCode 13。仍为实验版本，锁屏功能只限已验证固件。

SHA256：`cf771e42965c8b47b08a0de93f7000c6c4826138a8d95368f3630fc14b1957a3`

历史可用 APK：`hutao-touchkey-1.0.apk`，versionCode 6，仅基础功能；SHA256：`1c2157bc183de21b568861dc5e6c5b3c32f87ae5f117bb126bf98350f6643e2d`。

原 APK 签名证书 SHA256：`13e5e579105835fdd007bfd2f2fb13e1e5a74ca85891a1dd6ea784fd2a18232e`。公证书摘要不是签名私钥。

## 安装与使用

### 多门锁

1. 原签名覆盖安装即可迁移旧钥匙；不要卸载。通过“添加门锁钥匙”导入每把门锁自己的 `MAC 密钥`，添加第二把不会覆盖第一把。同 MAC 再次导入会重置该钥匙的验证与自动开锁状态，保留原标签备份。
2. 每把新钥匙分别在用户自己的门锁旁有意手动开门验证，再读取对应标签。配置标签前会有确认，只有用户主动配置才写入；已含本应用入口的匹配标签重新读取即可恢复 configured 标记。
3. “选择门锁 / 重命名”选择桌面卡片与手环的默认门锁；碰 NFC 则按门锁 MAC 和已登记 UID 找对应钥匙，不改变默认。界面的当前门锁可能是最近 NFC 识别的门锁，其手动按钮使用该目标。
4. 每把钥匙分别控制自动开锁，亮屏锁屏监听只处理已配置、验证通过、开启自动开锁且登记 UID 的门锁。开启监听仍需当前手机固件匹配、电脑授权、蓝牙及通知权限。
5. 卸载后旧钥匙、原始标签备份和授权不会自动回来。若门上标签已有 `ht.key` 而原始备份丢失，不要把当前标签内容当作原始备份；应用提示无法恢复原内容。读取只恢复配置标记，不虚构备份、不自动改写标签。

本版已核对原钥匙记录迁移、添加/选择入口、前台 NFC 读取及亮屏锁屏监听运行；两把实体门锁开门、音效和手环新版回归尚待验收。

1.2.1 在识别已配置门锁 NFC 时播放内置语音，跟随媒体音量；前台和亮屏锁屏两个入口均已接入。安装后已确认锁屏监听恢复，实际语音播放尚待明确反馈。

同包同签名可从 1.0 覆盖升级到 1.3.0；不要先卸载，卸载会清除本机钥匙与设置。1.0 版本号较低，不能当作无损降级包直接覆盖新版本；如需换回旧版，应先确认自己有合法配置备份，避免卸载后丢失。

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

调试 APK：`build/hutao-touchkey-test.apk`；release APK：`build/hutao-touchkey.apk`。当前 1.3.0 清单为 versionCode 13，release 与 debug 都使用 13；release 仅关闭 debuggable，不再额外递增版本号。

若私有 signing 目录不存在，脚本会在源码外生成新签名配置。这份新签名无法覆盖本项目已安装的原签名 APK；需要保留原应用时，应使用不同 applicationId，且用户重新导入自己的合法钥匙。禁止把签名文件和密码提交仓库。

只运行协议测试（JDK 路径自行配置）：

```powershell
New-Item -ItemType Directory -Force test-build
javac -encoding UTF-8 -d test-build src/com/hutao/touchkey/LockProtocol.java tests/com/hutao/touchkey/LockProtocolTest.java
java -cp test-build com.hutao.touchkey.LockProtocolTest
```

预期 `9 protocol checks passed`。这是纯协议校验，不能代替 BLE 真机和机械开门测试。SDK、JDK、签名私钥不在源码包内。
