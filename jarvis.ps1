# Jarvis watch face helper. Usage examples:
#   .\jarvis.ps1 connect 192.168.0.29:40857     # adb over Wi-Fi
#   .\jarvis.ps1 install                         # build debug + install + set as watch face
#   .\jarvis.ps1 cmd state:bored                 # trigger a state (held until 'clear')
#   .\jarvis.ps1 cmd emote:love
#   .\jarvis.ps1 cmd clear
#   .\jarvis.ps1 notify-access                   # grant notification access (optional feature)
#   .\jarvis.ps1 shot idle                       # screenshot from the watch to screens/
#   .\jarvis.ps1 log                             # follow Jarvis logs
param([string]$Action = "help", [string]$Arg = "")

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$pkg = "com.jarvis.watchface"
$svc = "$pkg/$pkg.JarvisWatchFaceService"
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr" }

switch ($Action) {
    "connect" { & $adb connect $Arg }
    "install" {
        & "$PSScriptRoot\gradlew.bat" :watchface:assembleDebug --console=plain -q
        & $adb install -r "$PSScriptRoot\watchface\build\outputs\apk\debug\watchface-debug.apk"
        & $adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation set-watchface --es watchFaceId $pkg
    }
    "cmd" { & $adb shell am broadcast -a "$pkg.DEBUG" -p $pkg --es cmd $Arg }
    "notify-access" { & $adb shell cmd notification allow_listener "$pkg/$pkg.JarvisNotificationListener" }
    "notify-revoke" { & $adb shell cmd notification disallow_listener "$pkg/$pkg.JarvisNotificationListener" }
    "shot" {
        $name = if ($Arg) { $Arg } else { Get-Date -Format "HHmmss" }
        New-Item -ItemType Directory -Force "$PSScriptRoot\screens" | Out-Null
        & $adb shell screencap -p /sdcard/jarvis.png
        & $adb pull /sdcard/jarvis.png "$PSScriptRoot\screens\$name.png"
    }
    "log" { & $adb logcat -s JarvisMood JarvisAssist JarvisFace AndroidRuntime }
    default { Get-Content $PSCommandPath -TotalCount 10 }
}
