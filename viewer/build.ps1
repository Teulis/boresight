# Build the single-file Boresight survey viewer.
#
#   .\viewer\build.ps1
#
# Splices the vendored Leaflet and the data layer into the template and writes one
# self-contained HTML file. Vendored rather than loaded from a CDN on purpose: the
# viewer must make no network request except OpenStreetMap tiles, and a CDN script tag
# is both a request and a dependency on someone else's uptime for a tool that may be
# opened in a hotel with no signal.
param()
$ErrorActionPreference = "Stop"
$root   = $PSScriptRoot
$src    = Join-Path $root "src"
$vendor = Join-Path $root "vendor"
$out    = Join-Path $root "boresight-viewer.html"

foreach ($f in @("$src\viewer.template.html", "$src\store.js",
                 "$vendor\leaflet.js", "$vendor\leaflet.css")) {
    if (-not (Test-Path $f)) { Write-Host "missing: $f" -ForegroundColor Red; exit 1 }
}

$html = [System.IO.File]::ReadAllText("$src\viewer.template.html")
$css  = [System.IO.File]::ReadAllText("$vendor\leaflet.css")
$js   = [System.IO.File]::ReadAllText("$vendor\leaflet.js")
$store= [System.IO.File]::ReadAllText("$src\store.js")

# Leaflet's stylesheet references marker-icon.png and friends by relative URL. Those are
# only fetched if an L.Marker is created, and this viewer never creates one - it draws
# to a canvas and uses L.circleMarker for the handful of detector hits. Strip the rules
# anyway so a stray request can never happen from a file:// page.
$css = $css -replace 'url\((?!data:)[^)]*\)', 'none'

$html = $html.Replace('/*__LEAFLET_CSS__*/', $css)
$html = $html.Replace('/*__LEAFLET_JS__*/',  $js)
$html = $html.Replace('/*__STORE_JS__*/',    $store)

if ($html -match '__LEAFLET_CSS__|__LEAFLET_JS__|__STORE_JS__') {
    Write-Host "a placeholder was not replaced" -ForegroundColor Red; exit 1
}

[System.IO.File]::WriteAllText($out, $html, (New-Object System.Text.UTF8Encoding $false))
"built {0}  {1:N0} KB" -f (Split-Path $out -Leaf), ((Get-Item $out).Length / 1KB)
