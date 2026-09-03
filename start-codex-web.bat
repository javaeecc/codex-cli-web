@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%"
for /f "delims=" %%B in ('git branch --show-current') do set "CURRENT_BRANCH=%%B"

echo Pulling the latest code from Git...
git pull --ff-only
if errorlevel 1 (
    git remote get-url gitee >nul 2>&1
    if errorlevel 1 (
        echo [ERROR] Git pull failed. Resolve the repository state or network access and try again.
        exit /b 1
    )
    echo [WARN] Pull from the configured upstream failed. Trying the gitee mirror...
    git pull --ff-only gitee "%CURRENT_BRANCH%"
    if errorlevel 1 (
        echo [ERROR] Git pull failed from both the upstream and gitee.
        echo Resolve the repository state or network access and try again.
        exit /b 1
    )
)

rem Optional overrides for machines where Node.js/Maven are not on PATH.
if defined CODEX_NODE_HOME if exist "%CODEX_NODE_HOME%\node.exe" set "PATH=%CODEX_NODE_HOME%;%PATH%"
if defined CODEX_NPM_HOME if exist "%CODEX_NPM_HOME%\npm.cmd" set "PATH=%CODEX_NPM_HOME%;%PATH%"
for %%D in ("%ProgramFiles%\nodejs" "%ProgramFiles(x86)%\nodejs" "%LOCALAPPDATA%\Programs\nodejs") do if exist "%%~D\npm.cmd" set "PATH=%%~D;%PATH%"

where mvn >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Maven was not found in PATH. Install Maven 3.6+ first.
    exit /b 1
)
where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java was not found in PATH. Install JDK 8 first.
    exit /b 1
)
where npm.cmd >nul 2>&1
if errorlevel 1 (
    echo [ERROR] npm.cmd was not found in PATH. Install Node.js 18+ first.
    exit /b 1
)

if not exist "%SCRIPT_DIR%frontend\node_modules\.bin\webpack.cmd" (
    echo Frontend dependencies not found. Installing from package-lock.json...
    pushd "frontend"
    call npm.cmd ci
    if errorlevel 1 (
        popd
        echo [ERROR] Frontend dependency installation failed.
        exit /b 1
    )
    popd
)

if not exist "%SCRIPT_DIR%frontend\node_modules\.bin\webpack.cmd" (
    echo [ERROR] Frontend dependencies are still missing after installation.
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
