# 分支管理与 GitHub Release

## 分支职责

| 分支 | 职责 | PR 目标 |
| --- | --- | --- |
| `feature/*`、`fix/*`、`docs/*` | 从 `master` 创建，开发与验证单项修改 | `master` |
| `master` | 日常开发与集成主分支，也是仓库默认分支；合并后运行普通 CI | 准备发布时提交到 `release` |
| `release` | 长期发布分支；合入后由 GitHub Actions 构建签名 APK 并创建 GitHub Release | 发布修复通过 PR 同步回 `master` |

本项目是客户端应用，目前没有独立的预发布部署环境或需要单独维护的集成阶段，因此不保留长期 `staging` 分支。功能经过 Review 和 CI 后直接合入 `master`；不要求每次合并都发布版本。准备发布时，在普通 PR 中更新 `version.properties`、完成验证，再创建 `master → release` 发布 PR。

发布 PR 必须使用 **merge commit**，保持长期分支的共同历史；不要对 `master → release` 使用 squash/rebase merge。`release` 的推送才会触发正式发布工作流，`master` 的推送只触发普通 CI。发布前应检查整个 PR 差异，确保 `master` 中的全部待发布功能都已准备好。

紧急修复可以从 `release` 创建 `hotfix/*`，递增 SemVer 和 versionCode 后向 `release` 提交 PR，执行同样的 Review、CI 和发布检查。发布后通过 `release → master` 的 merge commit PR 同步修复和版本信息；冲突应在临时同步分支解决。避免为了发布修复而带入尚未准备好的 `master` 功能，也不要让后续正常发布退回旧版本号。

建议在 GitHub Rulesets 中对 `master` 和 `release` 要求 PR、评审、必需检查，禁止 force-push 和删除。正常发布 PR 的来源是 `master`，紧急修复按上述流程处理。工作流中的分支校验不能代替 Rulesets：不应直接向 `release` 推送日常开发提交。仓库默认分支保持 `master`，以便新 PR 使用正确目标。规则设置属于仓库管理配置，本文件不会自动启用服务端保护；启用规则时从一次真实运行中选择对应的 CI 检查名称。

## 从旧 staging 模型迁移

旧模型使用 `staging` 集成、`master` 发布。此次迁移保留全部历史，不重写分支或移动已发布标签：

1. 检查工作区、工作树、远程分支和现有 PR；保留未提交修改。确认旧 `master` 的基线提交没有 `.github/workflows/release.yml`。本仓库已核对的基线是 `c0de6e485fbd9e29299570655696c35e78fe9eec`，它仅包含普通 Android CI。
2. 从这个已核对、没有发布工作流的旧基线建立 `release` 并推送。这个初始分支只是首次发布的比较基线，不代表已经发布过版本。不要直接从含有发布触发器的最新集成提交初始化 `release`，以免创建分支就触发发布。
3. 从旧 `staging` 创建一次性的迁移分支，保留已合并功能的全部提交，同时将文档、CI 触发器和发布脚本的分支限制改为本规范。迁移 PR 直接以 `master` 为目标，Review 和 CI 通过后使用 merge commit 合并。这是迁移例外；后续普通开发一律从 `master` 创建分支。
4. 关闭被迁移 PR 取代的旧 `staging → master` 发布草稿，并注明替代关系。从合并后的 `master` 创建新的 `master → release` 发布草稿；公开更新源、签名和其他发布前置条件未就绪时保持草稿。
5. 确认旧 `staging` 的最新提交已经是 `master` 的祖先，且没有打开的 PR 再以 `staging` 为来源或目标，才删除远程和本地的 `staging`。发现新增提交时先通过 PR 保留它们，不能强制删除或重置历史。

如果其他仓库或未来迁移的基线已经包含发布工作流，必须重新制定初始化方式，不能照搬上述基线假设。

## 版本与更新协议

`version.properties` 同时供 Gradle 与发布脚本读取，例如：

```properties
versionCode=2
versionName=0.2.0
```

