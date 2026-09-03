@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%"

echo Pulling the latest code from Git...
git pull --ff-only
if errorlevel 1 (
    echo [ERROR] Git pull failed. Resolve the repository state and try again.
    exit /b 1
)

echo Building the application...
pushd "backend"
call mvn clean package
if errorlevel 1 (
    popd
    echo [ERROR] Maven build failed.
    exit /b 1
)
popd

if not exist "backend\target\codex-web.jar" (
    echo [ERROR] backend\target\codex-web.jar not found after the build.
    exit /b 1
)

echo Stopping the existing service on port 9000...
for /f "tokens=5" %%P in ('netstat -ano ^| findstr /R /C:":9000 .*LISTENING"') do taskkill /F /PID %%P >nul 2>&1
timeout /t 2 /nobreak >nul

echo Starting Codex Web at http://127.0.0.1:9000/
cd /d "%SCRIPT_DIR%backend"
java -jar "target\codex-web.jar" --server.port=9000

endlocal
