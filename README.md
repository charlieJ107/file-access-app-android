# FileAccess

面向个人 NAS 和自建网盘的 Android 原生文件客户端：远端浏览与管理、媒体预览、本机照片视频备份，以及可扩展的同步与端到端加密。

当前阶段：**0.1.0 开发预览，已实现 SMB 纵向流程**。按最新确认，先只支持 SMB，主要验证设备为 Android 16；WebDAV、S3、私有 API 和端到端加密留待后续阶段。此版本不是完整 v1 验收版本。

从 [设计文档入口](docs/design/README.md) 开始阅读。文档包含产品范围、功能清单、Android 架构、协议契约、传输与同步、安全与加密、UI/UX、实施计划和验收标准。

技术方向：Kotlin、Jetpack Compose、Material 3、自适应布局、Navigation 3、Coroutines / Flow、ViewModel、Room、DataStore、Hilt；后台按任务性质组合 WorkManager 与用户发起的数据传输任务。

## 仓库与许可

GitHub 仓库：[charlieJ107/file-access-app-android](https://github.com/charlieJ107/file-access-app-android)，可见性为 **Private**。

本项目自行编写的代码采用 [Mozilla Public License 2.0](LICENSE)。使用开源许可证不会更改 GitHub 仓库的私有可见性或访问设置。第三方组件保留各自许可，见 [NOTICE.md](NOTICE.md)。MPL 官方文本与说明见 [Mozilla](https://www.mozilla.org/en-US/MPL/2.0/)。

开发协作见 [CONTRIBUTING.md](CONTRIBUTING.md)，安全报告见 [SECURITY.md](SECURITY.md)。

[Android CI](https://github.com/charlieJ107/file-access-app-android/actions/workflows/android-ci.yml) 在 `master` 推送和 PR 时执行 Debug 构建、JVM 单元测试及 Lint，测试报告保留 7 天。真实 SMB 网络测试和 Android 设备测试需要单独准备隔离环境，不计入默认 CI 的通过范围。

## 运行

在 Android Studio 打开此目录，配置 Android SDK 37，然后选择 `app` 运行；最低 Android 15。命令行可执行：

```powershell
.\scripts\build.ps1 :app:assembleDebug
```

安装包：`app/build/outputs/apk/debug/app-debug.apk`。具体环境、依赖版本与测试命令见[开发说明](docs/development.md)。

## 当前功能

- 保存、测试和编辑 SMB 连接，凭据由 Android Keystore 加密保存。
- 浏览目录、当前目录筛选、批量选择、新建目录、重命名、删除文件或空目录。
- 列表／自适应网格切换，图片缩略图、视频封面与时长；独立缓存和网络读取预算。详见[媒体实现与验证](docs/media-thumbnails.md)。
- 文本、图片、PDF 预览；Media3 从 SMB 按范围读取音视频。
- 系统文件选择器上传/下载；数据库保存任务，支持暂停、继续、取消、进度与失败原因。
- 相机照片/视频及用户授权目录的增量备份；按 Wi-Fi、计费网络、充电条件执行；本机删除不删除 NAS 副本。
- SMB 签名默认开启；可要求 SMB3 加密；不支持 SMB1 和匿名回退。

当前上传中断后可能从头重传；自动备份的单文件上限为 256 MiB，更大或大小未知的文件需在应用内点击“立即备份”。[实现与验证记录](docs/implementation-status.md) 列出了准确边界、测试证据和下一步。
