@echo off
echo ==========================================
echo Smart Cattle Health Monitoring - Server
echo ==========================================

echo [1/2] Installing required Python packages...
pip install -r requirements.txt
if %errorlevel% neq 0 (
    echo Failed to install packages. Please make sure Python is installed.
    pause
    exit /b
)

echo.
echo [2/2] Starting the FastAPI Server...
echo Make sure your Arduino is plugged into COM4.
echo.
cd backend\app
python -m uvicorn main:app --host 0.0.0.0 --port 8000
pause
