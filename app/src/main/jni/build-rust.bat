@echo off
REM Build script for Rust native modules (Windows)

setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%"

REM Android NDK configuration
if "%ANDROID_NDK%"=="" set ANDROID_NDK=%LOCALAPPDATA%\Android\Sdk\ndk\25.2.9519653
set ANDROID_PLATFORM=29

REM Rust targets
set TARGETS=aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android

REM Modules to build
set MODULES=xray-signal-handler pepper-shaper perf-net quiche-client

echo Building Rust modules for Android...
echo NDK: %ANDROID_NDK%
echo Platform: %ANDROID_PLATFORM%

REM Check if cargo-ndk is installed
where cargo-ndk >nul 2>&1
if errorlevel 1 (
    echo Installing cargo-ndk...
    cargo install cargo-ndk
)

REM Build each module for each target
for %%m in (%MODULES%) do (
    echo.
    echo Building %%m...
    cd %%m
    
    for %%t in (%TARGETS%) do (
        echo   Building for %%t...
        cargo ndk --target %%t --platform %ANDROID_PLATFORM% build --release
        
        REM Copy library to libs directory
        if not exist "libs\%%t" mkdir "libs\%%t"
        copy /Y "target\%%t\release\lib%%m:-=_%.so" "libs\%%t\" >nul 2>&1
    )
    
    cd ..
)

echo.
echo Build complete!

