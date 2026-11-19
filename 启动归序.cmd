@echo off
rem 用途：设置仓库工作目录和可用的 JDK 21 环境后，转发到 scripts\start.ps1。
rem 输入：继承当前环境；接受已设置或可自动发现的 JAVA_HOME，并转发命令行参数。
rem       若当前目录包含发布包 maven-repository，无参数启动默认使用 Maven 离线模式和 2 路模块并行。
rem 副作用：启动前台 PowerShell 启动链，可能触发构建、虚拟环境和本地服务进程。
rem 安全边界：本启动器不提升权限、不访问应用工作区之外的业务目录；JDK 主版本仍由 PowerShell 脚本复核。
rem 错误传播：保存转发命令的 ERRORLEVEL，非零时暂停以便查看，再以相同退出码结束。

setlocal
cd /d "%~dp0"

if not defined JAVA_HOME (
  for /d %%J in ("C:\Program Files\Java\jdk-21*" "C:\Program Files\Eclipse Adoptium\jdk-21*" "%USERPROFILE%\.jdks\*21*") do (
    if not defined JAVA_HOME set "JAVA_HOME=%%~fJ"
  )
)

if not defined JAVA_HOME (
  echo [Orderly] JDK 21 was not found. Set JAVA_HOME to a JDK 21 installation and retry.
  pause
  exit /b 1
)

if not "%~1"=="" goto forward_args
if not exist "%~dp0maven-repository" goto start_online

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start.ps1" -MavenOffline -MaxParallel 2
goto capture_exit

:forward_args
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start.ps1" %*
goto capture_exit

:start_online
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start.ps1"

:capture_exit
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" pause
exit /b %EXIT_CODE%
