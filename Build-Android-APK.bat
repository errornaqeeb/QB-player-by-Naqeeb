@echo off
title QB Player - Android APK Builder
echo ====================================================
echo      Building QB Player Android APK (Debug/Release)
echo ====================================================

cd /d "%~dp0"

echo Syncing web assets to Android project...
python -c "
import shutil, os
dest_dir = './android/app/src/main/assets'
os.makedirs(dest_dir, exist_ok=True)
for item in ['index.html', 'manifest.json', 'service-worker.js']:
    shutil.copy2(item, os.path.join(dest_dir, item))
for folder in ['css', 'js', 'assets']:
    dest_folder = os.path.join(dest_dir, folder)
    if os.path.exists(dest_folder):
        shutil.rmtree(dest_folder)
    shutil.copytree(folder, dest_folder)
print('Assets synced successfully.')
"

cd android
where gradlew.bat >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo Building debug APK with Gradle...
    call gradlew.bat assembleDebug
    if %ERRORLEVEL% EQU 0 (
        echo.
        echo ====================================================
        echo [SUCCESS] APK built successfully!
        echo Location: android\app\build\outputs\apk\debug\app-debug.apk
        echo Transfer this APK to your Mobile / Oppo Pad and tap Install.
        echo ====================================================
        goto done
    )
)

echo.
echo Notice: To compile the native APK locally, Android SDK and JDK 17 are required.
echo.
echo Easiest Way to get the APK:
echo 1. Run 'Push-To-GitHub.bat' to push this repo to GitHub.
echo 2. The included GitHub Actions workflow will automatically compile
echo    'QB-Player-v1.0-Naqeeb.apk' in the cloud!
echo 3. You can download the APK directly onto your Mobile or Oppo Pad!
echo.
echo Or open the 'android' folder in Android Studio and click Build - Build APK.

:done
pause
