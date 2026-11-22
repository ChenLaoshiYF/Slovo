@echo off
chcp 65001 >nul
setlocal
set "APP_PY=%USERPROFILE%\Desktop\QingOH-WorkSpace\projects\俄汉同传\.venv\Scripts\python.exe"
if not exist "%APP_PY%" set "APP_PY=python"
set "PYTHONUTF8=1"
"%APP_PY%" "%~dp0make_lesson.py" %*
pause
