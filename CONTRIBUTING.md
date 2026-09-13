# 参与开发

本仓库为私有仓库，仅获授权的协作者可以访问。代码采用 [MPL-2.0](LICENSE)；仓库可见性与代码许可分别管理，添加许可证不会将仓库设为公开。提交内容应有权以仓库许可证提供；引入第三方代码时保留其许可与署名，并说明来源。

## 开发约定

先阅读 [分支与发布规范](docs/branching-and-releases.md)、[开发说明](docs/development.md)、[设计入口](docs/design/README.md) 和 [当前实现边界](docs/implementation-status.md)。普通 PR 合入 `staging`，正式发布通过 `staging → master` PR 触发 GitHub Actions。首阶段聚焦 SMB 和 Android 16；其他协议通过 `core:storage-api` 的能力接口接入。

- 使用 Kotlin、Compose、ViewModel / Flow 和现有依赖注入方式；让 UI 状态、持久化、传输调度和协议实现保持模块边界。
- 在协议层处理远端路径、版本核对和资源释放，不把 SMBJ 类型、密码或网络会话传入 UI。
- 修改传输或备份逻辑时考虑取消、重试、提交结果未知、同名冲突和进程恢复；成功基线只能在确认传输成功后更新。
- Room 实体变化需同步提交 `core/data/schemas`，明确数据库迁移行为并验证已有数据的升级路径。
- 依赖版本统一维护在 `gradle/libs.versions.toml`；更新依赖时审阅并更新 Gradle 校验元数据，不能关闭校验来绕过失败。
- 遵循 `.editorconfig`。仓库未配置强制格式化工具，避免在功能修改中混入无关的全文件格式化。

## 验证与提交

围绕行为变化增加必要的测试。普通修改至少构建受影响模块；传输、持久化、安全或权限变化应运行对应单元测试和 Android 测试。构建、Lint、设备测试的完整命令见 [开发说明](docs/development.md)。SMB 集成测试只使用 [隔离测试共享](protocol/smb/README.md)，不要让自动化测试连接存有个人文件的 NAS。缺少测试服务导致的跳过应明确记录。

提交和 PR 描述说明问题、最终行为、验证方式及剩余限制。涉及可见交互时附上脱敏截图；涉及行为边界或构建方式时同步更新文档。提交信息使用能表达意图的简短描述即可。

不要提交账号密码、真实 NAS 地址与目录清单、个人媒体、签名材料、`local.properties`、生成的构建输出或未脱敏日志。测试凭据必须只适用于临时隔离服务。安全问题的报告方式见 [SECURITY.md](SECURITY.md)。
