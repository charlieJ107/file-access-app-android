# 可复现签名轮换验证

`scripts/test-signing-rotation.py` 使用 JDK、Android SDK Build Tools 36.0.0 和已安装的 SDK Platform 创建最小 APK，不调用 Gradle、不启动设备，也不读取正式签名配置。每次随机生成临时 A/B/C/X 密钥、测试密码和独立包名；进程正常结束或失败时删除临时密钥。输出只保留已签名 APK、公开证书和 JSON 报告。

仅生成并验证 APK：

```powershell
python scripts/test-signing-rotation.py
```

在已经启动的隔离 API 35+ 模拟器上验证系统覆盖安装：

```powershell
python scripts/test-signing-rotation.py --serial emulator-5554
```

增加 `--test-release-signer` 会使用同一批临时密钥和单独的不可调试 APK 实际运行 `sign-release.py`：验证首次签名、同签名、A→B→C；拒绝缺失历史、回退、无关密钥、当前密钥不在链尾、不允许保留数据、允许回退的链及丢失已发布历史。首次签名及 A→B 使用原子 `RELEASE_SIGNING_BUNDLE` 输入，其他情况验证本地文件输入。失败情况必须不生成输出 APK。测试子进程移除已有 `RELEASE_*` 和 GitHub Token 后才注入临时测试配置，不读取正式 keystore。

增加 `--test-rotation-preparation` 运行 16 项原生签名维护检查：A→B 和已有 A→B 的 B→C 延续；nonce/commit SHA/新证书指纹/运行分支不匹配的拒绝；带未准备 NEXT 和无 NEXT 的 inspect；带完整 lineage 的 NEXT 晋升前、后 inspect。准备后的 inspect 还必须拒绝错误旧/新证书指纹、损坏 lineage、密钥不在链尾、缺失旧证书绑定，以及把较早祖先误当直接前任的情况。每次接受结果只允许公开元数据和 lineage；拒绝结果不生成制品。此模式设置的 GitHub 环境变量仅为本地测试输入，不调用 GitHub，也不修改 Secrets。

工具拒绝物理设备序列号，并进一步检查模拟器属性。它只安装本次生成的 `space.zhuoling.fileaccess.rotationfixture.t<随机值>` 包，结束时卸载；不会安装、卸载或清除 FileAccess。任何预先存在的同名包都会导致失败。

系统测试包括 A→B→C、A→C 跳过中间版本、同签名升级、B→C 截短的前向链、每次升级后的私有数据保留，以及高 versionCode 的旧 A、同祖先 A→X 和无关 X 签名拒绝。所有 APK 先通过 `apksigner verify --min-sdk-version 35` 的完整验证，并核对当前签名者的 SHA-256。轮换历史明确开启 `installed-data`、关闭 `rollback`；使用 v3 签名和 `--rotation-min-sdk-version 28`。

默认输出到 `build/verification/signing-rotation/<随机目录>/`。可用 `--output <新目录>` 指定位置，已有目录不会被覆盖。`report.json` 区分仅验证制品与通过设备安装测试，不能把 `fixtures-verified-device-not-run` 当成设备测试通过。中断或强制终止后可从报告的 package 字段定位本次测试包；不要据此删除其他包。

`instrumentation-arguments.json` 包含 `SignatureRotationTest` 使用的七个 APK 参数：`rotationBaseApk`、`rotationSameApk`、`rotationForwardApk`、`rotationForwardTruncatedApk`、`rotationRollbackApk`、`rotationForkApk`、`rotationUnrelatedApk`。将 APK 用 `adb -s <模拟器> push` 传入随机命名的 `/data/local/tmp/` 测试目录，再通过 `run-as space.zhuoling.fileaccess cp` 复制到测试 App 的私有测试目录；核对设备副本的 SHA-256，再把参数值改为设备绝对路径。运行 `space.zhuoling.fileaccess.update.SignatureRotationTest` 后清理这两个测试目录。测试读取系统解析的 SigningInfo，不要求安装基准 fixture APK，因而不会改变 FileAccess 的签名或数据。

命令依据：[Android apksigner](https://developer.android.com/tools/apksigner)。
