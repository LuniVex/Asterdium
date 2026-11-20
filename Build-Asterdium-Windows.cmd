@echo off
setlocal
cd /d "%~dp0"
title Asterdium - Minecraft 1.21.1 development build

where java >nul 2>nul
if errorlevel 1 (
  echo ERROR: Java 21 is required. Install a Java 21 JDK first.
  goto :failure
)

echo.
echo ===============================================
echo  Asterdium 0.0.1 - Vulkan renderer research
echo  Minecraft 1.21.1 / Fabric / Java 21
echo ===============================================
echo.
echo This first build is a CPU chunk mesher and a Vulkan GPU probe.
echo It does NOT replace Minecraft's OpenGL renderer yet.
echo First-time dependency downloads can take several minutes.
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Build-Asterdium-Windows.ps1"
if errorlevel 1 goto :failure

echo.
echo BUILD FINISHED. Development mod JAR is in build\libs\
echo.
pause
exit /b 0

:failure
echo.
echo BUILD STOPPED. Copy or screenshot the error above.
pause
exit /b 1
