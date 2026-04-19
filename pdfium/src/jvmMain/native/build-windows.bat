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

REM Bring MSVC into the environment if cl.exe is not already on PATH.
where cl.exe >nul 2>&1
if errorlevel 1 (
  set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
  if not exist "!VSWHERE!" set "VSWHERE=%ProgramFiles%\Microsoft Visual Studio\Installer\vswhere.exe"
  if not exist "!VSWHERE!" (
    echo ERROR: cl.exe not on PATH and vswhere.exe not found. Install Visual Studio Build Tools or run from a Native Tools prompt.
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
  call "!VCVARS!" %ARCH%
  if errorlevel 1 (
    echo ERROR: vcvarsall.bat %ARCH% failed.
    exit /b 1
  )
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
