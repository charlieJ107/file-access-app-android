# 03 协议抽象与后续私有 API 对接

## 抽象目标

用户已明确要求：新增协议应主要通过实现统一接口完成。设计采用“最小可用 Provider + 可选能力接口 + 能力驱动 UI”。基础接口覆盖识别、列举、元数据和读取；写入、范围读取、服务端复制、回收站、增量变更等分别扩展。

不把全部后端塞入类似 `java.io.File` 的假文件系统，也不将所有协议降级成下载 URL。S3 对象键、WebDAV URL、SMB 路径和私有节点 ID 都由适配器保管，业务层只看不透明引用。

新增协议通常需要：一个实现模块、注册工厂、声明配置字段、映射能力、跑契约测试。**新增一种已有抽象完全表达不了的业务能力，仍需扩展契约和相关 UI**；不能承诺任何未来协议都绝对零核心改动。

## 最小接口草案

以下为设计伪代码，不是当前工程已存在或可以直接编译的 SDK。名称可在 M0 调整，但语义必须保留。

```kotlin
data class EntryRef(val connectionId: ConnectionId, val opaqueId: String)
data class RemoteVersion(val opaqueToken: String, val strength: Strength)
data class Page<T>(val items: List<T>, val next: Cursor?, val coverage: Coverage)

interface StorageProviderFactory {
    val descriptor: ProviderDescriptor // typeId, config schema, protocol version
    suspend fun connect(config: ConnectionConfig, credentials: CredentialAccess): StorageSession
}

interface StorageSession : AutoCloseable {
    suspend fun roots(): List<RemoteEntry>
    suspend fun list(parent: EntryRef, request: ListRequest): Page<RemoteEntry>
    suspend fun stat(ref: EntryRef): RemoteEntry
    suspend fun openRead(ref: EntryRef, expected: RemoteVersion?): ReadHandle
    suspend fun capabilities(scope: EntryRef): CapabilitySnapshot
    fun <T : StorageCapability> capability(key: CapabilityKey<T>): T?
}

interface ReadHandle : AutoCloseable {
    val resolvedVersion: RemoteVersion?
    val length: Long?
    suspend fun read(buffer: ByteArray, offset: Int, count: Int): Int
}

interface RangeRead : StorageCapability {
    suspend fun openRange(ref: EntryRef, range: ByteRange,
                          expected: RemoteVersion?): ReadHandle
}

interface Upload : StorageCapability {
    suspend fun prepare(request: WriteRequest): UploadSession
    suspend fun restore(checkpoint: UploadCheckpoint): UploadSession
}

interface UploadSession : AutoCloseable {
    val mode: UploadMode // OneShot / Offset / Multipart / PrivateSession
    suspend fun reconcile(): UploadCheckpoint
    suspend fun writePart(part: PartSpec, source: ContentSource): PartReceipt
    suspend fun commit(condition: WriteCondition): CommitReceipt
    suspend fun abort(): CleanupResult
}

interface Mutations : StorageCapability {
    suspend fun createDirectory(request: CreateDirectoryRequest): RemoteEntry
    suspend fun rename(request: RenameRequest): MutationReceipt
    suspend fun delete(request: DeleteRequest): MutationReceipt
}
// 可选：ServerCopy、ServerMove、Trash、Versions、ChangeFeed、Search、Thumbnail
// 后续：EncryptedNamespace / VaultAccess、PrivateJobControl
```

`Mutations` 可继续拆细；每个操作的支持状态、权限、作用域和原子性仍必须独立声明。`restore` 在 OneShot 模式返回明确的“需从头重传”，不假造续传。`close` 只释放连接资源；删除远端临时数据由显式 `abort` 负责，进程关闭不能顺带撤销已提交对象。

业务层提交 `TransferPlan`，适配器决定 PUT、偏移写还是 multipart。不能把 HTTP 分片边界写死到同步引擎中。`ContentSource` 表达可重新打开、可 seek、长度是否已知、内容版本及取消行为，Android 实现用 ContentResolver 打开 `content://`，不强转成磁盘路径。

## 能力描述必须携带语义

`CapabilitySnapshot` 包含：支持 / 不支持 / 未知、原因、服务与账号作用域、探测时间、支持的操作模式与限制。

