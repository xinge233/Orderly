# Orderly 后端骨架

这是 Orderly 控制层的最小 Spring Boot 3 / Java 21 工程。当前只包含平台模块的注册、发现和一个无副作用的测试模块，不包含文件操作、AI 调用、任务执行或系统设置能力。

运行（推荐使用工作区根目录的启动脚本）：

```powershell
.\run.ps1
```

启动后访问 `http://127.0.0.1:8080/` 查看集成后的主界面，模块目录接口为 `GET /api/platform/modules`。

模块目录使用独立的版本化 API DTO，不直接序列化内部 `ModuleDescriptor`。当前契约版本为 `1.0`，固定样例位于 `../contracts/module-catalog.sample.json`。

只执行测试和打包：

```powershell
.\run.ps1 -Test
```

如果本机 Maven 用户仓库可正常使用，也可以在 `backend` 目录执行：

```powershell
mvn spring-boot:run
```

新增模块时：

1. 实现 `PlatformModule` 提供稳定元数据。
2. 如模块确实需要处理受控动作，再实现 `ModuleHandler.actions()`，动作定义是唯一能力来源。
3. 不在模块中实现独立入口；所有执行必须经过 `ExecutionPipeline`。
4. 管线固定执行校验、确认、工作区授权、快照准备、模块执行、审计和失败回滚。
5. 当前骨架没有 HTTP 执行端点，也没有真实文件、Shell、AI 或任务状态能力。

当前默认安全策略：

- 写入与破坏性动作必须声明需要确认，否则应用启动失败。
- 没有服务端确认存储时，需要确认的动作一律拒绝。
- 只有 `WorkspaceAccess.NONE` 的动作可以在当前骨架中通过边界检查。
- 执行许可由中央管线签发并只能消费一次。
