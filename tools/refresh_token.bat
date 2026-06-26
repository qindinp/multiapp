@echo off
where python >nul 2>&1
if errorlevel 1 (
    echo Python was not found. Please install Python 3 first.
    pause
    exit /b 1
)

set "SCRIPT=%~dp0refresh_openai_token.py"

echo.
echo === OpenAI Token Refresh Tool ===
echo.
echo Usage:
echo   1. Double-click this file and follow the prompts.
echo   2. Or drag a token_*.json file/folder onto this file.
echo.

if "%~1"=="" (
    python "%SCRIPT%" --wizard --no-color 2>&1
) else (
    python "%SCRIPT%" "%~1" --wizard --no-color 2>&1
)
set "EXIT_CODE=%ERRORLEVEL%"
echo.
pause
exit /b %EXIT_CODE%
