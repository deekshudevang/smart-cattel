@echo off
title Smart Cattle - Install Android App
cd /d "%~dp0"

echo ======================================================================
echo             Smart Cattle Health Monitoring - App Installer
echo ======================================================================

set "APK_PATH=%~dp0smart-cattle-update.apk"
if not exist "%APK_PATH%" (
    set "APK_PATH=%~dp0android\SmartCattle\app\build\outputs\apk\debug\app-debug.apk"
)

if not exist "%APK_PATH%" (
    echo [INFO] APK not found. Building APK with Gradle...
    cd /d "%~dp0android\SmartCattle"
    call gradlew.bat assembleDebug
    cd /d "%~dp0"
    set "APK_PATH=%~dp0android\SmartCattle\app\build\outputs\apk\debug\app-debug.apk"
)

if not exist "%APK_PATH%" (
    echo [ERROR] Failed to locate or build APK!
    pause
    exit /b 1
)

:: Detect Local LAN IP for Wi-Fi download option
set "LAN_IP=127.0.0.1"
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /c:"IPv4 Address"') do (
    set "LAN_IP=%%a"
    goto :ip_found
)
:ip_found
set "LAN_IP=%LAN_IP: =%"

:: Locate adb executable
set "ADB_EXE="
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
    set "ADB_EXE=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
) else (
    where adb >nul 2>&1
    if not errorlevel 1 set "ADB_EXE=adb"
)

echo.
echo Searching for connected Android phone via USB/Wi-Fi ADB...
set "DEVICE_FOUND=0"

if not "%ADB_EXE%"=="" (
    for /L %%i in (1,1,10) do (
        for /f "tokens=2" %%d in ('"%ADB_EXE%" devices ^| findstr /v "List of" ^| findstr "device"') do (
            set "DEVICE_FOUND=1"
            goto :device_ready
        )
        <nul set /p=.
        timeout /t 1 /nobreak >nul
    )
)

:device_ready
echo.

if "%DEVICE_FOUND%"=="1" (
    echo [SUCCESS] Android phone detected!
    echo.
    echo [1/2] Installing updated Smart Cattle APK with Steps ^& Total Steps...
    "%ADB_EXE%" install -r -d "%APK_PATH%"
    if errorlevel 1 (
        echo [INFO] Performing fresh install...
        "%ADB_EXE%" uninstall com.example.smartcattle >nul 2>&1
        "%ADB_EXE%" install "%APK_PATH%"
    )
    if not errorlevel 1 (
        echo.
        echo [2/2] Launching Smart Cattle App on your phone...
        "%ADB_EXE%" shell am start -n com.example.smartcattle/.MainActivity >nul 2>&1
        echo.
        echo ======================================================================
        echo   SUCCESS: App installed and launched on your phone!
        echo ======================================================================
        pause
        exit /b 0
    )
)

echo.
echo ----------------------------------------------------------------------
echo  NO PHONE CONNECTED VIA USB DEBUGGING. CHOOSE AN INSTALL METHOD:
echo ----------------------------------------------------------------------
echo.
echo  OPTION A: 1-Tap Wireless Install (No Cables, Works Immediately!)
echo    1. On your phone, open Chrome or your mobile browser.
echo    2. Visit: http://%LAN_IP%:8000/
echo       (or directly download: http://%LAN_IP%:8000/app.apk)
echo    3. Tap Download and open the file to install the update!
echo.
echo  OPTION B: USB Cable Direct Install
echo    1. Connect your phone to this PC with a USB cable.
echo    2. On your phone, go to Settings -^> Developer Options -^> Enable 'USB Debugging'.
echo    3. Re-run this INSTALL_APP.bat script.
echo.
echo  OPTION C: Manual Copy
echo    A File Explorer window is opening with 'smart-cattle-update.apk'.
echo    Copy it to your phone via USB or WhatsApp Web and tap to install.
echo ----------------------------------------------------------------------
echo.

explorer /select,"%APK_PATH%"
pause
