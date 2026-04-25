@echo off
title Visitor Management Fingerprint and RFID Service
echo Starting Services...
java -Dlog4j2.configurationFile=log4j2.xml  -Dlog4j2.enableAnsi=true -Dlog4j2.skipJansi=false --enable-native-access=ALL-UNNAMED -jar VMSServer-1.0.jar
pause