| 能力 | 必须表达的差异 |
| --- | --- |
| List | 远端分页 / 本地分页 / 非分页流；稳定排序；快照还是可变化的列表 |
| RangeRead | 能否从指定位置重开；如何固定版本；范围是否真正生效 |
| Upload | 单次 PUT、偏移写、multipart；最大对象/分片限制、恢复期限、校验方式 |
| ConditionalWrite | create-if-absent、match-version、无条件；前置检查不等于原子条件写 |
| Rename / Move | 同目录/同共享/同 bucket 的限制；原子 / 分步 / 不支持 |
| Integrity | 可比较的完整内容摘要、分片摘要、仅长度、不可验证 |
| ChangeFeed | 游标范围、保留期、删除事件、游标失效处理 |
| Trash / Versions | 可恢复条件、保留期是否可获取、是否允许永久删除 |
| Encryption | 传输加密 / 服务端加密 / 端到端加密；不能只用一个 lock 布尔值 |

有效能力 = 服务实现 ∩ 当前账号权限 ∩ 当前条目限制 ∩ 加密空间策略。探测采用无副作用方式；写入验证仅在用户发起的测试目录中创建可识别临时对象，失败后有清理记录。列目录成功不代表具备写权限。

统一失败类型：`AuthenticationRequired`、`PermissionDenied`、`LocalNetworkPermissionRequired`、`Unreachable`、`TlsUntrusted`、`NotFound`、`Conflict`、`QuotaExceeded`、`RateLimited(retryAfter)`、`Unsupported(reason)`、`CorruptData`、`SourceChanged`、`CommitOutcomeUnknown`。取消使用协程取消机制，不包装为普通失败重试。

## 四类协议的映射

下表是典型语义，不是对所有实现的保证；最终以能力探测和具体兼容测试为准。

| 项目 | WebDAV | SMB2/3 | S3（普通 bucket） | 私有网盘 |
| --- | --- | --- | --- | --- |
| 标识 | URL/href，路径相关 | share + 路径/服务器文件标识 | bucket + 原始 key，目录是 prefix | 以已有 API 的节点 ID 为准 |
| 目录 | Collection | 真目录 | 合成 prefix；可能有目录 marker | 等资料映射 |
| 列举 | PROPFIND，一般无标准分页 | 目录枚举 | ListObjectsV2 + token | 映射已有分页契约 |
| 下载范围 | HTTP Range，验证响应 | offset read | GetObject Range | 待确认 |
| 上传续传 | 基础 WebDAV 不保证 | offset write，需验证临时文件和源版本 | multipart，需保留 session 与 receipts | 映射已有会话 |
| 移动/重命名 | MOVE，作用域与实现相关 | 同 share 文件系统语义，需实测 | 一般 copy → 验证 → delete，非原子 | 待确认是否原子/异步任务 |
| 版本条件 | ETag/If-Match，弱 ETag 不作为强条件 | 句柄/锁/元数据；没有通用 HTTP ETag | ETag/versionId 和 endpoint 支持的条件请求 | 优先映射原生 revision |
| 增量变更 | RFC 6578 是可选扩展 | 不假定移动后台常驻监听 | 基础对象列表不是变更日志 | 若已有 change feed 则接入 |
| 回收站 | 基础协议无通用回收站 | 基础客户端不能假定可恢复 | versioning/delete marker 不等同产品回收站 | 映射已有恢复机制 |

