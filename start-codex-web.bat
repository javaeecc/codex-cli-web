@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%backend"

if not exist "target\codex-web.jar" (
    echo [ERROR] target\codex-web.jar not found.
    echo Please run: cd backend ^&^& mvn clean package
    exit /b 1
)

echo Starting Codex Web at http://127.0.0.1:9000/
java -jar "target\codex-web.jar" --server.port=9000

endlocal
