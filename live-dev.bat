@echo off
title Campus Ride - Live Hot-Deploy Watcher
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0live-dev.ps1"
pause
