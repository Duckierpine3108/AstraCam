@echo off
title AstraCam - GitHub Push Script
color 0B

echo =========================================================
echo             ASTRACAM GITHUB AUTOMATED PUSHER             
echo =========================================================
echo Target Repo: https://github.com/Duckierpine3108/AstraCam
echo.

:: 1. Check if Git is installed
git --version >nul 2>&1
if %ERRORLEVEL% neq 0 (
    color 0C
    echo [ERROR] Git is not installed or not in your system PATH.
    echo Please install Git from https://git-scm.com/ and try again.
    goto END
)

:: 2. Safety check: make sure we are inside the AstraCam root directory
if not exist "settings.gradle.kts" (
    color 0C
    echo [ERROR] Could not find settings.gradle.kts.
    echo Make sure you placed this .bat file directly in the C:\AstraCam folder!
    goto END
)

:: 3. Initialize Git repository if .git folder is missing
if not exist ".git" (
    echo [*] Initializing local Git repository...
    git init
    echo.
)

:: 4. Ensure branch is 'main'
git branch -M main

:: 5. Set or update remote origin URL
echo [*] Configuring remote repository...
git remote remove origin >nul 2>&1
git remote add origin https://github.com/Duckierpine3108/AstraCam.git

:: 6. Check for uncommitted changes or untracked files
echo [*] Staging all files...
git add .

:: 7. Prompt for custom commit message or use default
echo.
set /p msg="Enter commit message (Press ENTER for 'Initial AstraCam native engine release'): "
if "%msg%"=="" set msg=Initial AstraCam native engine release

echo.
echo [*] Committing changes...
git commit -m "%msg%"

:: 8. Push to GitHub
echo.
echo [*] Pushing to GitHub (origin main)...
echo.
git push -u origin main

if %ERRORLEVEL% equ 0 (
    color 0A
    echo.
    echo =========================================================
    echo [SUCCESS] Code pushed successfully to GitHub!
    echo Check your Actions tab at:
    echo https://github.com/Duckierpine3108/AstraCam/actions
    echo =========================================================
) else (
    color 0E
    echo.
    echo =========================================================
    echo [NOTICE] If GitHub rejected the push because the remote 
    echo already has a README or commit, would you like to FORCE push?
    echo =========================================================
    set /p forceChoice="Force overwrite remote? (y/n): "
    if /i "%forceChoice%"=="y" (
        echo [*] Force pushing to main...
        git push -u origin main --force
        if %ERRORLEVEL% equ 0 (
            color 0A
            echo [SUCCESS] Force push completed!
        )
    )
)

:END
echo.
echo Press any key to exit...
pause >nul