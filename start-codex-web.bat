@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%backend"

if not exist "target\codex-web.jar" (
    echo [ERROR] target\codex-web.jar not found.
    echo Please run: cd backend ^&^& mvn clean package
    exit /b 1
)

echo Stopping the existing service on port 9000...
for /f "tokens=5" %%P in ('netstat -ano ^| findstr /R /C:":9000 .*LISTENING"') do taskkill /F /PID %%P >nul 2>&1
timeout /t 2 /nobreak >nul

echo Starting Codex Web at http://127.0.0.1:9000/
java -jar "target\codex-web.jar" --server.port=9000

endlocal
