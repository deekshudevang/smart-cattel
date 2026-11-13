@echo off
title Smart Cattle Health Monitoring - Backend Server
cd /d "%~dp0"

echo ======================================================================
echo           Smart Cattle Health Monitoring - Backend Server
echo ======================================================================

:: 1. Locate a working Python Interpreter (with automatic recovery from copied venvs)
set "PYTHON_EXE="
set "SYSTEM_PYTHON="

:: Check system python
where python >nul 2>&1
if not errorlevel 1 (
    python -V >nul 2>&1
    if not errorlevel 1 set "SYSTEM_PYTHON=python"
)
if not defined SYSTEM_PYTHON (
    where py >nul 2>&1
    if not errorlevel 1 (
        py -3 -V >nul 2>&1
        if not errorlevel 1 set "SYSTEM_PYTHON=py -3"
    )
)

:: Check root venv, and verify it can run scripts and pip on THIS machine
if exist "%~dp0venv\Scripts\python.exe" (
    "%~dp0venv\Scripts\python.exe" -c "import sys, encodings" >nul 2>&1
    if errorlevel 1 (
        echo [WARN] Stale/copied virtual environment detected from another PC.
        echo Rebuilding clean local virtual environment for this machine...
        rd /s /q "%~dp0venv" >nul 2>&1
    ) else (
        "%~dp0venv\Scripts\python.exe" -m pip --version >nul 2>&1
        if errorlevel 1 (
            echo [WARN] Incompatible pip detected in copied venv.
            echo Rebuilding clean local virtual environment for this machine...
            rd /s /q "%~dp0venv" >nul 2>&1
        ) else (
            set "PYTHON_EXE=%~dp0venv\Scripts\python.exe"
        )
    )
)

:: Check backend\venv if root venv not used
if not defined PYTHON_EXE (
    if exist "%~dp0backend\venv\Scripts\python.exe" (
        "%~dp0backend\venv\Scripts\python.exe" -c "import sys, encodings" >nul 2>&1
        if errorlevel 1 (
            echo [WARN] Stale/copied backend\venv detected from another PC.
            echo Rebuilding clean local virtual environment for this machine...
            rd /s /q "%~dp0backend\venv" >nul 2>&1
        ) else (
            "%~dp0backend\venv\Scripts\python.exe" -m pip --version >nul 2>&1
            if errorlevel 1 (
                echo [WARN] Incompatible pip detected in copied backend\venv.
                echo Rebuilding clean local virtual environment for this machine...
                rd /s /q "%~dp0backend\venv" >nul 2>&1
            ) else (
                set "PYTHON_EXE=%~dp0backend\venv\Scripts\python.exe"
            )
        )
    )
)

:: If no valid venv, create one using system python or use system python directly
if not defined PYTHON_EXE (
    if defined SYSTEM_PYTHON (
        echo [INFO] Setting up local virtual environment for this computer...
        %SYSTEM_PYTHON% -m venv "%~dp0venv"
        if exist "%~dp0venv\Scripts\python.exe" (
            set "PYTHON_EXE=%~dp0venv\Scripts\python.exe"
        ) else (
            set "PYTHON_EXE=%SYSTEM_PYTHON%"
        )
    ) else (
        echo ======================================================================
        echo [ERROR] Python environment not found on this computer!
        echo Please install Python 3.10+ from https://www.python.org/
        echo IMPORTANT: Check the box "Add python.exe to PATH" during installation.
        echo ======================================================================
        pause
        exit /b 1
    )
)

:: Auto-initialize .env configuration if missing
if not exist "%~dp0.env" (
    if exist "%~dp0.env.example" (
        copy "%~dp0.env.example" "%~dp0.env" >nul
        echo [INFO] Initialized root .env from .env.example
    )
)
if not exist "%~dp0backend\.env" (
    if exist "%~dp0.env" (
        copy "%~dp0.env" "%~dp0backend\.env" >nul
    ) else if exist "%~dp0.env.example" (
        copy "%~dp0.env.example" "%~dp0backend\.env" >nul
    )
    echo [INFO] Initialized backend\.env configuration
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
echo    * Health Check:     http://%LAN_IP%:8000/health
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
"%PYTHON_EXE%" -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload

if %errorlevel% neq 0 (
    echo.
    echo [ERROR] Server exited with error code %errorlevel%.
    pause
)
