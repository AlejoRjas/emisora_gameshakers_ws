@echo off
rem Inicia Kafka para GameShakers Chat (Windows).
rem Kafka debe estar descomprimido en C:\kafka (o define KAFKA_HOME con otra carpeta).
setlocal
if "%KAFKA_HOME%"=="" set "KAFKA_HOME=C:\kafka"
cd /d "%~dp0"
if exist "%KAFKA_HOME%\bin\windows\kafka-server-start.bat" goto existe
echo No encuentro Kafka en %KAFKA_HOME%. Descomprimelo en C:\kafka o define KAFKA_HOME.
pause
exit /b 1
:existe
rem La primera vez hay que formatear la carpeta de datos con un identificador de cluster
if exist "datos\meta.properties" goto arrancar
for /f %%i in ('call "%KAFKA_HOME%\bin\windows\kafka-storage.bat" random-uuid') do set "KAFKA_ID=%%i"
echo Formateando la carpeta de datos (id %KAFKA_ID%)...
call "%KAFKA_HOME%\bin\windows\kafka-storage.bat" format --standalone -t %KAFKA_ID% -c chat-kafka.properties
:arrancar
echo Iniciando Kafka en localhost:9092 (no cierres esta ventana)...
call "%KAFKA_HOME%\bin\windows\kafka-server-start.bat" chat-kafka.properties
pause
