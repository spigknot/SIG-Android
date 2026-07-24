@echo off
setlocal
cd /d "%~dp0"
set "WORKPATH=%TEMP%\sig-pyinstaller-%RANDOM%%RANDOM%"
python -m PyInstaller --noconfirm --onefile --windowed --name sig --workpath "%WORKPATH%" --icon "assets\icon.ico" ^
  --hidden-import _cffi_backend ^
  --hidden-import websocket ^
  --add-data "assets\ffmpeg.exe;assets" ^
  --add-data "assets\ffplay.exe;assets" ^
  --add-data "assets\appwin.jpg;assets" ^
  --add-data "assets\appwin.png;assets" ^
  --add-data "assets\icon.png;assets" ^
  --add-data "assets\default_nomes.txt;assets" ^
  "src\sig_app.py"
echo.
echo Executavel gerado em: %CD%\dist\sig.exe
pause
