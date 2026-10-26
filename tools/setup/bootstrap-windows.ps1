# Installs the SABLE toolchain into a user-local folder (no admin, no system changes).
#   JDK 21 (Temurin), Android cmdline-tools + platform-tools + platform + build-tools + NDK + CMake,
#   llvm-mingw (host clang with ASan/UBSan + clang-tidy), FFmpeg (test/validation only).
# Usage: powershell -ExecutionPolicy Bypass -File tools\setup\bootstrap-windows.ps1 [-Root C:\Users\<you>\dev\sable-toolchain]
param(
  [string]$Root = "$env:USERPROFILE\dev\sable-toolchain",
  [string]$AndroidPlatform = "android-36",
  [string]$BuildTools = "37.0.0",
  [string]$CmakeVersion = "4.1.2"
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
New-Item -ItemType Directory -Force $Root, "$Root\dl" | Out-Null

function Get-Zip($url, $name, $dest) {
  $zip = "$Root\dl\$name"
  if (-not (Test-Path $zip)) { Write-Host "download $name"; Invoke-WebRequest $url -OutFile $zip -UseBasicParsing }
  if (-not (Test-Path $dest)) { Write-Host "extract  $name"; Expand-Archive $zip -DestinationPath "$dest.tmp" -Force; Move-Item "$dest.tmp" $dest }
}

# --- JDK 21
$jdk = Invoke-RestMethod "https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse"
Get-Zip $jdk[0].binary.package.link $jdk[0].binary.package.name "$Root\jdk"
$javaHome = (Get-ChildItem "$Root\jdk" -Directory | Select-Object -First 1).FullName

# --- llvm-mingw (host compiler)
$rel = Invoke-RestMethod https://api.github.com/repos/mstorsjo/llvm-mingw/releases/latest
$asset = $rel.assets | Where-Object name -match 'ucrt-x86_64\.zip$' | Select-Object -First 1
Get-Zip $asset.browser_download_url $asset.name "$Root\llvm-mingw"
$llvmBin = (Get-ChildItem "$Root\llvm-mingw" -Directory | Select-Object -First 1).FullName + "\bin"

# --- FFmpeg (validation only, never shipped)
Get-Zip "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip" "ffmpeg.zip" "$Root\ffmpeg"
$ffBin = (Get-ChildItem "$Root\ffmpeg" -Directory | Select-Object -First 1).FullName + "\bin"

# --- Android SDK
$sdk = "$Root\android-sdk"
$page = (Invoke-WebRequest https://developer.android.com/studio -UseBasicParsing).Content
$clt = ([regex]'commandlinetools-win-\d+_latest\.zip').Match($page).Value
Get-Zip "https://dl.google.com/android/repository/$clt" $clt "$Root\clt-extract"
if (-not (Test-Path "$sdk\cmdline-tools\latest")) {
  New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
  Copy-Item "$Root\clt-extract\cmdline-tools" "$sdk\cmdline-tools\latest" -Recurse
}
$env:JAVA_HOME = $javaHome
# sdkmanager ignores piped license answers on recent cmdline-tools; the new `android` CLI prompts once.
# License acceptance approved by the project owner (2026-10-25).
$ndk = "30.0.16248370"
$cli = "$sdk\cmdline-tools\latest\bin\android.exe"
("y`n" * 20) | & $cli --sdk=$sdk sdk install platform-tools "platforms;$AndroidPlatform" "platforms;android-37.0" "build-tools;$BuildTools" "ndk;$ndk" "cmake;$CmakeVersion"

# --- env file other scripts dot-source
@"
`$env:JAVA_HOME = '$javaHome'
`$env:ANDROID_HOME = '$sdk'
`$env:ANDROID_SDK_ROOT = '$sdk'
`$env:ANDROID_NDK_HOME = '$sdk\ndk\$ndk'
`$env:PATH = '$llvmBin;$sdk\cmake\$CmakeVersion\bin;$sdk\platform-tools;$ffBin;$javaHome\bin;' + `$env:PATH
"@ | Set-Content -Encoding utf8 "$Root\env.ps1"
Write-Host "done. dot-source $Root\env.ps1"
