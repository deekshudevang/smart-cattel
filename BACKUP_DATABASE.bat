@echo off
setlocal
cd /d "%~dp0"

echo ==========================================
echo Smart Cattle - Database Backup
echo ==========================================

for /f "tokens=2-4 delims=/ " %%a in ('date /t') do (set mydate=%%c%%a%%b)
for /f "tokens=1-2 delims=/:" %%a in ("%time%") do (set mytime=%%a%%b)
set mytime=%mytime: =0%
set TIMESTAMP=%mydate%_%mytime%

if not exist "backups" mkdir backups

if exist "backend\smart_cattle.db" (
    copy "backend\smart_cattle.db" "backups\smart_cattle_backup_%TIMESTAMP%.db" >nul
    echo [OK] Backed up backend\smart_cattle.db to backups\smart_cattle_backup_%TIMESTAMP%.db
) else if exist "smart_cattle.db" (
    copy "smart_cattle.db" "backups\smart_cattle_backup_%TIMESTAMP%.db" >nul
    echo [OK] Backed up smart_cattle.db to backups\smart_cattle_backup_%TIMESTAMP%.db
) else (
    echo [WARN] No SQLite database file found to back up.
)

echo Done.
pause
