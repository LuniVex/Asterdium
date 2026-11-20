@echo off
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Run-Vulkan-Smoke-Windows.ps1"
set "ASTERDIUM_EXIT=%ERRORLEVEL%"
echo.
if not "%ASTERDIUM_EXIT%"=="0" echo Asterdium Vulkan smoke test FAILED (exit %ASTERDIUM_EXIT%).
echo Press any key to close this window.
pause >nul
exit /b %ASTERDIUM_EXIT%
