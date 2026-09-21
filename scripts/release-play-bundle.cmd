@echo off
setlocal EnableExtensions

cd /d "%~dp0\.."

echo.
echo == Open Dash Play release bundle check ==
echo Repo: %CD%
echo.

if not exist "gradlew.bat" (
    echo ERROR: gradlew.bat was not found. Run this script from the repository checkout.
    exit /b 1
)

if not exist "scripts\smoke-local.cmd" (
    echo ERROR: scripts\smoke-local.cmd was not found. Run Phase 6 smoke setup first.
    exit /b 1
)

echo [1/2] Running local smoke checks...
call scripts\smoke-local.cmd
if errorlevel 1 (
    echo ERROR: local smoke checks failed. Release bundle build was not attempted.
    exit /b 1
)

echo.
echo [2/2] Building Play release bundle...
call gradlew.bat :app:bundlePlayRelease --no-daemon
if errorlevel 1 (
    echo ERROR: Play release bundle build failed.
    echo Expected output if successful: app\build\outputs\bundle\playRelease\app-play-release.aab
    exit /b 1
)

echo.
echo == Open Dash Play release bundle build completed ==
echo Expected output: app\build\outputs\bundle\playRelease\app-play-release.aab
exit /b 0
