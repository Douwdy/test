@echo off
rem Construit Visualiseur.exe (dans dist\) avec PyInstaller. Demande Python 3.10+ installé.
cd /d "%~dp0"
python -m pip install --upgrade -r requirements.txt pyinstaller || goto :erreur
python -m PyInstaller --noconfirm --onefile --windowed --name Visualiseur visualiseur.py || goto :erreur
if exist ffmpeg.exe copy /y ffmpeg.exe dist\ >nul
if exist ffprobe.exe copy /y ffprobe.exe dist\ >nul
echo.
echo Termine : dist\Visualiseur.exe
echo Mettez ffmpeg.exe et ffprobe.exe a cote de Visualiseur.exe si ce n'est pas deja fait.
pause
exit /b 0
:erreur
echo La construction a echoue.
pause
exit /b 1
