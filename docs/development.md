# 开发与构建

工程采用 7 个 Gradle 模块：`app` 负责 Compose 页面和依赖装配；`core:model`、`core:storage-api` 为纯 JVM 契约；`core:security`、`core:data`、`core:transfer` 为 Android 基础设施；`protocol:smb` 封装 SMBJ。第一阶段只接入 SMB，其他协议通过现有契约扩展。

## 本机环境

- Gradle Wrapper 9.6.0、AGP 9.4.0，应用 `compileSdk/targetSdk = 37`、`minSdk = 35`；主要验证目标是用户的 Android 16（API 36）。
- `scripts/build.ps1` 优先使用 `JAVA_HOME`，再发现 Android Studio 的 JBR。环境变量只对脚本当前进程生效，结束后恢复。
- 当前验证环境的 JBR 位于 `C:\Users\Charlie\AppData\Local\Programs\Android Studio\jbr`，版本 25.0.3；没有将这个机器路径写入 Gradle 配置。应用和 JVM 模块的字节码目标均为 Java 17。
- 在 Android Studio SDK Manager 中安装 Android SDK Platform 37 和 Build Tools 36.0.0。SDK 路径写入本机 `local.properties`（不提交），或者设置 `ANDROID_HOME`。

```powershell
# 自动发现 JDK，默认构建 debug APK
.\scripts\build.ps1

# JVM 与 Android library 单元测试
.\scripts\build.ps1 :core:model:test :core:storage-api:test :core:security:testDebugUnitTest :core:data:testDebugUnitTest :core:transfer:testDebugUnitTest :protocol:smb:testDebugUnitTest --console=plain

# 本机构建 debug/release；无签名环境变量时 release 仅生成 unsigned APK
.\scripts\build.ps1 :app:assembleDebug :app:assembleRelease --console=plain

# 连上 Android 16 测试设备后，执行 UI、Keystore、数据库与媒体扫描测试
.\scripts\build.ps1 :app:connectedDebugAndroidTest :core:data:connectedDebugAndroidTest :core:transfer:connectedDebugAndroidTest --console=plain

# 静态检查；不使用忽略错误的 lint baseline
.\scripts\build.ps1 :app:lintDebug --console=plain
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，使用本地开发证书。版本统一读取 `version.properties`。未设置签名环境变量时本机可构建 unsigned Release APK；现有 `RELEASE_STORE_FILE` 等本地接口仅用于独立开发证书，不能下载正式密钥使用。正式安装包由 GitHub Actions 无 Secrets 构建 unsigned APK，再独立读取原子 `RELEASE_SIGNING_BUNDLE` 签名。配置与 CLI 命令见[分支与发布规范](branching-and-releases.md)和[签名密钥管理](signing-and-rotation.md)。

真实 SMB 集成测试采用独立临时共享，配置方式见 [SMB 模块说明](../protocol/smb/README.md)。没有提供测试端口时，相关集成用例会明确跳过；普通单元测试和 UI 测试仍会运行。Android 端 `TransferIntegrationTest` 使用 `smbTestPort`、`smbTestShare`、`smbTestUser`、`smbTestPassword` instrumentation 参数，模拟器访问宿主机时使用 `10.0.2.2`。参数只应指向可清理的测试共享。

## 已锁定工具与依赖

直接依赖集中在 `gradle/libs.versions.toml`。Android 模块保留 AGP 内置 Kotlin，不应用 `org.jetbrains.kotlin.android`。AGP 9.4.0 的实际 Maven 元数据最低依赖 Kotlin 2.2.10；JVM、Compose compiler、serialization 插件统一固定为 Kotlin 2.4.20，使编译器能够读取 Coil 3.6.2 的 Kotlin 2.4 元数据。

| 组件 | 锁定版本 |
| --- | --- |
| Kotlin / Compose compiler | 2.4.20 |
| Compose BOM | 2026.09.00 |
| Navigation 3 | 1.1.7 |
| Material 3 Adaptive | 1.3.0 |
| Hilt / AndroidX Hilt | 2.60.1 / 1.4.0 |
| KSP | 2.3.12 |
| Room 3 / SQLite | 3.0.3 / 2.7.1 |
| WorkManager / DataStore | 2.11.2 / 1.2.1 |
| Coil / Media3 | 3.6.2 / 1.11.1 |
| SMBJ | 0.15.0 |

Room schema 由 Room Gradle 插件导出到 `core/data/schemas`，应随实体更改一同提交。更新依赖时先检查官方发布说明与实际 Maven 元数据，再运行构建和相关测试。Compose BOM 不管理 Kotlin、Hilt、KSP、Navigation 或 Room 版本。

依赖校验清单需要在更新依赖后通过 Gradle 的 `--write-verification-metadata sha256` 生成并审阅，随后用普通命令重新构建验证。生成哈希仅固定本次获取的制品，不等于独立的供应链审计；不要为了通过构建关闭校验。

## Android 16 模拟器

本机 Android Emulator 支持 WHPX 加速。可以在 Android Studio Device Manager 创建 API 36 设备，或者使用已安装的新 Android CLI：

```powershell
android --no-metrics sdk install system-images/android-36/google_apis/x86_64
```

新 CLI 的 SDK 包标识使用 `/`；旧 `avdmanager` 的包标识仍使用 `;`。协议测试只连接专门的隔离 SMB 测试服务，不自动扫描局域网或修改真实 NAS 文件。

## 复现 Android SMB 集成测试

在 Linux/WSL 的专用 Python venv 安装 `impacket==0.13.1`，运行 `protocol/smb/src/test/fixtures/smb_server.py --port 24451 --password test-only-password`，保持标准输入打开。不要传 `--root`，脚本会创建并在退出时删除自己的随机临时目录。这里的密码仅用于临时测试服务；WSL 需要支持 Windows 本地回环端口转发。

随后为连接好的 Android16 模拟器运行：

```powershell
.\scripts\build.ps1 :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.smbTestPort=24451 `
  -Pandroid.testInstrumentationRunnerArguments.smbTestShare=test `
  -Pandroid.testInstrumentationRunnerArguments.smbTestUser=fileaccess-test `
  -Pandroid.testInstrumentationRunnerArguments.smbTestPassword=test-only-password
```

