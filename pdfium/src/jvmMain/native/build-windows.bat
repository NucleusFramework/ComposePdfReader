@echo off
REM Compile the PDFium JNI glue for Windows x64. Requires MSVC (cl.exe) on PATH (vcvars64.bat).
setlocal
set "HERE=%~dp0"
if "%PDFIUM_INCLUDE%"=="" (echo PDFIUM_INCLUDE unset & exit /b 1)
if "%PDFIUM_LIB%"=="" (echo PDFIUM_LIB unset & exit /b 1)
if "%OUT_DIR%"=="" (echo OUT_DIR unset & exit /b 1)
if "%JAVA_HOME%"=="" (echo JAVA_HOME unset & exit /b 1)

if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

cl /std:c++17 /O2 /LD /EHsc ^
  /I "%PDFIUM_INCLUDE%" ^
  /I "%JAVA_HOME%\include" /I "%JAVA_HOME%\include\win32" ^
  "%HERE%pdfium_jni.cpp" ^
  /link /LIBPATH:"%PDFIUM_LIB%" pdfium.dll.lib ^
  /OUT:"%OUT_DIR%\pdfiumjni.dll"

if errorlevel 1 exit /b 1
echo Built %OUT_DIR%\pdfiumjni.dll
endlocal
