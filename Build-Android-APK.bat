@echo off
setlocal EnableExtensions
title QB Player - Android APK Builder
cd /d "%~dp0"

echo ====================================================
echo      Building QB Player Android APK (Debug/Release)
echo ====================================================
echo.

echo Syncing web assets to Android project...
python sync_assets.py

cd android

if exist gradlew.bat (
    echo.
    echo Checking for Java / Gradle...
    where java >nul 2>&1
    if %ERRORLEVEL% EQU 0 (
        echo Building debug APK with Gradle...
        call gradlew.bat assembleDebug
        if exist "app\build\outputs\apk\debug\app-debug.apk" (
            echo.
            echo ====================================================
            echo [SUCCESS] APK built successfully!
            echo Location: android\app\build\outputs\apk\debug\app-debug.apk
            echo Transfer this APK to your Mobile / Oppo Pad and tap Install.
            echo ====================================================
            goto done
        )
    )
)

echo.
echo Notice: Local APK compilation requires JDK 17 and Android SDK installed on PC.
echo.
echo ====================================================
echo Easiest Way to get the APK (No Android SDK required):
echo 1. Run 'Push-To-GitHub.bat' to push this repo to GitHub.
echo 2. The included GitHub Actions workflow will automatically compile
echo    'QB-Player-v1.0-Naqeeb.apk' in the cloud!
echo 3. You can download the APK directly onto your Mobile or Oppo Pad!
echo ====================================================
echo.
echo Or open the 'android' folder in Android Studio and click:
echo Build -^> Build Bundle(s) / APK(s) -^> Build APK(s)

:done
echo.
echo Press any key to exit...
pause >nul