测试只连接模拟器的 `10.0.2.2`，创建自己的 MediaStore 文档和随机远端目录，验证传输、冲突、备份基线、预览及实际 UIDT 系统服务。UIDT 测试还会在模拟器 App 的 Keystore/数据库创建临时连接，结束时删除连接与凭据；终态测试任务记录可能保留在模拟器的传输页。结束测试服务的标准输入后，确认监听端口已释放。

## 验证更新器

```powershell
.\scripts\build.ps1 :app:testDebugUnitTest :app:lintDebug :app:lintRelease
python -m unittest discover -s scripts -p 'test_*.py'
```

Android `UpdateValidationTest` 验证 FileProvider 范围、摘要、版本和同签名新 APK。完整测试需在模拟器安装旧版本，并准备包名/签名相同且 SemVer 和 versionCode 更高的测试 APK。先 `adb push <fixture.apk> /data/local/tmp/fileaccess-update-fixture.apk`，再 `adb shell run-as space.zhuoling.fileaccess cp /data/local/tmp/fileaccess-update-fixture.apk files/update-fixture.apk`（debug App 的私有 files 目录需已存在）。运行 instrumentation 时传 `-e updateFixture /data/user/0/space.zhuoling.fileaccess/files/update-fixture.apk`，或通过 Gradle 的 `-Pandroid.testInstrumentationRunnerArguments.updateFixture=...`。缺少 fixture 时只跳过新版本接受测试，不能把跳过记为成功；结束后清理这两个测试文件。不要用 `connectedDebugAndroidTest` 自动安装的新版本覆盖测试所需的旧版本，需确保已安装版本低于 fixture。

原生密钥轮换测试使用独立随机测试包与一次性证书，详见[签名轮换测试说明](../scripts/signing-rotation/README.md)。设备测试必须显式指定模拟器序列号；不要使用正式 keystore。`SignatureRotationTest` 检查真实 APK 的同签名、向前轮换、截短历史、旧密钥回退、历史分叉和无关签名。

## 技术依据

- [AGP 9.4 兼容表](https://developer.android.com/build/releases/agp-9-4-0-release-notes)
- [AGP 内置 Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Kotlin Gradle 兼容配置](https://kotlinlang.org/docs/gradle-configure-project.html)
- [Compose BOM](https://developer.android.com/develop/ui/compose/bom)
- [Room 3 与 schema 导出](https://developer.android.com/jetpack/androidx/releases/room3)
- [KSP 官方发布记录](https://github.com/google/ksp/releases)
- [SMBJ 发布记录](https://github.com/hierynomus/smbj/releases)