- `versionName` 使用 [Semantic Versioning 2.0.0](https://semver.org/)；正式发布采用 `MAJOR.MINOR.PATCH`，标签为 `vMAJOR.MINOR.PATCH`。功能增加递增 minor，兼容修复递增 patch，不兼容变更递增 major；0.x 阶段按开发版本管理。
- App 按 SemVer 数值与预发布优先级比较版本，忽略 build metadata；例如 `1.10.0 > 1.9.0`。自动更新只接受正式版本，过滤 draft/prerelease，也过滤带预发布后缀的标签。
- `versionCode` 是独立的 Android 安装序号，每次发布必须递增，范围 1–2100000000；若 SemVer 更新但 versionCode 未递增，会报告发布配置错误。
- 每个 Release 恰好包含一个通用签名 APK：`fileaccess-<versionCode>.apk`，另附 `SHA256SUMS`。App 读取 GitHub 资产的 `digest=sha256:...`，核对实际下载字节，再核对包名、版本名、安装序号、最低 SDK 和签名。
- 已发布的标签与 APK 不覆盖、不移动；问题修复发布新版本。未发布草稿可在同一 commit 上重跑，先补齐制品再公开。

## 首次发布配置

1. 由仓库维护者在发布前将当前仓库 `charlieJ107/file-access-app-android` 设为公开。App 匿名读取 Release API；不内置 GitHub Token。工作流会在私有仓库上明确失败。
2. 创建 GitHub Environment `release`，建议将 deployment branches 限制为 `release`；可按团队需要配置 required reviewers。
3. 使用长期保管的正式签名 keystore，添加以下 Environment Secrets：

| Secret | 内容 |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | keystore 文件的 Base64 内容 |
| `RELEASE_STORE_PASSWORD` | keystore 密码 |
| `RELEASE_KEY_ALIAS` | 签名密钥别名 |
| `RELEASE_KEY_PASSWORD` | 私钥密码 |

签名文件仅在 runner 临时目录中解码，构建结束和失败时清理；不要上传为构建 artifact。所有正式版本必须使用相同签名，本版更新器不支持签名轮换。原有 debug 安装使用不同证书，无法直接覆盖为正式版；不要通过卸载来“修复”签名错误而丢失用户数据，应提前安排首次正式版安装与数据处理。

GitHub Actions 的 `GITHUB_TOKEN` 仅在发布 job 获得 `contents: write`；PR 检查不使用签名 Secrets。工作流不创建私钥，缺失配置会失败。公开仓库或正式签名配置未就绪时，保留发布草稿，不合并发布 PR。

## 日常开发与发布 CLI

安装 GitHub CLI 并执行 `gh auth login`。以下命令中的版本与 PR 编号按实际结果填写，并遵循本次任务已明确的提交、推送、合并和发布授权范围：

```powershell
git fetch origin
git switch -c feature/your-change origin/master

# 开发完成、提交并推送后创建普通 PR
gh pr create --base master --head feature/your-change --title "描述最终变化" --body-file pr-body.md
gh pr checks <PR编号> --watch

# 可手动构建并验证 master
gh workflow run android-ci.yml --ref master
gh run list --workflow android-ci.yml --branch master --limit 5
gh run watch <run-id> --exit-status

# 在普通 PR 中更新 version.properties、发布说明与验证结果，确认发布前置条件后
gh pr create --base release --head master --title "Release v0.2.0" --body-file release-notes.md
gh pr checks <发布PR编号> --watch
gh pr merge <发布PR编号> --merge

# release push 自动启动发布；以下命令可跟踪结果
gh run list --workflow release.yml --branch release --limit 5
gh run watch <release-run-id> --exit-status
gh release view v0.2.0
gh release download v0.2.0 --pattern "fileaccess-*.apk" --pattern SHA256SUMS --dir release-download
```

`.github/workflows/android-ci.yml` 覆盖 `master` push、所有 PR、手动执行和可复用调用。`.github/workflows/release.yml` 仅在 `release` push 或手动调用时启动，并在 job 和发布脚本中再次限制发布分支为 `release`。它先调用普通 CI，运行 Debug 构建、单元测试和 Lint；随后检查公开可见性、版本递增和标签指向，再构建签名 Release APK、执行 Release Lint 与 apksigner 验证，通过 `gh release create/upload/edit` 上传、核对资产并发布。

失败时修复配置后可 `gh run rerun <run-id> --failed`，或 `gh workflow run release.yml --ref release`。手动发布必须指定 `release`，选择 `master` 或其他分支不能发布；已公开版本不能通过重跑覆写。源码变更应发布新版本，不能把旧草稿标签改指向另一 commit。

发布完成后检查 Release 的版本、APK、SHA256SUMS，并在同签名旧版本上验证覆盖升级、用户数据保留、拒绝安装权限及取消安装后的重试。真实 SMB 测试与 Android 设备测试仍需隔离环境，默认 CI 不代替这些验收。

## App 更新交互与边界

默认在启动/回到前台时检查，每 24 小时最多自动请求一次（包括失败尝试），设置页可关闭或手动重试。无网络、限流、未公开仓库及无 Release 会在设置页显示原因，不阻断文件操作。不会在 App 未运行时定时唤醒。

检测到新版本后显示发布说明，用户点击下载，显示进度并可取消。APK 上限 256 MiB，HTTPS 来源限制为本仓库及 GitHub 资产域名，保存于私有 `files/updates/`；失败或取消删除临时文件，下一次下载清理旧版本缓存。下载在当前进程中执行；进程被终止后从头重试，已完成文件会重新校验后复用。

下载完成点击安装；首次进入“允许安装未知应用”设置，授权返回后继续；拒绝授权或取消安装可重试。FileProvider 只分享更新目录。普通 Android 应用仍需系统确认安装，不能保证静默升级。校验失败或签名不同会阻止安装。

API 依据：[GitHub Releases](https://docs.github.com/en/rest/releases/releases)、[GitHub CLI release create](https://cli.github.com/manual/gh_release_create)、[Android 安装来源权限](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls())、[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)。
