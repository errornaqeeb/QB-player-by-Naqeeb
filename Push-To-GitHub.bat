@echo off
setlocal EnableExtensions
title QB Player by Naqeeb - Push to GitHub
cd /d "%~dp0"

echo ====================================================
echo      QB Player by Naqeeb - GitHub Repository Setup
echo ====================================================
echo.

:: Refresh PATH from registry so winget installed git and gh are accessible
for /f "tokens=2*" %%a in ('reg query "HKCU\Environment" /v Path 2^>nul') do set "USERPATH=%%b"
for /f "tokens=2*" %%a in ('reg query "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v Path 2^>nul') do set "SYSPATH=%%b"
set "PATH=%USERPATH%;%SYSPATH%;%PATH%"

where gh >nul 2>&1
if %ERRORLEVEL% NEQ 0 goto no_gh

echo Checking GitHub authentication status...
gh auth status >nul 2>&1
if %ERRORLEVEL% EQU 0 goto gh_logged_in

echo You are not logged into GitHub yet.
echo Launching GitHub login in your web browser...
echo (Follow the prompts in your browser to authorize GitHub CLI)
echo.
gh auth login --web -p https
if %ERRORLEVEL% NEQ 0 goto manual_git

:gh_logged_in
echo.
echo Logged in! Creating remote repository 'QB-player-by-Naqeeb'...
gh repo create QB-player-by-Naqeeb --public --source=. --remote=origin --push
if %ERRORLEVEL% EQU 0 (
    echo.
    echo ====================================================
    echo [SUCCESS] Repository created and pushed to GitHub!
    echo Your Android APK build will automatically start now.
    echo ====================================================
    goto done
) else (
    echo.
    echo Notice: Repository may already exist on GitHub, pushing main branch...
    git branch -M main
    git push -u origin main
    goto done
)

:no_gh
echo GitHub CLI [gh] was not found in PATH.
goto manual_git

:manual_git
echo.
echo ====================================================
echo Manual Push with Git
echo ====================================================
echo Enter your GitHub username to link and push:
set /p GH_USER="GitHub Username: "
if "%GH_USER%"=="" goto done
git remote remove origin 2>nul
git remote add origin https://github.com/%GH_USER%/QB-player-by-Naqeeb.git
git branch -M main
echo Pushing to https://github.com/%GH_USER%/QB-player-by-Naqeeb.git ...
git push -u origin main

:done
echo.
echo Press any key to exit...
pause >nul
