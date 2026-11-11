@echo off
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

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start.ps1"
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" pause
exit /b %EXIT_CODE%
