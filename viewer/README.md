# Boresight survey viewer

A desktop viewer for survey data. Double-click **`boresight-viewer.html`** — no
install, no runtime, no server. Drop files onto it:

- **whole bundles**, `boresight-<device>-<stamp>.json`, formats 1 and 2
- **chunks**, `bs-<dev>-<from>-<to>-<stamp>.ndjson.gz` — gzipped NDJSON, what
  continuous collection actually produces

Select as many at once as you like; a folder of chunks is the normal case, not one
file. **Add…** merges more into the view, and so does dropping onto an already-loaded
window (hold Shift to replace instead).

Overlapping files are fine and expected — the chunks from a drive and the bundle
exported afterwards describe the same readings. Rows are de-duplicated exactly on
`(dev, seq)`, so loading the same data twice does not move a single number. Measured
against the real 43,630-point file: a second load adds **0 points** and leaves the
coverage figure identical to six decimal places.

Chunks need `DecompressionStream`, which Chromium and Edge have. If your browser does
not, the viewer says so plainly rather than failing obscurely.

Nothing is uploaded. The only network request the page makes is for OpenStreetMap
basemap tiles, and the **Offline** button stops even those. Leaflet is inlined rather
than loaded from a CDN, so the viewer works in a hotel with no signal and makes no
request you did not ask for.

## What it does

- **Map** of cell, Wi-Fi and BLE sightings with the detector hits, coloured by RSRP
  against the same −95 dBm / 95 % criterion the app uses.
- **Zoom-dependent detail.** Zoomed out you see binned summaries and the status line
  says so; from z15 up you see every individual point and can click one.
- **Detail pane** with everything recorded for a sighting. Fields the phone did not
  record are shown as *not reported*, never as a blank or a zero.
- **Filters**: session, carrier, band, technology, RSRP floor, and one-click
  **DAS bands only** (700, cell 850, PCS, AWS, FirstNet band 14).
- **Session summary**: points, duration, distance, carriers and bands per session.

## Building

```powershell
.\viewer\build.ps1
```

Splices `src/viewer.template.html`, `src/store.js` and the vendored Leaflet in
`vendor/` into the single `boresight-viewer.html`. Edit the sources, not the output.

## Testing

```powershell
# format 2, chunks, and de-duplication, against real data
node viewer\test\run-real-tests.mjs <bundle.json> <chunkDir>

# data layer, against a bundle
node viewer\test\run-store-tests.mjs <bundle.json>

# the real page in headless Edge; a directory argument expands to the files in it
node viewer\test\browser-check.mjs <browser.exe> viewer\boresight-viewer.html <file|dir>…

# where it stops scaling
node viewer\test\scale-bench.mjs <bundle.json> [...]
```

The tests exist for the failures that do not throw: a streaming parser that drops the
last record of a slice, a column that turns an absent reading into a zero, a filter
that quietly excludes rows. None of those crash; they just draw a map that looks fine
and is wrong.

## How big a bundle can it take?

Measured on this machine, not estimated. The reader streams the file in slices and
never holds it as one string, so the limit is the columnar store, not the JSON.

| bundle | points | file | read | filter change | memory held |
|---|---|---|---|---|---|
| ~7 h drive | 49 k | 12.6 MB | 0.2 s | 24 ms | 6 MB |
| ~16 h drive | 109 k | 28.0 MB | 0.5 s | 49 ms | 13 MB |
| ~1 month | 764 k | 195.6 MB | 3.6 s | 341 ms | 87 MB |

Frame cost scales with **road covered**, not with points, because the renderer walks
bins and a bin is a length of road. A second, coarser pyramid level is rolled up once
per filter change to bound that:

| road covered | fine cells | frame, fine only | frame, with coarse level |
|---|---|---|---|
| 1,700 mi | 50 k | 13 ms | 13 ms |
| 5,000 mi (1 month) | 146 k | 39 ms | 25 ms |
| 15,000 mi | 440 k | 106 ms | 23 ms |
| 60,000 mi (1 year) | 1.75 M | 415 ms | 25 ms |

**Where the line is.** A single bundle of up to about a million points — six weeks at
5,000 miles a month — loads in a few seconds and stays interactive. Past that the
columnar store is the constraint at roughly 115 bytes a point, and a year's archive as
one JSON file is not merely slow but impossible: 2.4 GB of UTF-8 is more characters
than one JavaScript string may hold.

The fix is a real database, and the viewer is built so only the data layer changes.
Everything the UI knows goes through the `DataSource` methods in `src/store.js`
(`setFilters`, `bins`, `points`, `row`, `facets`, `sessions`, `stats`); the rendering
code has no access to the underlying arrays and cannot scan them. Dropping in a
`SqliteSource` or an `HttpSource` that answers the same questions needs no change above
that seam.

## Things that bit us, recorded so they do not bite again

- **Absent is not zero.** `Sync.writeBundle` omits a field entirely when there is no
  reading. A row with no RSRP has no `dbm` key, and storing that as 0 would paint an
  impossible −0 dBm reading as the strongest signal on the map. Every numeric column
  carries a sentinel and nothing defaults to zero.
- **A default filter must exclude nothing.** The RSRP window first defaulted to a −40
  ceiling, which looked safe because 3GPP caps RSRP at −44 — but Wi-Fi RSSI reaches
  −30, so it silently dropped the strongest access points. The test caught it.
- **Cell identity collides.** When the network withholds the cell id the app falls back
  to `lte:pci<N>@<earfcn>`, and with only 504 PCIs that label repeats every few
  kilometres. The detail pane says so whenever it shows one. Nothing here aggregates by
  identity across distance.
- **Aggregation must announce itself.** A thinned scatter looks like sparse coverage,
  which is a lie the eye believes. When the view is binned the status line says how
  many readings each picture stands for.
- **A positive dBm is not a reading.** The real file carries 16 BLE rows with
  `dbm: 127`, Android's "RSSI unavailable" sentinel exported as though it were a
  measurement. Taken at face value it is the strongest signal in the dataset, so it
  wins every "best in bin" comparison and paints bright green. Stored as absent.
- **Defaults must not hide data.** BLE is a sixth of the real file. It was first
  filtered out by default, so 7,592 rows loaded, sat in memory and never appeared with
  nothing on screen admitting it. The browser test caught it by comparing the visible
  count against the loaded count.
- **Sessions are numbered per device.** Session 3 on one phone is not session 3 on
  another, so the key is `<dev>:<id>`. Merging two phones' chunks on the bare id would
  silently fuse unrelated drives.
- **Chunks carry no session labels.** There is no sessions array in a chunk, so ids
  turn up that nothing can name. Those are shown as "no label" with times derived from
  their own rows, rather than left blank or invented.
- **Two format numbers, counted separately.** The bundle format is at 2; the chunk
  format is at 1. Guarding one with the other's number would reject good files.

## Attribution

Map data © OpenStreetMap contributors (ODbL). Leaflet 1.9.4, BSD-2-Clause, vendored in
`vendor/` unmodified except that `url(...)` references are stripped from the CSS at
build time so no image request can escape a `file://` page.
