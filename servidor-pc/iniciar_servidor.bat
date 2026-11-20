@echo off
chcp 65001 >nul
title Servidor - Traductor de entorno
cd /d "%~dp0"

echo ==================================================
echo   Servidor de PC - Traductor de entorno
echo ==================================================
echo.

REM --- 1) Buscar Python ---
set PY=
where py >nul 2>nul && set PY=py -3
if "%PY%"=="" (where python >nul 2>nul && set PY=python)
if "%PY%"=="" (
  echo [ERROR] No encontre Python. Instalalo desde https://www.python.org/downloads/
  echo         y marca la casilla "Add Python to PATH" en el instalador.
  pause
  exit /b 1
)

REM --- 2) Entorno virtual (solo la primera vez) ---
if not exist ".venv\Scripts\python.exe" (
  echo Creando entorno virtual, solo la primera vez...
  %PY% -m venv .venv
  if errorlevel 1 (
    echo [ERROR] No pude crear el entorno virtual.
    pause
    exit /b 1
  )
)

REM --- 3) Instalar librerias (solo si faltan) ---
if not exist ".venv\.instalado" (
  echo Instalando librerias, puede tardar un minuto...
  ".venv\Scripts\python.exe" -m pip install --upgrade pip >nul
  ".venv\Scripts\python.exe" -m pip install -r requirements.txt
  if errorlevel 1 (
    echo [ERROR] Fallo la instalacion. Revisa tu conexion a internet.
    pause
    exit /b 1
  )
  echo ok > ".venv\.instalado"
)

REM --- 4) Clave de Gemini ---
if not exist ".env" (
  echo.
  echo Falta el archivo .env con tu clave de Gemini.
  set /p CLAVE=Pega tu clave de Gemini y apreta Enter: 
  if "%CLAVE%"=="" (
    echo [ERROR] No ingresaste ninguna clave.
    pause
    exit /b 1
  )
  > ".env" echo GEMINI_API_KEY=%CLAVE%
  echo Clave guardada en .env
)

REM --- 5) Mostrar las IP de esta PC (la del WiFi es la que va en el ESP32) ---
echo.
echo IP de esta PC (usa la de tu red WiFi en SERVIDOR_IP_RESPALDO del ESP32):
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /c:"IPv4"') do echo    %%a
echo Puerto: 8000
echo.
echo Para detener el servidor: Ctrl+C
echo.

REM --- 6) Arrancar ---
".venv\Scripts\python.exe" servidor.py
echo.
echo El servidor se detuvo.
pause
