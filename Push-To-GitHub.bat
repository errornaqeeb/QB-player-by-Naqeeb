@echo off
setlocal
title QB Player by Naqeeb - Push to GitHub
echo ====================================================
echo      QB Player by Naqeeb - GitHub Repository Setup
echo ====================================================

:: Refresh PATH to include winget installed tools (git, gh)
for /f "tokens=2*" %%a in ('reg query "HKCU\Environment" /v Path 2^>nul') do set "USERPATH=%%b"
for /f "tokens=2*" %%a in ('reg query "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v Path 2^>nul') do set "SYSPATH=%%b"
set "PATH=%USERPATH%;%SYSPATH%;%PATH%"

echo Checking GitHub CLI status...
where gh >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo GitHub CLI (gh) is not installed yet.
    echo Please install it or use standard git commands below.
    goto manual_git
)

gh auth status >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo GitHub CLI is authenticated! Creating repository 'QB-player-by-Naqeeb'...
    gh repo create QB-player-by-Naqeeb --public --source=. --remote=origin --push
    if %ERRORLEVEL% EQU 0 (
        echo.
        echo [SUCCESS] Repository created and pushed successfully to GitHub!
        goto done
    )
)

echo You are not logged into GitHub CLI yet.
echo Launching 1-time GitHub authentication...
gh auth login --web -p https
if %ERRORLEVEL% EQU 0 (
    echo Authenticated! Now creating repository 'QB-player-by-Naqeeb'...
    gh repo create QB-player-by-Naqeeb --public --source=. --remote=origin --push
    echo [SUCCESS] Done!
    goto done
)

:manual_git
echo.
echo ====================================================
echo Alternative: Push with your GitHub username
echo ====================================================
set /p GH_USER="Enter your GitHub username: "
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
