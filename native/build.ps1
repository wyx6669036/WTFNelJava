param(
    [string]$Configuration = "Release",
    # 产物目录；不传就落在 native/build（与 README 里的手工流程一致）。
    # Gradle 会传 build/native，好让 gradlew clean 一并清掉。
    [string]$BuildDir = ""
)

$ErrorActionPreference = "Stop"

$vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
if (-not (Test-Path $vswhere)) { throw "vswhere.exe not found; install Visual Studio Build Tools with C++" }

$vsPath = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $vsPath) { throw "no Visual Studio installation with the C++ toolchain" }

$vcvars = Join-Path $vsPath "VC\Auxiliary\Build\vcvars64.bat"
if (-not (Test-Path $vcvars)) { throw "vcvars64.bat not found: $vcvars" }

$cmake = Join-Path $vsPath "Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe"
if (-not (Test-Path $cmake)) { $cmake = "cmake" }

$ninja = Join-Path $vsPath "Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja\ninja.exe"
if (-not (Test-Path $ninja)) { $ninja = "ninja" }

if ([string]::IsNullOrWhiteSpace($BuildDir)) {
    $buildDir = Join-Path $PSScriptRoot "build"
} else {
    $buildDir = [System.IO.Path]::GetFullPath($BuildDir)
}

$configure = "`"$cmake`" -S `"$PSScriptRoot`" -B `"$buildDir`" -G Ninja -DCMAKE_MAKE_PROGRAM=`"$ninja`" -DCMAKE_BUILD_TYPE=$Configuration"
$build = "`"$cmake`" --build `"$buildDir`" --config $Configuration"

$line = "call `"$vcvars`" >nul && $configure && $build"
cmd /c $line
if ($LASTEXITCODE -ne 0) { throw "native payload build failed (exit $LASTEXITCODE)" }

Write-Host "native payload output: $buildDir"

Get-ChildItem $buildDir -Filter "*.dll" -ErrorAction SilentlyContinue |
    ForEach-Object { Write-Host ("  " + $_.Name + "  " + $_.Length + " bytes") }
Get-ChildItem $buildDir -Filter "WPFLauncher.exe" -ErrorAction SilentlyContinue |
    ForEach-Object { Write-Host ("  " + $_.Name + "  " + $_.Length + " bytes") }
