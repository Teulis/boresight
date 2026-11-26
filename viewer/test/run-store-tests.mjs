/*
 * Headless tests for the viewer's data layer.
 *
 * The DOM half of the viewer needs a browser, but the half that can be wrong *quietly*
 * does not: a streaming parser that drops the last record of a slice, a column that
 * turns an absent reading into zero, a filter that counts rows it should have excluded.
 * None of those throw. They just produce a map that looks fine and is wrong, which is
 * exactly the failure this project keeps running into.
 *
 *   node --experimental-default-type=module viewer/test/run-store-tests.mjs <bundle.json>
 */
import { readFileSync } from "node:fs";
import { TextDecoder } from "node:util";
import vm from "node:vm";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const bundlePath = process.argv[2];
if (!bundlePath) { console.error("usage: run-store-tests.mjs <bundle.json>"); process.exit(2); }

/* ---- minimal File / FileReader over a Buffer, enough for BundleStream ---- */
const buf = readFileSync(bundlePath);
class FakeFile {
  constructor(b, name) { this._b = b; this.size = b.length; this.name = name; }
  slice(a, z) { const f = new FakeFile(this._b.subarray(a, z), this.name); return f; }
}
class FakeFileReader {
  readAsArrayBuffer(blob) {
    // Async on purpose: BundleStream schedules its next slice from onload, and a
    // synchronous callback would recurse until the stack blew on a large file.
    setImmediate(() => {
      this.result = blob._b.buffer.slice(
        blob._b.byteOffset, blob._b.byteOffset + blob._b.byteLength);
      this.onload();
    });
  }
}

const sandbox = {
  TextDecoder, FileReader: FakeFileReader, Map, Set, console,
  setTimeout, setImmediate, performance: { now: () => Date.now() },
  Float64Array, Float32Array, Int32Array, Int16Array, Uint8Array, Array, JSON, Math,
  isNaN, parseInt, Object, String, Number
};
vm.createContext(sandbox);
vm.runInContext(readFileSync(path.join(here, "..", "src", "store.js"), "utf8"), sandbox);

let fails = 0;
function ok(what, cond, extra) {
  console.log((cond ? "  ok   " : "  FAIL ") + what + (extra ? "  [" + extra + "]" : ""));
  if (!cond) fails++;
}

const file = new FakeFile(buf, path.basename(bundlePath));
const DB = new sandbox.MemoryStore();
const t0 = Date.now();

const stream = new sandbox.BundleStream(file, {
  obs: o => DB.addObs(o),
  session: s => DB.addSession(s),
  hit: h => DB.addHit(h),
  progress: () => {},
  error: msg => { console.error("STREAM ERROR: " + msg); process.exit(1); },
  done: (meta, counts) => finish(meta, counts)
});
stream.start();

