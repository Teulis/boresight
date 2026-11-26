# Build, test, and publish a release to the private GitHub repo.
#
#   .\release.ps1              version is today's date
#   .\release.ps1 -Tag v2026.11.26.1
#
# The APK is a release asset rather than a committed file: binaries do not belong in
# git history, and a release page is what a phone can download from.
param([string]$Tag)

$ErrorActionPreference = "Continue"
$root = $PSScriptRoot
$env:PATH = "C:\tools\git\cmd;$env:PATH"

if (-not $Tag) { $Tag = "v" + (Get-Date -Format "yyyy.MM.dd") }

# Stamp the manifest BEFORE building, so the APK carries the version it is released
# under. Without this every build reported the same versionName forever, and a bug
# report could not name which one it came from.
#
# UTF-8 explicitly on both the read and the write. Reading as the ANSI codepage and
# writing as UTF-8 is what mangled every non-ASCII character in this repo once before.
$manifestPath = "$root\AndroidManifest.xml"
$utf8 = New-Object System.Text.UTF8Encoding $false
$manifest = [System.IO.File]::ReadAllText($manifestPath, [System.Text.Encoding]::UTF8)

$name = $Tag.TrimStart("v")
# versionCode must be a monotonically increasing integer; the date lives in the name.
$code = [int]([regex]::Match($manifest, 'android:versionCode="(\d+)"')).Groups[1].Value + 1
$manifest = [regex]::Replace($manifest, 'android:versionCode="\d+"',  "android:versionCode=`"$code`"")
$manifest = [regex]::Replace($manifest, 'android:versionName="[^"]*"', "android:versionName=`"$name`"")
[System.IO.File]::WriteAllText($manifestPath, $manifest, $utf8)
Write-Host "stamped $name (versionCode $code)" -ForegroundColor Cyan

Write-Host "building" -ForegroundColor Cyan
& "$root\build.ps1"
if ($LASTEXITCODE -ne 0) { Write-Host "build failed" -ForegroundColor Red; exit 1 }

Write-Host "testing" -ForegroundColor Cyan
& "$root\test\run.ps1"
if ($LASTEXITCODE -ne 0) { Write-Host "tests failed - not releasing" -ForegroundColor Red; exit 1 }

$apkSrc = (Get-ChildItem "$root\build\signed" -Filter *.apk | Select-Object -First 1).FullName
$apk = Join-Path $env:TEMP ("boresight-" + $Tag.TrimStart("v") + ".apk")
Copy-Item $apkSrc $apk -Force

Push-Location $root
& git add -A
& git commit -q -m "Build $Tag" 2>$null
& git push -q origin HEAD 2>$null
& gh release create $Tag $apk --title "Boresight $($Tag.TrimStart('v'))" --generate-notes
Pop-Location
Remove-Item $apk -Force -ErrorAction SilentlyContinue
Write-Host "released $Tag" -ForegroundColor Green
