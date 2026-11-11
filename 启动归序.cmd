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

set "HOST_JAR="
for /f "delims=" %%J in ('dir /b /a-d /o-d "%~dp0app\host\target\orderly-host-*.jar" 2^>nul ^| findstr /v /i "\.original$"') do (
  if not defined HOST_JAR set "HOST_JAR=%~dp0app\host\target\%%J"
)

if not defined HOST_JAR (
  echo [Orderly] Host JAR was not found.
  echo [Orderly] Run scripts\start.ps1 once to build the application, then retry this shortcut.
  pause
  exit /b 1
)

echo [Orderly] JAVA_HOME=%JAVA_HOME%
echo [Orderly] Starting http://127.0.0.1:8080/
"%JAVA_HOME%\bin\java.exe" "-Dorderly.modules-dir=%~dp0target\orderly-modules" "-Dorderly.themes-dir=%~dp0themes" -jar "%HOST_JAR%" --server.port=8080
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" pause
exit /b %EXIT_CODE%
