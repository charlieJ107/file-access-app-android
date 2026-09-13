# 签名密钥管理与轮换

正式私钥只保存在 GitHub Environment `release` 的 Secrets 中，这是维护者明确选择的存储方式。本地开发使用 Android debug 证书或独立开发 keystore；不下载正式私钥用于开发，不保留本地正式 keystore、密码文件或备份。生成工具短暂创建随机临时 keystore，读取后立即删除；密码只存在进程内存、子进程环境和 GitHub Secret 中。公钥证书、指纹、签名 APK 与公开 lineage 不含私钥，可以公开。

## 签名身份与更新规则

应用最低 Android 15（API 35）。每个正式 APK 只有一个当前签名者，可通过 Android APK Signature Scheme v3 的 proof-of-rotation 建立 `A → B → C` 授权链。当前签名相同时允许更新；当前签名变化时，候选 APK 经 Android 验证的签名历史必须包含已安装版本的当前证书，并沿该证书向前延伸。旧 A 重签更高版本不能接替已安装的 B，仅共享 A 祖先的 `A → X` 也不能接替 B。多签名 APK 必须完整匹配当前签名集合，不使用轮换规则。

App 允许平台支持的截短历史（例如已安装 B 接受有效 `B → C`），不强制旧历史为新历史的完整前缀。正式发布 CI 另有保留历史的项目规则：新 APK 必须完整延续上一正式 APK 的历史，保证尚未升级的旧用户仍能验证授权链。所有历史节点保留 `installed-data` 能力，并禁止 `rollback` 能力。Android 系统安装器仍对实际覆盖安装做最终权限检查。

生产签名工具显式关闭 v1/v2/v4，使用 v3 和 `--rotation-min-sdk-version 28`。设备范围仍是 APK 的 minSdk 35，这个签名选项不会扩大支持范围；它避免 v3.1 的低平台签名回退要求把历史私钥继续交给 CI。发布签名只需要当前私钥和公开 lineage。包名、SemVer、versionCode、资产大小、SHA-256 和 HTTPS 边界继续独立验证。

## 原子签名 Secret

`RELEASE_SIGNING_BUNDLE` 是一个 JSON Secret，schema 为 1，包含 `keystore_base64`、`store_password`、`key_alias`、`key_password` 和可为空的 `lineage_base64`。它通过一次 Secrets 更新同时替换密钥、密码与授权链，避免分别更新四个 Secrets 导致半更新。

`RELEASE_NEXT_SIGNING_BUNDLE` 只在轮换时暂存：外层包含 schema、候选 bundle 和 request（nonce、release 源 SHA、新证书指纹；验证后增加旧证书指纹）。准备成功后加入完整 lineage，再原子晋升到 ACTIVE。失败时保留 NEXT 和维护锁，不能根据客户端报错推断远端一定未写入。

不要手工混用 bundle 和旧 `RELEASE_*` 签名字段。签名 CLI 为隔离测试和已有本地配置保留旧环境变量/文件接口，但正式 workflow 只读取 bundle。Gradle 构建步骤不读取正式签名 Secrets；签名发生在独立步骤，子进程不继承 GitHub Token 或整个 bundle。

## 首次初始化

前置条件是 GitHub CLI 已登录、拥有仓库 Secrets 和 Git refs 管理权限，且 Environment `release` 已创建并仅允许 `release` 分支。使用 JDK 自带 keytool。以下路径是命令示例，按实际安装位置填写：

```powershell
python scripts/manage-signing-key.py initialize `
  --repo charlieJ107/file-access-app-android `
  --gh 'C:\Program Files\GitHub CLI\gh.exe' `
  --keytool 'C:\Users\Charlie\AppData\Local\Programs\Android Studio\jbr\bin\keytool.exe'
```

工具在生成前检查现有 Secret，拒绝覆盖 ACTIVE、遗留的旧签名 Secrets 或待处理 NEXT。它生成 RSA 3072 / SHA256withRSA、约 30 年有效期的自签名证书，使用随机强密码，通过标准输入交给 `gh secret set`。输出只含公开证书 SHA-256 和操作状态。源码、命令参数、日志、APK 和 artifact 均不包含私钥或密码。

初始化只配置签名，不创建 GitHub Release，也不改变仓库可见性。当前更新源仓库已公开，正式签名已配置；首次发布通过 `master → release` 发布 PR 和 CI 执行。维护 workflow 随该代码进入 `release` 后才能运行；初始化本身不依赖维护 workflow。

本仓库已于 2026-09-13 完成首次初始化：Environment `release` 仅允许 `release` 分支，正式密钥已存入 `RELEASE_SIGNING_BUNDLE`，本地临时 keystore 和远端维护锁已清理。初始证书的公开 SHA-256 指纹为 `5920c6f3e3c008493457294b8bef9805fb63c5721666d1ad4568acf728a30db3`。这条历史记录不代替后续轮换时的状态检查，也不表示已经发布正式 APK。

## 仅凭 GitHub 中的旧私钥轮换

先确保已分发 App 的更新器支持原生轮换。若严格相等版已经分发，应先用旧密钥发布支持轮换的过渡版；未安装过渡版的用户需要通过系统安装器手动升级到合法新包。

```powershell
python scripts/manage-signing-key.py rotate `
  --repo charlieJ107/file-access-app-android `
  --gh 'C:\Program Files\GitHub CLI\gh.exe' `
  --keytool 'C:\Users\Charlie\AppData\Local\Programs\Android Studio\jbr\bin\keytool.exe'
```

