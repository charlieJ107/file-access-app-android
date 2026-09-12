# 08 依据、版本核对和设计决策

核对日期：2026-09-12。以下只引用官方文档、标准组织和库作者项目。官方页面会更新；版本快照不是永久依赖锁定，实际编码前还需验证组合兼容性。

## 关键事实与选型依据

| 主题 | 本轮核对结果 | 来源 |
| --- | --- | --- |
| 新应用 UI | Google 已明确 Compose-first；Compose 仍是现代原生 Android 的推荐 UI 技术 | [Compose-first](https://developer.android.com/develop/ui/compose/first) |
| 架构 | UI/data 分层、仓库、单向状态、协程/Flow、单 Activity；当前指南推荐 Navigation 3 | [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations) |
| 导航 | Navigation 3 面向 Compose；稳定线已存在 | [Navigation 3](https://developer.android.com/guide/navigation/navigation-3)、[发行说明](https://developer.android.com/jetpack/androidx/releases/navigation3) |
| Room 3 | 新包 `androidx.room3`、KSP、Kotlin/协程优先，稳定线已存在 | [Room 3 发行说明](https://developer.android.com/jetpack/androidx/releases/room3)、[2→3 差异](https://developer.android.com/training/data-storage/room/migration-2-to-3) |
| 构建 | AGP 9.4 兼容 API 37，Gradle 最低 9.6.0，JDK 最低 17 | [AGP 9.4](https://developer.android.com/build/releases/agp-9-4-0-release-notes) |
| Kotlin / Compose | AGP 9 内置 Kotlin；Compose BOM 和 compiler/Kotlin 各有版本管理范围 | [内置 Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)、[BOM](https://developer.android.com/develop/ui/compose/bom) |
| DI | Hilt 为官方 Android DI 建议；可与 Compose 和 WorkManager 集成 | [Hilt](https://developer.android.com/training/dependency-injection/hilt-android)、[Jetpack 集成](https://developer.android.com/training/dependency-injection/hilt-jetpack) |
| 大屏 | 根据可用窗口适配导航与列表详情 | [Adaptive apps](https://developer.android.com/develop/adaptive-apps/guides/get-started-with-adaptive-apps) |

当前检索到的稳定版本示例：Navigation 3 1.1.7、Material 3 1.4.0、Material 3 Adaptive 1.3.0、WorkManager 2.11.2、DataStore 1.2.1、Media3 1.11.0、Room3 3.0.3。这些是查询快照，不是已经在本项目联编通过的依赖组合；Compose UI 由稳定 BOM 配套，不根据单个最高版本拼装。[AndroidX 稳定版本总表](https://developer.android.com/jetpack/androidx/versions)

## 对本应用有直接影响的平台约束

| 约束 | 对应设计 | 来源 |
| --- | --- | --- |
| Android 17 / target 37 的 LAN 访问保护 | NAS 连接流程按需申请局域网权限，区分权限与超时 | [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)、[Android 17 行为变化](https://developer.android.com/about/versions/17/behavior-changes-17) |
| 自动与用户发起的数据传输不同 | WorkManager 自动任务；UIDT 手动长传输；共同任务表 | [传输 API 选择](https://developer.android.com/develop/background-work/background-tasks/data-transfer-options)、[UIDT](https://developer.android.com/develop/background-work/background-tasks/uidt) |
| 系统限制长后台工作 | checkpoint、可中断批次，不依赖永久服务 | [长运行 Worker](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)、[FGS 超时](https://developer.android.com/develop/background-work/services/fgs/timeout) |
| 周期任务不保证准点 | 展示上次检查与等待条件，不承诺准实时备份 | [Work requests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work) |
| 媒体/文档授权用途不同 | 手动 Picker、自动 MediaStore、目录 SAF；部分授权不会变成全库访问 | [Picker](https://developer.android.com/training/data-storage/shared/photo-picker)、[MediaStore](https://developer.android.com/training/data-storage/shared/media)、[SAF](https://developer.android.com/training/data-storage/shared/documents-files) |
| 任意远端不是默认播放器 URL | 使用自定义 DataSource 桥接 offset/read | [Media3 DataSource](https://developer.android.com/reference/androidx/media3/datasource/DefaultDataSource) |

## 协议与安全依据

| 主题 | 来源 |
| --- | --- |
| WebDAV 基础和可选集合同步 | [RFC 4918](https://www.rfc-editor.org/rfc/rfc4918.html)、[RFC 6578](https://www.rfc-editor.org/rfc/rfc6578.html) |
| SMB 库验证候选 | [SMBJ 作者仓库](https://github.com/hierynomus/smbj) |
| S3 Android 客户端候选 | [AWS SDK for Kotlin 支持平台](https://docs.aws.amazon.com/sdk-for-kotlin/latest/developer-guide/home.html) |
| S3 对象操作差异 | [复制/移动](https://docs.aws.amazon.com/AmazonS3/latest/userguide/copy-object.html)、[directory bucket rename](https://docs.aws.amazon.com/AmazonS3/latest/userguide/directory-buckets-objects-rename.html) |
| S3 版本条件与校验 | [条件请求](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-requests.html)、[完整性](https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity.html) |
| 本地密钥与弃用状态 | [Android cryptography](https://developer.android.com/privacy-and-security/cryptography)、[Keystore](https://developer.android.com/privacy-and-security/keystore)、[EncryptedSharedPreferences](https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences) |
| 系统备份 | [Auto Backup](https://developer.android.com/identity/data/autobackup) |
| 成熟加密原语参考，不替换已有私有格式 | [Tink 大文件加密](https://developers.google.com/tink/encrypt-large-files-or-data-streams)、[Streaming AEAD](https://developers.google.com/tink/streaming-aead)、[Wire format](https://developers.google.com/tink/wire-format) |

## 决策记录

“已确认”来自用户要求或本项目事实；“建议”是本设计选择，后续根据测试修订；“待资料”不阻塞 NAS 首版。

| ADR | 决策 | 状态 | 理由与代价 |
| --- | --- | --- | --- |
| 001 | Android 原生 Kotlin + Compose | 已确认原生方向，Compose 为建议选型 | 对齐官方现代路线，初始需要搭建设计系统和状态管理 |
| 002 | 首版 NAS（SMB/WebDAV），S3/私有后续 | 用户已确认 | 限定验证范围，优先满足实际用途 |
| 003 | 最小 Provider + 可选能力接口 | 用户已确认接口扩展方向 | 新协议可复用浏览/任务/同步；新业务语义仍可能需要扩展 API |
| 004 | 持久任务表 + 独立执行器 + 多调度入口 | 建议 | 处理进程退出、重试和提交未知；增加状态机与迁移成本 |
| 005 | 默认只备份、不传播本机删除 | 建议 | 保护照片副本；镜像/双向同步另有明确模式 |
| 006 | 首期 minSdk 35、target/compile 37 | 建议沿用现状 | 降低旧系统分支成本；无法安装到 Android 14 及更早版本 |
| 007 | 渐进模块化，最初约 8 模块 | 建议 | 协议隔离最先落地，避免大量空 feature 模块 |
| 008 | Room3 稳定线优先，数据库边界隔离 | 建议，M0 联编验证 | 新工程无需旧 API 迁移；必需集成不兼容时有 Room2 回退决策 |
| 009 | 编译期协议扩展，不运行下载插件 | 建议 | 安全、可测试且维护成本低；新增协议需重新发布 APK |
| 010 | 私有 API/E2EE 对齐已有方案 | 用户已确认已有方案，待资料 | 避免另建不兼容协议；加密功能后续独立验收 |
| 011 | 不使用永久后台服务承诺实时同步 | 建议，受平台约束 | 可恢复和透明等待更可靠；上传时间受系统调度影响 |
| 012 | 默认无广告/无收费功能依赖/无遥测 | 用户需求方向与设计建议 | 用户控制数据；诊断依赖本地去敏报告和主动导出 |

## 后续需要的信息

NAS 实际型号/服务版本、测试手机系统、常见最大视频大小和相册规模，会决定 M0 的兼容与性能数据集。私有 API、认证和 E2EE 资料按照用户计划后续再提供，不重复要求现在提供。

以上信息影响实现细节；不会改变首版 NAS 优先、原生 Compose 和统一协议接口这三个方向。
