# SMB 大文件断点上传

2026-09-13，基于 `c0de6e4`，分支 `feature/smb-resumable-upload`。只实现 SMB 可靠性；其他协议和私有网盘的接入顺序不变。

## 恢复与提交

上传继续使用原有稳定 operationId、同目录隐藏 `.part` 文件和 `replaceIfExists=false` 的句柄重命名。不会通过删除目标实现覆盖，也不会在重试时选择一个新文件名。

每写入约 4 MiB：先 FLUSH 临时文件，再写入并 FLUSH 新的回执槽，最后上报已确认偏移。回执保存文件身份、64 位偏移、源长度、源版本摘要及前缀 SHA-256。两个 1 KiB 槽交替保存带序号和校验和的记录；写入中断时选择最新的完整记录，兼容原有 v1 单槽回执。Room 中的偏移用于任务展示，恢复以远端回执为准。

恢复时独占回执和临时文件，检查源版本、远端身份和长度，并核对完整的已确认源/远端前缀。只截去经过身份核对的未确认尾部，随后按偏移继续写入。源或前缀不匹配时停止并保留恢复数据。所有上传在提交前核对完整源/远端 SHA-256 和长度；成功后才推进备份基线。

校验本身也可以断点继续：`CHECKING/CHECKED` 保存恢复前缀的校验进度，`VERIFYING` 保存提交前的完整校验进度，`RECONCILING` 保存已存在目标的结果核对进度。每 4 MiB 保存两个 SHA-256 的编码状态和校验偏移，绑定源版本与远端 revision。源/远端元数据改变时重新校验；没有源版本的来源始终重新校验。新一轮已提交结果核对从头验证，不仅依赖元数据。使用 SMBJ 已依赖的 Bouncy Castle 1.85.2，显式声明依赖以使用可保存的 SHA-256 状态，不实现自定义哈希算法。

自动备份保持每次约 7 分钟的执行预算，SMB 已解除 256 MiB 和未知长度的调度限制。后台时间片耗尽、断网、进程退出后的下一次执行沿用原任务；备份网络失败不再在第五次尝试后永久失败。非断点协议仍保留大文件自动运行限制。

## 清理

- 暂停、断网、进程退出和普通失败保留恢复数据，连接关闭不会隐式删除上传。
- 取消后排入清理；不需要重新读取本机源文件，因此源授权撤回后仍可清理。
- 已成功的回执保留七天，之后只清理回执，绝不删除已发布文件。
- 清理使用独占回执、no-follow 和文件身份检查；未知/损坏记录或身份被替换时保留数据并报告原因。远端已删除的回执/临时文件支持幂等重试。
- 在非计费 Wi-Fi 上进行有界清理，失败在终态任务显示原因，约 30 分钟后再试。连接移除或配置 revision 改变时不使用新配置访问旧目的地；可能需要用户在旧 NAS 上处理保留的数据。
- Room 终态任务作为 operationId 去重记录保留。终态的 `retryAt` 复用于清理重试时间，`Long.MAX_VALUE` 表示清理已完成，不改变数据库 schema。未来增加历史清理功能时，必须保留操作墓碑或同步设计回执保留期，不能直接删除这些去重记录。

## 复现验证

```powershell
$env:FILEACCESS_SMB_TEST_PYTHON = '/home/charlie/.cache/fileaccess-smb-test-venv/bin/python'
$env:FILEACCESS_SMB_TEST_WSL_DISTRO = 'Ubuntu'
$env:FILEACCESS_SMB_LARGE_TEST = '1'
.\scripts\build.ps1 :protocol:smb:testDebugUnitTest :core:data:testDebugUnitTest :core:transfer:testDebugUnitTest --console=plain
```

大文件用例真实上传 3 GiB + 73 字节的流式生成样本，在 512 MiB、1536 MiB、2560 MiB 中断并建立新连接，比较完整 SHA-256，再重试同一 operationId 并检查只有一个可见文件。不会使用稀疏上传冒充已传输的大视频内容。常规协议测试另外通过独立 JVM 的 `Runtime.halt` 验证进程硬退出，并覆盖双槽损坏、未确认尾部、源和远端前缀变化、空间不足、权限拒绝、结果应答丢失及清理时身份替换。

