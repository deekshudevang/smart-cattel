@echo off
setlocal
cd /d "%~dp0"

echo ==========================================
echo Smart Cattle Health Monitoring - Server
echo ==========================================

:: 1. Activate or set up Python virtual environment
if exist "%~dp0venv\Scripts\activate.bat" (
    call "%~dp0venv\Scripts\activate.bat"
) else if exist "%~dp0backend\venv\Scripts\activate.bat" (
    call "%~dp0backend\venv\Scripts\activate.bat"
) else (
    where py >nul 2>&1
    if not errorlevel 1 (
        echo Virtual environment not found. Creating venv using Python launcher...
        py -m venv "%~dp0venv"
        if exist "%~dp0venv\Scripts\activate.bat" call "%~dp0venv\Scripts\activate.bat"
    ) else if exist "%LocalAppData%\Python\pythoncore-3.14-64\python.exe" (
        echo Virtual environment not found. Creating venv...
        "%LocalAppData%\Python\pythoncore-3.14-64\python.exe" -m venv "%~dp0venv"
        if exist "%~dp0venv\Scripts\activate.bat" call "%~dp0venv\Scripts\activate.bat"
    )
)

:: Verify python is working (not the WindowsApps stub)
python -c "import sys" >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Python was not found or Windows App Execution Alias redirected it.
    echo Please ensure Python or venv is installed, or run using 'py' launcher.
    pause
    exit /b 1
)

echo.
echo [1/2] Checking and installing required Python packages...
python -m pip install -r "%~dp0requirements.txt"
if %errorlevel% neq 0 (
    echo [ERROR] Failed to install packages.
    pause
    exit /b %errorlevel%
)

echo.
echo [2/2] Starting the FastAPI Server...
echo Make sure your Arduino is plugged in.
echo.
cd /d "%~dp0backend"
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
pause

