# 0.1.0 实现与验证记录

2026-09-12。用户批准设计后启动实现，并进一步确认首版只需 SMB，手机为 Android 16。本版交付可安装的开发预览；完整设计中的双向同步、其他协议、E2EE、性能和故障矩阵验收仍在后续阶段。

## 已实现

| 范围 | 当前行为 |
| --- | --- |
| 工程 | 7 个 Gradle 模块；单 Activity，Compose / Material 3，Navigation 3，ViewModel / Flow，Hilt；手机底部导航、宽屏侧栏 |
| 连接 | SMB 主机、端口、共享、根目录、用户名、域、加密要求；测试/保存/修改/移除；配置 revision 防止旧任务写入新目标 |
| 协议 | `StorageProvider` / `StorageSession`、可选 `UploadCapability` / `MutationCapability`、`ProviderRegistry`；不向 UI 暴露 SMBJ 对象 |
| 文件 | 流式列举目录，错误显示为未完整加载；当前目录文本筛选、排序、选择；新建、非覆盖重命名、删除文件或空目录 |
| 预览 | UTF-8 文本前 1 MiB；Coil 图片；系统 PdfRenderer 逐页 PDF；Media3 音视频范围读取；不支持的格式可下载 |
| 传输 | Room 持久队列；原子任务租约与心跳、并发上限2/每连接1；暂停/继续/取消；故障重试与提交结果核对 |
| 下载 | SAF 新建目标，私有 staging 文件；按已落盘且记录到数据库的偏移恢复；大小/元数据版本验证后复制到目的 URI |
| 上传 | 临时文件与不覆盖提交；稳定 operationId；提交回执保存内容摘要，结果未知时核对身份与完整内容；不把复制进度当作任务成功 |
| 备份 | MediaStore 相机目录和持久 SAF 目录授权；增量扫描、任务去重、成功基线；文件变化生成新副本，本机删除不传播 |
| 后台 | 自动备份用有界 WorkManager；用户明确启动的传输用 UIDT JobService；不把公网连通性作为局域网可用条件 |
| 凭据 | Android Keystore + AEAD 加密文件；不进入 Room、日志或 SavedState；系统云备份与设备迁移排除应用数据 |

## 使用方式

1. 安装 debug APK，打开“空间 → 添加 SMB 连接”。填写 NAS 主机、共享名、账号；主机字段不要填写 `smb://` URL。若 NAS 支持且需要保密传输，可勾选要求 SMB3 加密。
2. 测试并保存后打开连接，在目标目录上传文件、下载或预览。首次操作按需授予通知/文件访问权限。
3. 在目标目录创建备份规则，选择相机照片视频或系统目录选择器授权的文件夹。相机备份仅包含系统允许访问的媒体；选择部分照片时不会绕过授权。
4. “备份 → 立即备份”扫描并启动待处理任务；“传输”查看错误、暂停、继续或取消。移除连接/规则不删除 NAS 数据。

本版没有 NAS 自动发现，不需要 NAS 型号，也不扫描局域网。真实账号密码只在设备 App 内填写。

## 明确限制

- SMB 已支持持久化断点及校验过程续跑，解除自动备份的 256 MiB 限制；见[断点上传实现与验证](smb-resumable-upload.md)。自动运行仍受 Android 调度、网络和授权条件约束。
- 自动扫描默认约30分钟一次，由系统调度；不承诺实时，也不承诺进程被强制停止后继续。Wi-Fi/充电限制、电量较低、Android 配额和文件授权都会影响执行时机。
- 这是单向保留副本的备份；没有双向同步、远端删除传播、回收站、跨连接移动、递归删除或远端全盘索引。目录列表当前保留在内存中，超大目录分页索引与性能目标尚待实现。
- 内部上传回执采用双槽，最多 2 KiB/文件；取消任务可安全清理临时文件，成功回执保留七天后清理。未知/损坏记录仍保留并显示原因，终态 operationId 保留用于去重。
- SMB revision 是大小和时间等元数据，SAF sourceGeneration 也是提供方可见的元数据；它们不是不可变快照或密码学版本。文件内容发生变化但元数据不变时，前置检查可能无法识别。SMB 持有读取句柄并禁止其他 SMB 写入；未知提交恢复使用完整摘要核对。
- 所有 SMB 上传提交前核对完整源/远端 SHA-256；校验进度可跨后台时间片保存。服务端实际掉电持久性和绕过 SMB 修改磁盘仍待真实 NAS 故障验证。
- 下载向 SAF 目的地发布时若系统终止，目的文档可能暂存部分内容，队列不会标记成功；继续任务会从 staging 重放发布。取消不会擅自删除用户选择的目的文档。
- 图片/PDF 单文件预览上限128 MiB，预览缓存预算256 MiB，按访问时间淘汰，超过24小时的缓存于后续加载时清理；缓存是应用私有的明文。音视频是否可解码取决于设备编码器。复杂/恶意 PDF 的隔离渲染和完整预览矩阵尚未验收。
- Settings 当前展示隐私、主题和版本说明；主题跟随系统动态颜色。DataStore 设置基础已建立，但尚未开放全部偏好开关。
- WebDAV、S3、私有 API、E2EE 均未启用。私有 API 按用户已有方案接入，不自行定义或发布另一套加密格式。新协议复用操作能力接口；协议配置/认证表单目前只实现 SMB，接入 OAuth、签名密钥、多阶段认证时还需补充对应配置描述与凭据类型。
- 最低 Android15，compile/target37；本次实测 Android16。Android17 局域网运行时权限已声明并按需请求，但尚未做 API37 设备测试。SMB3 实际加密互操作、真实 NAS 和物理手机测试仍待完成。
- Release 变体能构建但未配置发布签名和收缩；当前 APK 用开发签名。没有广告、收费组件、分析遥测或远端账户注册要求。

