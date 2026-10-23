@echo off
rem 打包为单个免安装 exe（需先: pip install pyinstaller）
cd /d %~dp0
pip install pyinstaller >nul 2>&1
pyinstaller --onefile --noconsole --name PhotoSyncReceiver ^
  --distpath dist --workpath build server.py
echo.
echo 完成: dist\PhotoSyncReceiver.exe （config.json 生成在 exe 同目录）
pause