校验时间片测试通过持久化编码后的回执恢复，验证下一次远端读取确实从校验偏移开始。Android 16 测试覆盖 Room 清理重试/七天保留/去重、引擎重建后的恢复与备份基线，以及源文档删除后的取消清理。

本机最终验证结果（测试共享与 Android 16 API36 模拟器，不连接真实 NAS）：

| 检查 | 结果 |
| --- | --- |
| SMB 常规测试 | 60 项通过：28 项纯逻辑/安全测试、32 项真实签名 SMB2 测试；大样本用例在此轮按开关跳过，单独记录如下 |
| 大文件专项 | `multiGiBVideoSurvivesThreeFreshConnections` 通过，真实传输 3 GiB + 73 字节，三次中断、完整摘要一致、同操作重试无副本；约 850 秒，非性能基准 |
| 数据与传输 JVM | 10/10 通过 |
| Android 16 数据库/安全 | 14/14 通过，含终态清理重试、七天保留及去重记录 |
| Android 16 引擎/系统服务 | 10/10 通过，含 257 MiB + 11 字节自动备份、引擎重建恢复、源文档删除后清理，以及原有 UIDT/下载/预览流程 |
| Debug / AndroidTest APK、Lint | 构建通过；Lint 0 errors、8 warnings（7 项版本更新提示、1 项现有可分配空间建议），未关闭依赖校验或忽略 lint 错误 |

生成的原始报告位于各模块 `build/test-results`、`build/outputs/androidTest-results/connected` 和 `app/build/reports/lint-results-debug.xml`。大样本测例摘录保存在 `build/verification/smb-large-case.xml`，同次完整运行保存在 `build/verification/smb-large/`；最终常规测试已经覆盖并通过独立 JVM 的强制退出用例。生成产物未纳入 Git。

Android 用例使用与正式应用相同的 `NetworkPolicy.socketFactory()`，避免模拟器在蜂窝与 Wi-Fi 之间选择不同网络。复现 Android 测试时先按[开发说明](development.md)启动独立临时 SMB 服务，再运行：

```powershell
.\scripts\build.ps1 :app:connectedDebugAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.class=space.zhuoling.fileaccess.TransferIntegrationTest' `
  '-Pandroid.testInstrumentationRunnerArguments.smbTestPort=24462' `
  '-Pandroid.testInstrumentationRunnerArguments.smbTestShare=test' `
  '-Pandroid.testInstrumentationRunnerArguments.smbTestUser=fileaccess-test' `
  '-Pandroid.testInstrumentationRunnerArguments.smbTestPassword=YOUR_ISOLATED_FIXTURE_PASSWORD'
```

测试夹具只运行随机临时共享。Impacket 0.13.1 的 EOF setter 实际会写零而不能正确缩短文件，因此夹具针对 EOF 设置改用 `os.ftruncate`；另增加一次性的空间/权限错误注入，不修改已安装的 Impacket 包。

## 边界与后续验收

源版本、SMB revision 都是元数据，并非不可变快照。跨时间片继续校验需要它们保持稳定；绕过 SMB 修改磁盘且保持所有元数据不变，不在此保证内。恢复/提交核对期间仍持有禁止其他 SMB 写入或删除的句柄。无版本、不可有效定位偏移的来源仍可能需要较长的一次运行；MediaStore/本地 SAF 视频通常提供可定位的流和版本信息。

初始创建恰好中断在临时文件身份尚未写入回执时，或两个回执槽均损坏时，不猜测所有权、不删除远端数据，显示结果待核对。服务端不提供可靠文件身份、违反 FLUSH/非覆盖重命名语义时，同样不能声称完成。NAS 掉电持久性、SMB3 加密、真实权限撤回、不同厂商后台调度及物理 Android 16 手机仍需要真机验收；本次不将隔离 SMB2/模拟器测试等同于这些验收。

合并时重点核对 `TransferEngine`、`Workers`、`TransferScheduler`、`TransferRepository/Daos` 的并行改动；保留租约检查、稳定 operationId 和终态去重记录。不要恢复 SMB 的 256 MiB 调度拦截，也不要在取消/关闭连接时直接按文件名删除 `.part`。
