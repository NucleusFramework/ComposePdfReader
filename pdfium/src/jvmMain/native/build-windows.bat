@echo off
REM Compile the PDFium JNI glue for Windows. Auto-locates MSVC via vswhere if cl.exe is not on PATH.
REM Usage: build-windows.bat [arch]   where arch is x64 (default) or arm64.
setlocal enabledelayedexpansion
set "HERE=%~dp0"
set "ARCH=%~1"
if "%ARCH%"=="" set "ARCH=x64"

if "%PDFIUM_INCLUDE%"=="" (echo PDFIUM_INCLUDE unset & exit /b 1)
if "%PDFIUM_LIB%"=="" (echo PDFIUM_LIB unset & exit /b 1)
if "%OUT_DIR%"=="" (echo OUT_DIR unset & exit /b 1)
if "%JAVA_HOME%"=="" (echo JAVA_HOME unset & exit /b 1)

REM Always call vcvarsall.bat with the target arch. Even when cl.exe is already on
REM PATH (e.g. GitHub runners with ilammy/msvc-dev-cmd default x64), we still need
REM to reconfigure the env for the requested %ARCH% so cross-compiles like arm64
REM link against the right libraries.
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "!VSWHERE!" set "VSWHERE=%ProgramFiles%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "!VSWHERE!" (
  echo ERROR: vswhere.exe not found. Install Visual Studio Build Tools.
  exit /b 1
)
for /f "usebackq tokens=*" %%i in (`"!VSWHERE!" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSINSTALL=%%i"
if "!VSINSTALL!"=="" (
  echo ERROR: No Visual Studio installation with C++ tools detected by vswhere.
  exit /b 1
)
set "VCVARS=!VSINSTALL!\VC\Auxiliary\Build\vcvarsall.bat"
if not exist "!VCVARS!" (
  echo ERROR: vcvarsall.bat not found at !VCVARS!
  exit /b 1
)
REM For arm64 target on an x64 host, vcvarsall needs the cross-compile variant.
set "VCVARS_ARCH=%ARCH%"
if /I "%ARCH%"=="arm64" (
  if /I not "%PROCESSOR_ARCHITECTURE%"=="ARM64" set "VCVARS_ARCH=amd64_arm64"
)
call "!VCVARS!" %VCVARS_ARCH%
if errorlevel 1 (
  echo ERROR: vcvarsall.bat %VCVARS_ARCH% failed.
  exit /b 1
)

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
