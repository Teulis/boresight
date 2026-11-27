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

# The hash goes in the notes of every release, because the alternative was telling
# people to skip Play Protect — which is advice nobody should take from a stranger's
# README. A published digest lets them check the download against VirusTotal without
# uploading anything or trusting this project's word for it.
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
$size = [math]::Round((Get-Item $apk).Length / 1KB)
$notes = Join-Path $env:TEMP "boresight-notes-$($Tag.TrimStart('v')).md"
@"
Install by downloading the APK on the phone and opening it. No adb, no developer
options, no cable. Signed with the same key as every other build, so it installs over
the previous one and keeps your data.

**SHA-256** ``$sha``
$size KB

Check the download matches before installing, and paste that hash into VirusTotal if
you want a second opinion — a hash lookup reports what dozens of engines already think,
without uploading the file or scanning anything locally.

``sha256sum boresight-$($Tag.TrimStart('v')).apk``
``certutil -hashfile boresight-$($Tag.TrimStart('v')).apk SHA256``

Tested on a Galaxy S22 Ultra (Android 15) and a Galaxy S25 Ultra (Android 17). Two
phones, one manufacturer, one country — behaviour on anything else is unverified.
"@ -replace "`r`n", "`n" | ForEach-Object {
    [System.IO.File]::WriteAllText($notes, $_, (New-Object System.Text.UTF8Encoding $false))
}

Push-Location $root
& git add -A
& git commit -q -m "Build $Tag" 2>$null
& git push -q origin HEAD 2>$null
& gh release create $Tag $apk --title "Boresight $($Tag.TrimStart('v'))" --notes-file $notes
Pop-Location
Remove-Item $apk -Force -ErrorAction SilentlyContinue
Remove-Item $notes -Force -ErrorAction SilentlyContinue
Write-Host "sha256 $sha" -ForegroundColor Cyan
Write-Host "released $Tag" -ForegroundColor Green