## 验证记录

全部在本机执行。测试服务仅包含自动生成的样本文件，未接触用户 NAS。具体命令和依赖固定版本见[开发说明](development.md)和[SMB 模块说明](../protocol/smb/README.md)。

| 检查 | 结果 |
| --- | --- |
| Debug / release / AndroidTest APK | 构建通过；最终构建使用普通 SHA-256 依赖校验模式 |
| Android Lint | 0 errors，6 warnings：5项依赖更新建议、1项更积极的缓存空间分配建议；没有忽略错误的 baseline |
| SMB JVM 模块 | 45/45：23项契约/安全测试、22项真实签名 SMB2 测试，0跳过；包含3 GiB稀疏文件范围读取、提交应答丢失恢复、同长度内容篡改和取消重试 |
| 数据 / 安全 / 传输 JVM | 16/16：去重、状态转移、加密认证、独立任务取消、备份命名 |
| Android16 Room / Keystore | 12/12：真实 Keystore、并发租约、过期恢复、成功基线、规则目标变化与游标一致性 |
| Android16 MediaStore 扫描 | 2/2：新文件/pending/非相机目录排除、二次去重、201文件跨扫描批次、受限媒体授权状态 |
| Android16 Compose UI | 4/4：空状态入口、无效/忙碌表单保护、显式删除确认 |
| Android16 真实 SMB 引擎与预览 | 6/6：上传下载内容一致、源文件变化、同名不覆盖、备份基线、UTF-8文本与范围读取、Media3实际WAV准备 |
| Android16 真实 UIDT 系统服务 | 1/1：可见 Activity 用户动作 → JobScheduler → Manifest JobService → Hilt/Keystore/数据库 → SMB成功上传 |
| 安装/冷启动 | Android16 API36 x86_64模拟器成功，初次测得1809ms；非正式性能基准；另完成截图检查 |

上述专项测试共86项，均通过；不把工程原有模板测试计入此数。App Android 测试先运行4项 UI、6项引擎/预览与1项旧模板，再单独运行新增 UIDT 用例。没有将缺少 fixture 时的跳过结果当成通过。

本机可查看的证据：`build/emulator/final-build.log`、`build/emulator/final-app-tests.log`、`build/emulator/uidt-platform-test.log`，以及各模块 `build/reports` / `build/test-results`。目录属于生成产物，重新构建可能替换报告。[空间页截图](../build/emulator/spaces-api36.png)来自真实模拟器，不是设计稿。

测试结束检查：UIDT 历史记录显示应用调用 `jobFinished`，无活动或待启动的用户传输 job，Android crash buffer 为空。已关闭本轮隔离 SMB 服务并清理其随机临时共享；端口24451已释放。确认 AVD 名称后仅停止本轮 `FileAccess_API36` 模拟器，保留 SDK/AVD 供后续开发使用。

本次 debug APK：`app/build/outputs/apk/debug/app-debug.apk`，85,233,954 bytes（约81.3 MiB）。SHA-256：

```text
4c5b015a6d4d160aadf92c8f0761e0260f9b0ccf4504a06c5852bfeb512ab242
```

Release 文件为 `app/build/outputs/apk/release/app-release-unsigned.apk`，不作为本次直接安装包。真机 NAS、SMB3加密、不同媒体编码/OEM后台调度、API37设备和灾难恢复矩阵仍按上方限制待验证。

## 下一阶段

SMB 大文件断点上传和清理已在独立分支实现，详见[本阶段说明](smb-resumable-upload.md)。下一步在用户 Android16 手机与 NAS 上执行真实断网、掉电、授权与空间恢复矩阵，再按统一能力契约扩展其他协议和私有网盘。
