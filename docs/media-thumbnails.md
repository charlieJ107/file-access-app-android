# 媒体缩略图与网格：实现和交接记录

2026-09-13。分支 `design/media-thumbnails-grid`，基于 `c0de6e4`，独立工作树 `FileAccess-media-grid`。对应[已批准方案](design/09-media-thumbnails-grid.md)。功能已实现，下面区分验证结果与尚未验收的设备、性能边界。

## 用户可见行为

- 目录工具栏支持列表／自适应网格切换，全局偏好保存在 DataStore。两种视图复用筛选、文件夹优先排序、选中集合、打开、下载、重命名和明确删除确认。
- 切换保留首个可见文件附近的位置；导航保存筛选、选择和滚动锚点。列表加载中的空快照不丢失目录身份，旧目录不会短暂显示在新目录中。完整刷新后移除已不存在的选中项，部分加载不误清选择。
- 图片显示静态缩略图，按显示密度采用 128／256／512 px 档位并应用方向信息；网格居中裁切。视频显示静态帧及可取得的时长。文件夹、音频和其他文件使用类型图标。
- 仅可见项和停止滚动后最多下一行有资格发起加载；失败时仍可打开、下载原文件。只有需要处理时显示目录级提示，避免逐项通知。
- 默认只在非计费网络自动读取缩略图；判断使用现有 `NetworkPolicy` 所选网络，尊重 VPN 和无公网的局域网。目录内显式继续加载可临时允许计费网络。设置页可修改策略、清除缩略图缓存，媒体菜单可重新加载单个缩略图。

## 实际实现取舍

缩略图代码集中在 `app/.../thumbnail/`，浏览布局在 `ui/BrowserScreen.kt` 与 `ui/browser/BrowserItems.kt`。图片生成使用平台 `ImageDecoder`，UI 用 Compose `Image`；由一个 `LruCache` 统一持有 bitmap，避免再经过 Coil 重复缓存已解码图片。原有全屏图片预览继续使用 Coil，Media3 播放管线保持原状。没有新增依赖或更新版本。

视频使用 Android `MediaDataSource` 和 `MediaMetadataRetriever`；底层 `ThumbnailReader` 将随机偏移传给现有 `StorageSession.openRead`，连续读复用句柄，缓存碎片与 SMB 短读在平台适配层合并。取帧接近前 1 秒的同步帧，不回退为整文件下载。平台吞掉读取异常时保留原始异常，以区分网络、预算和取消错误。

`BrowserState` 尾部增加带默认值的 `connectionRevision`、`listingEpoch`、`requestedRef`。目录快照与建立会话时的连接配置 revision 绑定；缩略图缓存命中和发布均校验该版本。编辑、删除连接会取消旧请求并撤图；文件重命名／删除使旧 EntryRef 缓存失效。清理缓存用 generation 和清理中的屏障阻止旧任务回写、阻止新请求在删除完成前重新读到旧缓存。

| 资源 | 当前上限／规则 |
| --- | --- |
| 生成 | 总并发 2、每连接 1、视频解码 1、订阅队列最多 64；等待视频解码器不占用全局图片 IO 槽 |
| 图片来源 | 16 MiB；已知超限直接降级，不建立远端会话；未知大小流式限制，临时原文件总受生成并发约束 |
| 视频来源 | 单次远端读取 8 MiB、最多 32 次非连续打开，最大 256 KiB 一次底层读；碎片缓存 2 MiB／32 块 |
| 自动读取预算 | 每个目录浏览预算初始 64 MiB；刷新不重置；明确继续加载增加 64 MiB；进程内保存最近 64 个目录预算 |
| bitmap 缓存 | `min(32 MiB, 最大堆 / 8)`；可复用同版本较大尺寸缩略图；未回收仍在 UI 使用的 bitmap |
| 磁盘缓存 | 私有目录 `cacheDir/thumbnails`；128 MiB／最多 2048 成品，7 天未访问淘汰；不重复缓存原文件 |
| 磁盘余量 | 开始生成时要求 512 MiB 余量及 32 MiB 在途预留；只清理缩略图，不触碰传输 staging |
| 重试 | 网络错误按连接冷却 30 秒；其他失败短期记忆 5 分钟、最多 512 项；显式重试清除失败记录；认证错误需修正连接／凭据 |

磁盘成品是一个包含时长头部和 PNG 的原子发布文件，路径按连接、条目、内容版本哈希隔离。没有原文件 EXIF／GPS。无 revision 的图片只在当前目录快照 epoch 内复用内存，不写入磁盘；视频要求已知长度、revision 和 rangeRead。缓存是应用私有明文，沿用应用禁止系统备份的规则。

## 验证证据

使用专用 Android 16 / API 36 x86_64 模拟器 `emulator-5572`，1080×2400、420 dpi；与其他工作的模拟器隔离。真实 SMB 使用单独的 loopback 测试服务、随机临时共享目录和仅用于 fixture 的凭据，没有访问个人 NAS。

