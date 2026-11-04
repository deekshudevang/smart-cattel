@echo off
title Smart Cattle Health Monitoring - Backend Server
cd /d "%~dp0"

echo ======================================================================
echo           Smart Cattle Health Monitoring - Backend Server
echo ======================================================================

:: 1. Locate Python Interpreter in Virtual Environment
if exist "%~dp0venv\Scripts\python.exe" (
    set "PYTHON_EXE=%~dp0venv\Scripts\python.exe"
) else if exist "%~dp0backend\venv\Scripts\python.exe" (
    set "PYTHON_EXE=%~dp0backend\venv\Scripts\python.exe"
) else (
    where python >nul 2>&1
    if not errorlevel 1 (
        set "PYTHON_EXE=python"
    ) else (
        where py >nul 2>&1
        if not errorlevel 1 (
            set "PYTHON_EXE=py"
        ) else (
            echo [ERROR] Python environment not found!
            echo Please ensure Python or venv is installed.
            pause
            exit /b 1
        )
    )
)

:: 2. Fast Dependency Check (Starts in 1 second; skips pip if already installed)
"%PYTHON_EXE%" -c "import uvicorn, fastapi, pydantic, serial" >nul 2>&1
if errorlevel 1 (
    echo [INFO] Installing/verifying required dependencies...
    if exist "%~dp0requirements.txt" (
        "%PYTHON_EXE%" -m pip install -r "%~dp0requirements.txt"
    ) else if exist "%~dp0backend\requirements.txt" (
        "%PYTHON_EXE%" -m pip install -r "%~dp0backend\requirements.txt"
    )
    if errorlevel 1 (
        echo [ERROR] Dependency installation failed!
        pause
        exit /b %errorlevel%
    )
)

:: 3. Free Port 8000 if occupied by a lingering/orphaned server
for /f "tokens=5" %%p in ('netstat -ano ^| findstr :8000 ^| findstr LISTENING') do (
    echo [INFO] Freeing occupied port 8000 for PID %%p...
    taskkill /F /PID %%p >nul 2>&1
    timeout /t 1 /nobreak >nul
)

:: 4. Free Serial Port if occupied by Arduino IDE Serial Monitor
tasklist /FI "IMAGENAME eq serial-monitor.exe" 2>nul | findstr /i "serial-monitor.exe" >nul
if not errorlevel 1 (
    echo [INFO] Arduino IDE Serial Monitor was locking the serial port.
    echo Freeing serial port for backend telemetry...
    taskkill /F /IM serial-monitor.exe >nul 2>&1
)

:: 5. Detect Local LAN IP for Android Configuration
set "LAN_IP=127.0.0.1"
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /c:"IPv4 Address"') do (
    set "LAN_IP=%%a"
    goto :ip_found
)
:ip_found
set "LAN_IP=%LAN_IP: =%"

echo.
echo ----------------------------------------------------------------------
echo  Server Ready for 1-Click Connection:
echo    * Local Dashboard:  http://localhost:8000
echo    * Swagger API Docs: http://localhost:8000/docs
echo    * Android App IP:   %LAN_IP%
echo    * App Base URL:     http://%LAN_IP%:8000/
echo    * WebSocket URL:    ws://%LAN_IP%:8000/ws/cattle/CATTLE-ALL
echo ----------------------------------------------------------------------
echo.
echo Starting FastAPI and WebSocket Server on 0.0.0.0:8000...
echo [Press Ctrl+C in this window to stop the server]
echo.

cd /d "%~dp0backend"
"%PYTHON_EXE%" -m uvicorn app.main:app --host 0.0.0.0 --port 8000

if %errorlevel% neq 0 (
    echo.
    echo [ERROR] Server exited with error code %errorlevel%.
    pause
)
