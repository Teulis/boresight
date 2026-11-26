# Desktop tests for the parts of Boresight that are pure Java.
#
# These exist because the treemap and the du parser fail quietly: a wrong layout still
# draws a plausible picture, and a wrong parse still builds a plausible tree. Both were
# written while the phone was unavailable, and both caught real bugs.
#
#   .\test\run.ps1
param()
$ErrorActionPreference = "Continue"
$root = Split-Path $PSScriptRoot -Parent
$out  = Join-Path $PSScriptRoot "build"
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $out | Out-Null

$src = @(
    "$root\src\com\boresight\Squarify.java",
    "$root\src\com\boresight\Kind.java",
    "$root\src\com\boresight\TreeNode.java",
    "$root\src\com\boresight\DuTree.java",
    "$root\src\com\boresight\Walk.java",
    "$root\src\com\boresight\Locate.java",
    "$root\src\com\boresight\Sampler.java",
    "$root\src\com\boresight\Store.java",
    "$root\src\com\boresight\Sync.java",
    "$root\src\com\boresight\Prefs.java",
    "$root\src\com\boresight\Rf.java",
    # Rf dumps the component carriers, so these come along: Agg for the carriers and
    # Perms because Agg asks it whether READ_PHONE_STATE was granted.
    "$root\src\com\boresight\Agg.java",
    "$root\src\com\boresight\Perms.java",
    "$root\src\com\boresight\Plmn.java",
    "$root\src\com\boresight\Noise.java",
    "$root\src\com\boresight\Json.java",
    "$root\src\com\boresight\Nbt.java",
    "$root\src\com\boresight\Blob.java",
    "$root\src\com\boresight\Rate.java",
    "$root\src\com\boresight\Mifare.java",
    "$root\src\com\boresight\ShLine.java",
    "$PSScriptRoot\AggTest.java",
    "$PSScriptRoot\LocateTest.java",
    "$PSScriptRoot\NoiseTest.java",
    "$PSScriptRoot\JsonTest.java",
    "$PSScriptRoot\BlobTest.java",
    "$PSScriptRoot\RateTest.java",
    "$PSScriptRoot\MifareTest.java",
    "$PSScriptRoot\TermTest.java",
    "$PSScriptRoot\KindTest.java",
    "$PSScriptRoot\WalkTest.java",
    "$PSScriptRoot\SquarifyTest.java",
    "$PSScriptRoot\DuTreeTest.java"
)
$androidJar = "C:\tools\android-sdk\platforms\android-35\android.jar"
& javac -d $out -classpath $androidJar @src
if ($LASTEXITCODE -ne 0) { Write-Host "compile failed" -ForegroundColor Red; exit 1 }

$fail = 0
foreach ($suite in @("SquarifyTest", "DuTreeTest", "KindTest", "WalkTest", "LocateTest", "NoiseTest", "JsonTest", "BlobTest", "RateTest", "MifareTest", "TermTest", "AggTest")) {
    Write-Host "`n=== $suite ===" -ForegroundColor Cyan
    & java -cp "$out;$androidJar" $suite
    if ($LASTEXITCODE -ne 0) { $fail++ }
}
if ($fail -gt 0) { Write-Host "`n$fail suite(s) failed" -ForegroundColor Red; exit 1 }
Write-Host "`nall suites passed" -ForegroundColor Green
