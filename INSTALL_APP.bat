@echo off
title Smart Cattle - Install Android App
cd /d "%~dp0"

echo ======================================================================
echo             Smart Cattle Health Monitoring - App Installer
echo ======================================================================

set "APK_PATH=%~dp0android\SmartCattle\app\build\outputs\apk\debug\app-debug.apk"

if not exist "%APK_PATH%" (
    echo [INFO] APK not found. Building APK with Gradle...
    cd /d "%~dp0android\SmartCattle"
    call gradlew.bat assembleDebug
    cd /d "%~dp0"
)

if not exist "%APK_PATH%" (
    echo [ERROR] Failed to locate or build app-debug.apk!
    pause
    exit /b 1
)

:: Locate adb executable
set "ADB_EXE="
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
    set "ADB_EXE=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
) else (
    where adb >nul 2>&1
    if not errorlevel 1 set "ADB_EXE=adb"
)

if "%ADB_EXE%"=="" (
    echo.
    echo [WARNING] ADB tool not found in standard paths.
    echo Opening folder containing app-debug.apk...
    explorer /select,"%APK_PATH%"
    echo.
    echo To install on your phone:
    echo  1. Copy 'app-debug.apk' to your phone via USB or WhatsApp/Drive.
    echo  2. Open the file on your phone and tap Install.
    pause
    exit /b 0
)

echo.
echo [1/3] Checking connected Android devices via ADB...
"%ADB_EXE%" devices

"%ADB_EXE%" get-state >nul 2>&1
if errorlevel 1 (
    echo.
    echo [NOTICE] No Android device currently detected over USB/Wi-Fi ADB.
    echo.
    echo To install automatically:
    echo   1. Connect your phone via USB cable to this PC.
    echo   2. On your phone, enable Developer Options and USB Debugging.
    echo   3. When prompted on phone, tap 'Allow USB debugging'.
    echo   4. Re-run this INSTALL_APP.bat script.
    echo.
    echo In the meantime, opening the APK folder so you can transfer it manually:
    explorer /select,"%APK_PATH%"
    echo.
    pause
    exit /b 0
)

echo.
echo [2/3] Installing updated Smart Cattle APK with Steps & Total Steps to your phone...
"%ADB_EXE%" install -r -d "%APK_PATH%"
if errorlevel 1 (
    echo.
    echo [WARNING] Standard install failed. Attempting uninstall and reinstall...
    "%ADB_EXE%" uninstall com.example.smartcattle >nul 2>&1
    "%ADB_EXE%" install "%APK_PATH%"
)

if errorlevel 1 (
    echo [ERROR] Installation failed. Please check phone screen for permissions.
    pause
    exit /b 1
)

echo.
echo [3/3] Launching Smart Cattle App on your phone...
"%ADB_EXE%" shell am start -n com.example.smartcattle/.MainActivity >nul 2>&1

echo.
echo ======================================================================
echo    SUCCESS! Updated Smart Cattle App installed & launched on your phone!
echo ======================================================================
echo.
pause
