@echo off
cd /d "%~dp0"

:: Set the WebSocket server address - directly use Railway's URL format
set SERVER_URL=wss://capable-surprise-production.up.railway.app

echo =============================================
echo Starting collaborative editor client
echo Connecting to: %SERVER_URL%
echo =============================================

:: Run the client with specific JVM arguments for proper WebSocket handling
mvn exec:java -Dexec.mainClass=com.project.Main -Djavax.net.debug=none

echo =============================================
echo Client closed
echo =============================================
pause 