# Orderly 后端骨架

这是 Orderly 控制层的最小 Spring Boot 3 / Java 21 工程。当前只包含平台模块的注册、发现和一个无副作用的测试模块，不包含文件操作、AI 调用、任务执行或系统设置能力。

运行（推荐使用工作区根目录的启动脚本）：

```powershell
.\run.ps1
```

启动后访问 `http://127.0.0.1:8080/` 查看集成后的主界面，模块目录接口为 `GET /api/platform/modules`。

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
2. 如模块确实需要处理受控命令，再单独实现 `ModuleHandler`。
3. 在模块内部完成参数校验与能力边界校验。
4. 涉及文件或系统动作时，先补齐确认、权限、快照、审计和回滚设计，再开放调度入口。
