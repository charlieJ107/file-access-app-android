# 面向 Agent 的项目指引

先阅读 [分支与发布规范](docs/branching-and-releases.md)、[开发说明](docs/development.md) 和 [参与开发](CONTRIBUTING.md)。分支和发布的具体规则以 `docs/branching-and-releases.md` 为准。

- 普通开发从 `staging` 创建 `feature/*`、`fix/*` 或 `docs/*` 分支，PR 默认目标是 `staging`。仓库初次迁移且尚无 `staging` 时，先从当前 `master` 建立基线，不丢弃工作区修改。
- `master` 仅作为发布分支；正常发布只提交 `staging → master` PR。不要把普通功能 PR 直接合入 `master`。
- 开始工作先检查 `git status`、当前分支和已有工作树；保护用户未提交的修改。不要自动 force-push 或移动已发布标签。
- `version.properties` 是版本唯一来源。新版本按 Semantic Versioning 递增 `versionName`；同时递增 Android `versionCode` 以支持覆盖安装。不能用 `versionCode` 代替 SemVer 判断新版本。
- 使用 `gh workflow run` / `gh run watch` 操作 CI；正式构建和 GitHub Release 由 `.github/workflows/release.yml` 在 `master` 上执行。本机 APK 只用于验证，不作为正式发布制品。
- 修改更新协议时同步检查 App、发布脚本、资产命名、SHA-256、签名校验和文档；保留稳定版过滤、HTTPS 与下载边界。
- 不在 APK、源码或日志中放入 GitHub Token、签名私钥或真实 NAS 凭据。签名只读取环境变量和 GitHub Secrets。
- 运行与修改相称的构建、测试和 Lint，报告实际执行结果；CI 尚未运行、缺少签名、未连接设备等情况要准确说明，不能写成已发布或已验证。

可复用任务提示：

> 请按 `AGENTS.md` 和 `docs/branching-and-releases.md` 处理此任务。先检查工作区与分支，从 staging 创建开发分支，完成实现和必要测试，准备合入 staging 的 PR。若任务是发布，先核对 SemVer、versionCode、公开更新源与签名配置，准备 staging 到 master 的发布 PR；合并后使用 GitHub CLI 跟踪 release workflow，并验证 Release APK 与 SHA-256。遵循本次用户已明确的提交、推送、合并和发布授权范围。
