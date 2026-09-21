@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0\.."

echo.
echo == Open Dash local smoke checks ==
echo Repo: %CD%
echo.

if not exist "gradlew.bat" (
    echo ERROR: gradlew.bat was not found. Run this script from the repository checkout.
    exit /b 1
)

echo [1/4] Building local debug APK...
call gradlew.bat :app:assembleLocalDebug --no-daemon
if errorlevel 1 (
    echo ERROR: local debug build failed.
    exit /b 1
)

echo.
echo [2/4] Running local debug unit tests...
call gradlew.bat :app:testLocalDebugUnitTest --no-daemon
if errorlevel 1 (
    echo ERROR: local debug unit tests failed.
    exit /b 1
)

echo.
echo [3/4] Checking Home/Route Connect permission scope...
where rg >nul 2>nul
if errorlevel 1 (
    echo ERROR: ripgrep ^(rg^) is required for the static permission smoke checks.
    echo Install rg or manually verify HomeScreen.kt and RouteScreen.kt before release work.
    exit /b 1
)

set "SCREEN_FILES=app\src\main\java\com\example\opendash\ui\screens\HomeScreen.kt app\src\main\java\com\example\opendash\ui\screens\RouteScreen.kt"

for %%P in (POST_NOTIFICATIONS ANSWER_PHONE_CALLS CALL_PHONE READ_PHONE_STATE READ_CALL_LOG) do (
    rg -n "%%P" %SCREEN_FILES% >nul
    set "RG_EXIT=!ERRORLEVEL!"
    if "!RG_EXIT!"=="0" (
        echo ERROR: optional permission %%P appears in a Home/Route Connect flow file.
        rg -n "%%P" %SCREEN_FILES%
        exit /b 1
    )
    if not "!RG_EXIT!"=="1" (
        echo ERROR: rg failed while checking %%P.
        exit /b !RG_EXIT!
    )
)

echo OK: Home/Route Connect flow files do not reference optional phone, call-log, or notification permissions.

echo.
echo [4/4] Checking Route phone permission copy...
rg -n "More > Media & calls" app\src\main\java\com\example\opendash\ui\screens\RouteScreen.kt >nul
if errorlevel 1 (
    echo ERROR: Route phone permission copy no longer points to More ^> Media ^& calls.
    exit /b 1
)

echo OK: Route phone permission copy points to More ^> Media ^& calls.
echo.
echo == Open Dash local smoke checks passed ==
exit /b 0
