@echo off
REM ============================================================
REM  SoftEthergai emergency CSV publisher - operator wrapper
REM  (BLOCK v1.0.75 operator convenience; the real tool is
REM   emergency_csv_publisher.py - OPERATOR-ONLY, never runs in the app)
REM
REM  Usage:
REM    1.bat                    full publish: fetch -> validate -> health-check
REM                             -> write CSV + emergency_defaults.xml -> commit + push
REM    1.bat --dry-run          write files locally, skip git
REM    1.bat --no-push          commit locally but do not push
REM    1.bat --max-servers 400  any argparse flag is passed through
REM    1.bat --help             all arguments
REM ============================================================
setlocal
cd /d "%~dp0"

where py >nul 2>nul && (set "PY=py") || (set "PY=python")
echo Running: %PY% emergency_csv_publisher.py %*
echo.
%PY% emergency_csv_publisher.py %*
set "RC=%ERRORLEVEL%"
echo.
echo Exit code: %RC%
if "%1"=="" pause
exit /b %RC%
