# Orderly 主程序目录

## 可运行应用

- `backend/`：Spring Boot 3、Java 21 控制层。
- `frontend/`：原生 HTML/CSS/JavaScript 界面。
- `contracts/`：Java 与前端共同验证的固定公共契约样例。
- `run.ps1`：本地启动与测试打包入口。

启动：

```powershell
.\run.ps1
```

访问 `http://127.0.0.1:8080/`。

## 工具边界

`positioning.py` 和 `test_positioning.py` 是代理工作区、分支与远程操作边界的检查工具，不是 Orderly 应用运行时模块，也不是 Java 接口测试实现。它们保留在主程序目录根部，仅作为独立工具使用，不参与 Maven 构建或应用启动，也不参与 Java 接口测试执行。

在有 Python 3 的环境中，可单独运行：

```powershell
python -m unittest test_positioning.py
```

Python 只承担该独立边界工具及其测试，不执行或替代本目录的 Java 接口测试。