S3 普通 bucket 的 rename 一般是复制和删除；AWS 的某些 directory bucket 提供专门 RenameObject，应该独立检测，不能把“所有 S3 都无法原子 rename”写成硬规则。ETag 也不能通用当作内容 MD5。[S3 复制/移动](https://docs.aws.amazon.com/AmazonS3/latest/userguide/copy-object.html)、[特定 bucket 的 rename](https://docs.aws.amazon.com/AmazonS3/latest/userguide/directory-buckets-objects-rename.html)、[内容完整性](https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity.html)

## NAS 首版实施

### WebDAV

候选方案为 OkHttp + 专门的 DAV 请求/响应层；XML 使用流式解析并禁用 DTD/外部实体。正确处理 DAV namespace、207 中每个 resource/propstat 的状态以及 percent encoding，不能见 HTTP 207 就全部成功。常用方法为 OPTIONS、PROPFIND Depth:1、GET/HEAD、PUT、MKCOL、COPY/MOVE、DELETE。[RFC 4918](https://www.rfc-editor.org/rfc/rfc4918.html)

允许配置 base URL、用户名/密码或服务支持的令牌、信任配置和兼容选项；首版优先 HTTPS。不能全局关闭证书验证来兼容自签名 NAS。

列表无标准分页时，流式写入临时 listing epoch，Room 对 UI 分页；仅完整响应成功后发布完整快照。大型目录的服务端首字节和完整列举成本不能被 Paging 库消除。

基础 DAV PUT 不承诺断点上传：优先唯一临时文件，完成后按支持的 MOVE 语义提交；若中断，需要重传并明确显示。只有验证过的扩展才能标为 resumable。`sync-collection` 用可选 ChangeFeed 接口接入；不支持就有界扫描。[RFC 6578](https://www.rfc-editor.org/rfc/rfc6578.html)

### SMB

SMBJ 作为首选验证候选，它明确实现 SMB2/3；Android 真机、签名/加密协商、长文件路径、取消和大文件偏移能力必须在 M0 验证后才能锁定。不要为“兼容”默认启用 SMB1。[SMBJ 项目](https://github.com/hierynomus/smbj)

连接配置：主机/IP、端口、共享名、可选域、用户名、密码、根目录及 SMB 安全要求。首版用户手填即可，自动发现列为增强项。单独处理“服务器连通但共享无权限”和“主机不可达”。

每连接限制会话与文件句柄；协程取消关闭对应句柄，网络改变重建会话。偏移续传必须核对临时对象归属、已确认长度及源版本，不能在未知旧文件上继续 append。默认不跟随超出授权根的链接/reparse 点，不递归追逐循环。

## S3 后续接入

优先验证官方 AWS SDK for Kotlin 的 S3 模块；官方支持 Android API 24+。采用 SDK 提供的认证/签名能力，不从头编写 SigV4；通过模块隔离 SDK 类型和体积影响。[AWS SDK for Kotlin](https://docs.aws.amazon.com/sdk-for-kotlin/latest/developer-guide/home.html)

配置字段包括 endpoint、region、bucket、prefix、寻址方式、access key / secret / 可选 session token。凭据必须是用户自己的或后端签发的最小权限凭据，不把公共固定密钥编译入应用。兼容服务需要验证 path-style、临时凭据、checksum 和条件请求；“S3-compatible”不是完整能力保证。

保留原始 key：不把连续 `/`、`.`、`..`、Unicode 规范化或 URL 编码后的字符悄悄改写。`a` 与 `a/` 可能并存；合成目录和真实对象使用不同节点类型。完整 bucket 全量递归列举必须由用户主动触发。

条件写和条件删除按 endpoint 能力验证；有版本支持时固定源版本复制。复制成功后，仅在可证明源未被替换且删除可安全执行时删除源；不能保证则保留源并报告“复制完成，源文件保留”。[S3 条件请求](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-requests.html)

## 私有网盘的对接清单

用户已有协议和加密方案，资料稍后提供。本节不规定 HTTP URL、不创造新服务端实现要求，也不假设使用 OAuth、REST 或某种密文格式。

| 应用侧契约 | 收到资料后需要映射的信息 |
| --- | --- |
| ProviderDescriptor | API 版本协商、服务识别、客户端最低版本 |
| CredentialAccess | 登录方式、续期/撤销、设备绑定；令牌过期后的安全重试 |
| EntryRef / list / stat | 稳定 ID、父子关系、大小、类型、revision、分页一致性 |
| RangeRead | 范围读取、短期下载链接、签名 URL 续期、固定版本读取 |
| UploadSession | 创建/查询/分片/完成/取消、断点恢复、会话过期、校验和 |
| WriteCondition | 不覆盖创建、匹配 revision 更新、提交幂等键或等效机制 |
| ChangeFeed | 游标、删除墓碑、保留窗口、失效后完整重建 |
| ServerCopy / Move | 原子性、异步任务 ID、部分失败、源端前置条件 |
| Versions / Trash | 恢复、永久删除、历史保留、权限 |
| VaultAccess | 现有文件格式、密钥层级、设备注册/恢复、分段随机读取 |
| TypedError | HTTP/业务错误映射、retry-after、配额、请求追踪 ID |

若已有协议缺少某项，就声明不支持或采用明确降级；不通过猜测补齐。复杂的私有异步任务可实现 `PrivateJobControl`，通用任务中心显示“远端处理中”，重启后通过任务 ID 查询，避免重复提交。

## 契约测试要求

共享 Provider 测试套件覆盖：空列表、大目录、分页中增删、Unicode/特殊名称、账号隔离、读到 EOF、取消释放资源、未知大小、权限变化、范围读版本固定、返回错误的类型映射。

能力专项测试覆盖：同名创建不覆盖、提交后响应丢失、同一幂等操作重复执行、分片恢复、冲突条件、部分批量成功、最终校验、回收站真实恢复。不可用能力必须显式返回 Unsupported，不能抛通用异常后让 UI 猜原因。
