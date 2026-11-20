@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo 正在启动反重力遥控网关 (默认端口 18900)...
python gateway.py
pause
