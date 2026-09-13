param(
    [string] $PackageName = "dev.drift.location",
    [switch] $Revoke
)

$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
if ($null -eq $adbCommand) {
    Write-Error "找不到 adb，请先安装 Android SDK Platform Tools 并把 adb 加入 PATH。"
    exit 1
}

$deviceLines = @(adb devices | Select-String "\sdevice$")
if ($deviceLines.Count -eq 0) {
    Write-Error "没有检测到已授权的 Android 设备。请打开 USB 调试并确认 adb devices 能看到 device。"
    exit 1
}

$mode = if ($Revoke) { "deny" } else { "allow" }
Write-Host "正在为 $PackageName 设置 android:mock_location=$mode ..."
adb shell appops set $PackageName android:mock_location $mode
if ($LASTEXITCODE -ne 0) {
    Write-Error "AppOp 设置失败。请确认包名和设备 Android 版本正确。"
    exit $LASTEXITCODE
}

Write-Host "当前授权状态："
adb shell appops get $PackageName android:mock_location
