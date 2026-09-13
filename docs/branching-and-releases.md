# 分支管理与 GitHub Release

## 分支职责

| 分支 | 职责 | PR 目标 |
| --- | --- | --- |
| `feature/*`、`fix/*`、`docs/*` | 从 staging 创建，开发与验证单项修改 | `staging` |
| `staging` | 汇总通过检查的 PR，集成与发布准备 | 准备发布时提交到 `master` |
| `master` | 正式版本源代码；合入后由 GitHub Actions 构建并发布 | 发布后将必要修复同步回 `staging` |

普通功能不直接推送到 master。每次 staging → master 合并都代表一次发布，应先完成版本修改与验证。发布 PR 使用 **merge commit**，保持长期分支的共同历史；不要对 staging → master 使用 squash/rebase merge。紧急修复从 master 创建 `hotfix/*`，优先经 staging 验证再发布；紧急直接进入 master 时，也必须通过 PR、递增版本并随后同步回 staging。

首次建立 staging（仅在远端尚不存在时）：

```powershell
git fetch origin
git switch -c staging origin/master
git push -u origin staging
git switch -c feature/your-change staging
```

建议在 GitHub Rulesets 中对 staging/master 要求 PR、评审、必需检查，禁止 force-push 和删除；master 的 PR 约定只接受 staging（紧急 hotfix 除外）。规则设置属于仓库管理配置，本文件不会自动启用服务端保护。可将默认分支设为 staging，减少 PR 目标选错；启用规则时从一次真实运行中选择对应的 CI 检查名称。

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
2. 创建 GitHub Environment `release`，建议将 deployment branches 限制为 `master`；可按团队需要配置 required reviewers。
3. 使用长期保管的正式签名 keystore，添加以下 Environment Secrets：

| Secret | 内容 |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | keystore 文件的 Base64 内容 |
| `RELEASE_STORE_PASSWORD` | keystore 密码 |
| `RELEASE_KEY_ALIAS` | 签名密钥别名 |
| `RELEASE_KEY_PASSWORD` | 私钥密码 |

签名文件仅在 runner 临时目录中解码，构建结束和失败时清理；不要上传为构建 artifact。所有正式版本必须使用相同签名，本版更新器不支持签名轮换。原有 debug 安装使用不同证书，无法直接覆盖为正式版；不要通过卸载来“修复”签名错误而丢失用户数据，应提前安排首次正式版安装与数据处理。

GitHub Actions 的 `GITHUB_TOKEN` 仅在发布 job 获得 `contents: write`；PR 检查不使用签名 Secrets。工作流不创建私钥，缺失配置会失败。

## 日常开发与发布 CLI

安装 GitHub CLI 并执行 `gh auth login`。以下命令中的版本与 PR 编号按实际结果填写：

```powershell
# 开发完成后创建普通 PR
gh pr create --base staging --head feature/your-change --title "描述最终变化" --body-file pr-body.md
gh pr checks <PR编号> --watch

# 可手动构建并验证 staging
gh workflow run android-ci.yml --ref staging
gh run list --workflow android-ci.yml --branch staging --limit 5
gh run watch <run-id> --exit-status

# 在 staging 的准备 PR 中更新 version.properties、发布说明与验证结果后
gh pr create --base master --head staging --title "Release v0.2.0" --body-file release-notes.md
gh pr checks <发布PR编号> --watch
gh pr merge <发布PR编号> --merge

# master push 自动启动发布；以下命令可跟踪结果
gh run list --workflow release.yml --branch master --limit 5
gh run watch <release-run-id> --exit-status
gh release view v0.2.0
gh release download v0.2.0 --pattern "fileaccess-*.apk" --pattern SHA256SUMS --dir release-download
```

`.github/workflows/release.yml` 先调用 `android-ci.yml`，运行 Debug 构建、单元测试和 Lint；随后检查公开可见性、版本递增和标签指向，再构建 release、执行 release Lint 与 apksigner 验证，通过 `gh release create/upload/edit` 发布。CI 覆盖 staging push、所有 PR 和手动执行；master push 的检查包含在 release workflow 中。

失败时修复配置后可 `gh run rerun <run-id> --failed`，或 `gh workflow run release.yml --ref master`。手动发布只允许 master，不能从开发分支发布；已公开版本不能通过重跑覆写。源码变更应发布新版本，不能把旧草稿标签改指向另一 commit。

发布完成后检查 Release 的版本、APK、SHA256SUMS，并在同签名旧版本上验证覆盖升级、用户数据保留、拒绝安装权限及取消安装后的重试。真实 SMB 测试与 Android 设备测试仍需隔离环境，默认 CI 不代替这些验收。

## App 更新交互与边界

默认在启动/回到前台时检查，每 24 小时最多自动请求一次（包括失败尝试），设置页可关闭或手动重试。无网络、限流、未公开仓库及无 Release 会在设置页显示原因，不阻断文件操作。不会在 App 未运行时定时唤醒。

检测到新版本后显示发布说明，用户点击下载，显示进度并可取消。APK 上限 256 MiB，HTTPS 来源限制为本仓库及 GitHub 资产域名，保存于私有 `files/updates/`；失败或取消删除临时文件，下一次下载清理旧版本缓存。下载在当前进程中执行；进程被终止后从头重试，已完成文件会重新校验后复用。

下载完成点击安装；首次进入“允许安装未知应用”设置，授权返回后继续；拒绝授权或取消安装可重试。FileProvider 只分享更新目录。普通 Android 应用仍需系统确认安装，不能保证静默升级。校验失败或签名不同会阻止安装。

API 依据：[GitHub Releases](https://docs.github.com/en/rest/releases/releases)、[GitHub CLI release create](https://cli.github.com/manual/gh_release_create)、[Android 安装来源权限](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls())、[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)。
