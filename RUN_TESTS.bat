@echo off
setlocal
cd /d "%~dp0"

echo ==========================================
echo Smart Cattle - Running Full Test Suites
echo ==========================================

:: 1. Backend Pytest
echo [1/2] Running Backend Tests (pytest)...
cd /d "%~dp0backend"
if exist "venv\Scripts\pytest.exe" (
    venv\Scripts\pytest.exe -v
) else if exist "..\venv\Scripts\pytest.exe" (
    ..\venv\Scripts\pytest.exe -v
) else (
    pytest -v
)

if %errorlevel% neq 0 (
    echo [ERROR] Backend tests failed!
    pause
    exit /b %errorlevel%
)

:: 2. Android Unit Tests
echo.
echo [2/2] Running Android Unit Tests (Gradle)...
cd /d "%~dp0android\SmartCattle"
call gradlew.bat test

if %errorlevel% neq 0 (
    echo [ERROR] Android unit tests failed!
    pause
    exit /b %errorlevel%
)

echo.
echo ==========================================
echo ALL TESTS PASSED SUCCESSFULLY!
echo ==========================================
pause