工具生成候选新密钥，删除临时本地 keystore，把候选存入 NEXT，然后用 `gh workflow run` 调用 `release` 分支上的 `prepare-signing-rotation.yml`。维护 job 从 GitHub 读取旧、新 bundle，使用旧私钥授权新证书，继承已有 lineage，并用临时最小 APK 验证实际签名与授权能力。它只上传公开 `lineage.bin` 和证书/请求元数据，不上传私钥，也不需要 Secrets 写权限或额外管理员 Token。

本地管理进程等待准确 nonce 和源 SHA 的运行成功，校验返回的证书指纹及 lineage 摘要，把公开证明加入仍在内存中的新 bundle，然后一次更新 ACTIVE 并清理 NEXT。轮换密钥本身不会发布新 APK；下次正式发布才用新密钥和完整历史签名。

## 并发控制与失败恢复

初始化和轮换通过 GitHub 原子创建临时分支 `signing/maintenance-lock` 排他执行，锁覆盖检查、生成、CI 验证、晋升与清理全程。这个分支只保存公开的操作标识和原始代码树，既不包含签名材料也不触发发布。成功后删除锁；已经开始远端写入而出现错误时保留锁。不得绕过工具并发改写签名 Secrets，也不得在仍有维护进程运行时恢复或手动删除锁。

公开状态检查（需要维护 workflow 已存在于 `release`）：

```powershell
python scripts/manage-signing-key.py inspect --repo charlieJ107/file-access-app-android
```

检查返回 ACTIVE/NEXT 的证书指纹、请求标识和是否已有 lineage，不返回私钥或密码。若原进程中断，先确认它已停止及对应 workflow 已结束。对照预期公开指纹恢复，不能因为一次 HTTP 响应丢失就重建或覆盖密钥：

```powershell
python scripts/manage-signing-key.py recover --repo charlieJ107/file-access-app-android `
  --lock-sha <失败操作保留的锁SHA> `
  --expected-active-certificate <确认保留的当前证书SHA256>
```

若 ACTIVE 已等于验证后的 NEXT，恢复完成遗留清理；若 ACTIVE 仍是旧密钥，工具要求额外显式传 `--discard-pending` 才取消未晋升的候选。当前证书与预期不符时停止，不修改 Secrets。初次初始化发生网络错误时，远端可能已保存唯一 key；保留输出中的公开指纹和锁信息，核对状态后再处理，禁止直接重新初始化。

恢复先通过 fast-forward 获取新的锁 SHA，后续恢复须使用最新输出的锁值。若初始化失败且确认 ACTIVE/NEXT 均不存在，可用 `--expected-active-certificate absent` 清理锁；工具会再次检查 Secret 名称。若 ACTIVE 已存在而维护 workflow 尚未进入 `release`，保留锁及指纹，待可运行公开状态检查后再恢复，不能以删除 Secret 的方式重试。

本地副本丢失不会影响 GitHub 中仍可用的旧密钥。如果 GitHub 中的唯一旧私钥或其密码也不可用，而且没有预先授权的后继密钥，就无法补造轮换证明。重新填写相同 Alias、证书名称或版本号不能恢复原签名身份。

## 验证

普通 CI 运行 Python 发布/签名管理测试、App JVM 签名策略测试，并用一次性密钥生成真实 APK，验证生产签名 CLI 的接受与拒绝路径；不使用正式 Secrets。设备验证方法见 [测试脚本说明](../scripts/signing-rotation/README.md)。API 35+ 模拟器验证 A→B→C、跳过中间版本、数据保留，以及旧密钥、分叉和无关密钥被拒绝。App instrumentation 验证从系统读取的真实签名历史。

参考：[Android v3](https://source.android.com/docs/security/features/apksigning/v3)、[SigningInfo](https://developer.android.com/reference/android/content/pm/SigningInfo)、[apksigner](https://developer.android.com/tools/apksigner)、[GitHub Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)。