function finish(meta, counts) {
  const secs = (Date.now() - t0) / 1000;
  DB.meta = meta;

  // Ground truth from a normal parse of the same file, so the streaming reader is
  // checked against something independent rather than against itself.
  const truth = JSON.parse(buf.toString("utf8"));

  console.log(`\n-- streaming reader --  (${secs.toFixed(1)}s, ${(file.size/1048576).toFixed(2)} MB)`);
  ok("read every observation", counts.obs === truth.obs.length,
     `${counts.obs} vs ${truth.obs.length}`);
  ok("read every session", counts.sessions === truth.sessions.length,
     `${counts.sessions} vs ${truth.sessions.length}`);
  ok("read every hit", counts.hits === truth.hits.length,
     `${counts.hits} vs ${truth.hits.length}`);
  ok("header fields survived slicing", meta.app === "boresight" && meta.format === 1
     && meta.deviceName === truth.deviceName, meta.deviceName);

  console.log("\n-- absent is not zero --");
  // Find a row the generator deliberately left a hole in and prove it stayed a hole.
  let noTac = -1, noAcc = -1, noBandWifi = -1;
  for (let i = 0; i < truth.obs.length && (noTac < 0 || noAcc < 0 || noBandWifi < 0); i++) {
    const o = truth.obs[i];
    if (noTac < 0 && o.kind === "cell" && o.tac === undefined) noTac = i;
    if (noAcc < 0 && o.acc === undefined) noAcc = i;
    if (noBandWifi < 0 && o.kind === "wifi") noBandWifi = i;
  }
  ok("a bundle row with no TAC exists to test with", noTac >= 0);
  if (noTac >= 0) ok("missing TAC reads back as null, not 0", DB.row(noTac).tac === null,
                     String(DB.row(noTac).tac));
  if (noAcc >= 0) ok("missing accuracy reads back as null, not 0",
                     DB.row(noAcc).acc === null, String(DB.row(noAcc).acc));
  if (noBandWifi >= 0) ok("wifi row has no band and reports null",
                          DB.row(noBandWifi).band === null, String(DB.row(noBandWifi).band));

  // Round-trip a fully populated cell row field by field.
  let full = truth.obs.findIndex(o => o.kind === "cell" && o.tac !== undefined
                                 && o.ta !== undefined && o.qual !== undefined);
  console.log("\n-- row round-trip --");
  if (full >= 0) {
    const a = truth.obs[full], b = DB.row(full);
    ok("lat", Math.abs(a.lat - b.lat) < 1e-9);
    ok("lon", Math.abs(a.lon - b.lon) < 1e-9);
    ok("dbm", a.dbm === b.dbm, `${a.dbm} vs ${b.dbm}`);
    ok("band", a.band === b.band);
    ok("pci", a.pci === b.pci);
    ok("tac", a.tac === b.tac);
    ok("arfcn (EARFCN)", a.arfcn === b.arfcn);
    ok("dlMhz survives Float32", Math.abs(a.dlMhz - b.dlMhz) < 0.05,
       `${a.dlMhz} vs ${b.dlMhz}`);
    ok("carrier", a.carrier === b.carrier, b.carrier);
    ok("tech", a.tech === b.tech);
    ok("ident", a.ident === b.ident, b.ident);
    ok("ta", a.ta === b.ta);
    ok("serving", !!a.serving === b.serving);
  } else ok("found a fully populated row", false);

  console.log("\n-- filters --");
  const base = {
    sessions: {}, carriers: {}, bands: {}, techs: {}, dasOnly: false,
    cell: true, wifi: true, showHits: true, rsrpMin: -140, rsrpMax: 0,
    colour: "rsrp", aggregate: "worst"
  };
  let st = DB.setFilters(base);
  const positioned = truth.obs.filter(o => typeof o.lat === "number"
                                      && (o.kind === "cell" || o.kind === "wifi")).length;
  ok("everything positioned is visible with no filters", st.visible === positioned,
     `${st.visible} vs ${positioned}`);

  st = DB.setFilters({ ...base, wifi: false });
  const cellsOnly = truth.obs.filter(o => o.kind === "cell").length;
  ok("turning Wi-Fi off leaves exactly the cell rows", st.visible === cellsOnly,
     `${st.visible} vs ${cellsOnly}`);

  const DAS = { 2:1,4:1,5:1,12:1,13:1,14:1,25:1,26:1,29:1,66:1 };
  st = DB.setFilters({ ...base, wifi: false, dasOnly: true });
  const dasTruth = truth.obs.filter(o => o.kind === "cell" && DAS[o.band]).length;
  ok("DAS-only matches a hand count", st.visible === dasTruth,
     `${st.visible} vs ${dasTruth}`);
  ok("DAS-only actually removed something", st.visible < cellsOnly,
     `${st.visible} < ${cellsOnly}`);

  // The pitfall: a row with no RSRP must not survive a floor, because it cannot be
  // shown to clear it.
  st = DB.setFilters({ ...base, wifi: false, rsrpMin: -100 });
  const strong = truth.obs.filter(o => o.kind === "cell"
                                  && typeof o.dbm === "number" && o.dbm >= -100).length;
  ok("an RSRP floor counts only rows that have a reading", st.visible === strong,
     `${st.visible} vs ${strong}`);

  console.log("\n-- coverage --");
  st = DB.setFilters({ ...base, wifi: false });
  const withRsrp = truth.obs.filter(o => o.kind === "cell" && typeof o.dbm === "number");
  const good = withRsrp.filter(o => o.dbm >= -95).length;
  const expectPct = 100 * good / withRsrp.length;
  ok("coverage denominator excludes rows with no RSRP",
     st.coverage.total === withRsrp.length, `${st.coverage.total} vs ${withRsrp.length}`);
  ok("coverage percentage matches a hand count",
     Math.abs(st.coverage.pct - expectPct) < 0.001,
     `${st.coverage.pct.toFixed(3)} vs ${expectPct.toFixed(3)}`);

  console.log("\n-- binning conserves readings --");
  // A bin covering everything must account for every visible reading. If aggregation
  // silently dropped points the map would understate coverage and never say so.
  st = DB.setFilters(base);
  const all = { south: -90, north: 90, west: -180, east: 180 };
  const flat = (lat, lon) => ({ x: (lon + 180) * 10, y: (90 - lat) * 10 });
  const binned = DB.bins(all, flat, 7, "worst");
  ok("bins account for every visible reading", binned.points === st.visible,
     `${binned.points} vs ${st.visible}`);
  ok("bins are fewer than points (it actually aggregated)",
     binned.cells.length < st.visible, `${binned.cells.length} cells`);
  ok("fine cells are fewer than points", st.fineCells < st.total,
     `${st.fineCells} fine cells for ${st.total} points`);

  // Zooming in must yield more bins, never fewer: that is the whole claim of
  // zoom-dependent detail.
  const coarse = DB.bins(all, (la, lo) => ({ x:(lo+180)*2, y:(90-la)*2 }), 7, "worst");
  const fine   = DB.bins(all, (la, lo) => ({ x:(lo+180)*60, y:(90-la)*60 }), 7, "worst");
  ok("more bins at higher zoom", fine.cells.length > coarse.cells.length,
     `${coarse.cells.length} -> ${fine.cells.length}`);

  console.log("\n-- bin statistic --");
  const b2 = DB.bins(all, flat, 7, "worst");
  let badMin = 0;
  for (const c of b2.cells) {
    if (c.min !== -32768 && c.max !== -32768 && c.min > c.max) badMin++;
  }
  ok("bin min never exceeds bin max", badMin === 0, `${badMin} bad`);

  console.log("\n-- viewport point query --");
  const anyRow = DB.row(full >= 0 ? full : 0);
  const d = 0.01;
  const box = { south: anyRow.lat - d, north: anyRow.lat + d,
                west: anyRow.lon - d, east: anyRow.lon + d };
  DB.setFilters(base);
  const pr = DB.points(box, 60000);
  let outside = 0;
  for (const id of pr.ids) {
    const r = DB.row(id);
    if (r.lat < box.south || r.lat > box.north || r.lon < box.west || r.lon > box.east) outside++;
  }
  ok("viewport query returns something", pr.ids.length > 0, `${pr.ids.length} points`);
  ok("viewport query returns nothing outside the box", outside === 0, `${outside} stray`);
  const bruteForce = truth.obs.filter(o => typeof o.lat === "number"
      && o.lat >= box.south && o.lat <= box.north
      && o.lon >= box.west && o.lon <= box.east
      && (o.kind === "cell" || o.kind === "wifi")).length;
  ok("viewport query matches a brute-force count",
     pr.ids.length === bruteForce, `${pr.ids.length} vs ${bruteForce}`);

  console.log("\n-- sessions --");
  const ses = DB.sessions();
  ok("every session is summarised", ses.length === truth.sessions.length);
  const totalPts = ses.reduce((a, s) => a + s.points, 0);
  ok("session point counts add up to the cell rows", totalPts === cellsOnly,
     `${totalPts} vs ${cellsOnly}`);
  ok("a session reports distance", ses.some(s => s.dist > 1000));
  ok("a null label survives as null rather than the string 'null'",
     ses.some(s => s.label === null));

  console.log("\n-- memory --");
  const bytes = DB.bytes();
  const perPoint = bytes / DB.n;
  console.log(`  ${(bytes/1048576).toFixed(1)} MB held for ${DB.n.toLocaleString()} points `
            + `(${perPoint.toFixed(0)} bytes/point)`);
  ok("under 200 bytes per point", perPoint < 200, perPoint.toFixed(0) + " B");

  console.log("\n-- format guard --");
  const future = Buffer.from(buf.toString("utf8").replace('"format":1', '"format":9'));
  const f2 = new FakeFile(future, "future.json");
  const DB2 = new sandbox.MemoryStore();
  let refused = null;
  new sandbox.BundleStream(f2, {
    obs(){}, session(){}, hit(){}, progress(){},
    error: m => { refused = m; },
    done: () => {}
  }).start();
  setTimeout(() => {
    ok("a newer format is refused, not guessed at", !!refused && /format 9/.test(refused));
    console.log("\n" + (fails ? fails + " failure(s)" : "all checks passed"));
    process.exit(fails ? 1 : 0);
  }, 400);
}
