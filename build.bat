@echo off
setlocal enabledelayedexpansion
:: Name: build.bat
:: Version: v1.0.0 (Modified by Thallium)
:: Author: jcau8
:: Date: 2026, 05, 11

title Build VV Plus

REM Set root directory to current directory to avoid any file issues
pushd "%~dp0"

REM Colours and escape sequences (from matject, thanks fzul)
set "GRY=[90m"
set "RED=[91m"
set "GRN=[92m"
set "YLW=[93m"
set "BLU=[94m"
set "CYN=[96m"
set "WHT=[97m"
set "RST=[0m" && REM Clears colours and formatting
set "ERR=[41;97m" && REM Red background with white text

REM Shaderc paths
set "shadercPath=tools\shaderc.exe"
set "shadercZip=shaderc.zip"
set "downloadURL=https://github.com/bambosan/bgfx-mcbe/releases/download/binaries/shaderc-win-x64.zip"

REM Materials paths
set "baseMaterialsPath=output"

REM Checking for lazurite
python -c "import lazurite" 2>nul
if errorlevel 1 (
    echo !ERR!Lazurite not found.!RST!
    echo !WHT!Make sure you have installed lazurite.!RST!
    echo !WHT!To install lazurite open a command prompt and run: !GRY!pip install lazurite!RST!
    echo !GRY!Press any key to exit...!RST!
    pause >nul
    popd
    exit /b 1
)
echo !GRN!Lazurite found!!RST!

REM Checking shaderc
if exist "%shadercPath%" (
    echo !GRN!Shaderc found!RST!
    goto :SetPlatform
) else (
    echo !ERR!Shaderc not found.!RST!
    echo !WHT!The build cannot start without shaderc installed.!RST!
)

echo !YLW!Would you like to download shaderc automatically? (Y/N)!RST!
choice /c yn /n >nul
set "choice=%errorlevel%"

if "%choice%"=="1" (
    goto :DownloadShaderc
) else (
    echo !WHT!Please install shaderc to this folder.!RST!
    echo !GRY!Press any key to exit...!RST!
    pause >nul
    popd
    exit /b 1
)

:DownloadShaderc
cls
powershell -Command "Invoke-WebRequest -Uri '%downloadURL%' -OutFile '%shadercZip%'"
powershell -Command "Expand-Archive -Force '%shadercZip%' '.'"

set "SHADERC_FOUND=0"
for /r %%f in (shadercRelease.exe) do (
    if not exist "tools" mkdir "tools"
    move /y "%%f" "%shadercPath%" >nul
    set "SHADERC_FOUND=1"
)

REM Make sure shaderc installed successfully
if "!SHADERC_FOUND!"=="0" (
    echo !ERR!Shaderc binary not found after extraction!!RST!
    echo !GRY!Press any key to exit...!RST!
    pause >nul
    popd
    exit /b 1
)
del "%shadercZip%"

echo !GRN!Shaderc successfully downloaded.!RST!

:SetPlatform
cls
REM Hardcoding platform to support all devices
set "baseProfile=multiple"

goto :BuildMaterials

:BuildMaterials
cls
REM Check for build directories, create them if they don't exist
if not exist "%baseMaterialsPath%" mkdir "%baseMaterialsPath%"

cls

REM Build the multiple profile
echo !WHT!Running build: %baseProfile%!RST!
call python -m lazurite build ./src -p %baseProfile% -o %baseMaterialsPath% --shaderc %shadercPath%

if errorlevel 1 (
    echo !ERR!Failed to build profile: %baseProfile%!RST!
    pause
    popd
    exit /b 1
)

echo !GRN!Build: %baseProfile% completed successfully!!RST!
echo !WHT!The compiled materials are ready in the '%baseMaterialsPath%' folder.!RST!

echo !GRY!Press any key to exit...!RST!
pause >nul
popd
exit /b 0