| 检查 | 结果 |
| --- | --- |
| Debug APK、AndroidTest APK、App JVM 测试、core:data JVM 测试、Lint | 通过；普通 Gradle 依赖校验，没有改校验元数据 |
| 新增媒体 JVM 契约测试 | 8/8：键隔离、分类、超过 2 GiB 偏移、短读与预算退款、缓存重复读、seek 上限、单项预算、幂等关闭 |
| core:data JVM 回归 | 5/5；App 原有模板单元测试另 1 项通过 |
| 完整 App Android 回归 | 29/29，包含已有 7 项真实 SMB 传输／UIDT 流程、4 项原有 UI，以及当时的媒体和设置测试 |
| 最终队列／资源调整后的媒体专项 | 14/14，包含新增清理期间取消和超大图片零读取用例；与完整回归合计覆盖 31 个不同设备用例 |
| 图片 | PNG 降采样／透明度、JPEG EXIF 1–8 的实际像素方向正确；缓存重启命中、缓存损坏重建、权限撤销清缓存 |
| 视频 | 平台生成 H.264；头部索引与竖屏方向、尾部索引、带 12 MiB 填充的尾部索引视频均成功取帧 |
| 浏览交互 | 列表／网格打开同一条目，筛选及选择保留、明确删除确认、未完整目录提示；10,000 项目录只组合可见窗口，切换仍在第 5000 项附近 |
| 设置与取消 | DataStore 重建后视图／网络偏好保留；相同请求去重、取消一个订阅不影响另一个、最后订阅取消释放会话、清缓存后无迟到成品 |
| Lint | 0 errors，9 warnings：6 项现有依赖更新建议、现有预览及新缩略图的 3 项可用空间 API 建议；没有新增 baseline |
| 最终测试后的 crash buffer | 空 |

最后一次真实 SMB 样本记录：2220 字节的生成 PNG 读取 2220 字节、生成耗时 47 ms；12,587,476 字节视频读取 138,003 字节、耗时 410 ms，包含直接跳转到文件尾部的读取。这是本机 fixture 的单次观察，不代表真实 NAS 吞吐或照片性能基准。

本机生成的测试日志（不提交构建输出）；脱敏截图另存入文档目录供 PR 审查：

- `build/media-validation/final-build.log`
- `build/media-validation/final-device-tests.log`（完整 29 项）
- `build/media-validation/final-media-tests.log`（最终媒体 14 项）
- `build/media-validation/media-metrics.log`、`crash.log`
- [网格截图](images/media/grid.png)、[列表截图](images/media/list.png)：使用合成色块图片检查布局，不包含个人媒体。

复现构建：

```powershell
.\scripts\build.ps1 :app:testDebugUnitTest :core:data:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

设备测试沿用[开发说明](development.md)的隔离 SMB 参数。本次为防止影响另一台模拟器，使用 `adb -s emulator-5572 install` 安装两个 APK，再运行 `adb -s emulator-5572 shell am instrument -w`，显式传入 `smbTestPort`、`smbTestShare`、`smbTestUser`、`smbTestPassword`。没有隔离服务时 SMB 测试会跳过，跳过不算通过。

## 仍需真实设备验收的边界

PR 评审修复了分批刷新时的滚动位置恢复：首批 100 项尚未包含原锚点时继续等待后续条目，仅在完整列表确认文件已不存在时回到顶部。新增 Compose 回归覆盖列表和网格的空快照、100 项、300 项、完整 600 项刷新序列；本次仅编译该设备测试，尚未在设备上运行新增用例。

- HEIF/HEIC、AVIF、WebP、GIF、H.265 和其他 OEM 编码器的完整格式矩阵尚未逐项验收；当前通过平台解码能力尝试静态显示，失败回退类型图标。音频专辑封面、RAW、SVG、PDF 封面不在本期范围。
- 视频 10 秒是停止继续读数据的软时限，不能强制中断平台原生解码；实际网络关闭仍受 SMB 超时约束。没有承诺所有损坏视频都能在 10 秒内释放；已运行工作的槽位一直保留到真实退出。
- SMB revision 是元数据，同大小／同时间替换可能仍命中缓存；可通过媒体菜单显式重载。离线时不能即时获知服务端撤权。
- 现有目录条目仍全量保存在内存；10k 懒布局测试不等于目录分页。没有完成真实手机 p95 帧耗时、宽屏／大字体／TalkBack 的全面验收或 1000 项真实媒体网络基准，不宣称原设计所有性能目标均已达成。

## 给合并 agent

主要新文件在 `app/.../thumbnail/`、`ui/BrowserScreen.kt`、`ui/browser/BrowserItems.kt`，测试和 `media_strings.xml` 独立新增。`Screens.kt` 中原 BrowserScreen 已搬到新文件，Page／EmptyState／NameDialog／entryDetail 改为 internal 供复用。

共享修改点为 MainViewModel 的构造注入及目录状态、FileAccessApp 的浏览／设置接线、SettingsRepository 和 AppSettings 的新增字段。合并其他 UI 或设置分支时保留双方字段和构造参数，避免把已移出的 BrowserScreen 再复制回 Screens.kt。现有位置参数调用因新增字段／参数带默认值仍兼容。

没有修改上传、后台调度、SMBJ、协议能力接口、Room schema 或 Gradle 依赖。缓存失效由 app 层调用，不要求合并其他协议分支后才能使用。本分支不负责合并回 master，也不覆盖其他工作树。
