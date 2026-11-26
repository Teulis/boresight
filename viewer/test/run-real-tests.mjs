/*
 * Format 2, gzipped NDJSON chunks, and exact de-duplication — tested against real data.
 *
 * The claim this file exists to prove is narrow and important: loading the same
 * readings twice must not move a single number. Double counting would not crash and
 * would not look wrong — it would inflate the totals and shift the coverage percentage
 * into something that still reads as plausible. That is the failure worth a test.
 *
 *   node viewer/test/run-real-tests.mjs <bundle.json> <chunkDir>
 *
 * The data lives outside the repository on purpose and is never copied into it.
 */
import { readFileSync, readdirSync } from "node:fs";
import { TextDecoder } from "node:util";
// TextDecoderStream and DecompressionStream are globals in Node, not node:util exports.
import vm from "node:vm";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { Blob } from "node:buffer";

const here = path.dirname(fileURLToPath(import.meta.url));
const [bundlePath, chunkDir] = process.argv.slice(2);
if (!bundlePath || !chunkDir) {
  console.error("usage: run-real-tests.mjs <bundle.json> <chunkDir>");
  process.exit(2);
}

/* A File good enough for both readers: slice() for the bundle path, stream() for the
 * chunk path. Backed by a Blob so the real DecompressionStream pipeline is exercised
 * rather than a stand-in for it. */
function fileOf(buf, name) {
  const blob = new Blob([buf]);
  return {
    name, size: buf.length,
    _b: buf,
    slice(a, z) { return fileOf(buf.subarray(a, z), name); },
    stream() { return blob.stream(); }
  };
}
class FakeFileReader {
  readAsArrayBuffer(blob) {
    setImmediate(() => {
      this.result = blob._b.buffer.slice(
        blob._b.byteOffset, blob._b.byteOffset + blob._b.byteLength);
      this.onload();
    });
  }
}

const sandbox = {
  TextDecoder, TextDecoderStream, DecompressionStream, FileReader: FakeFileReader,
  Map, Set, console, setTimeout, setImmediate, Promise,
  Float64Array, Float32Array, Int32Array, Int16Array, Uint8Array, Array, JSON, Math,
  isNaN, parseInt, Object, String, Number, Infinity
};
vm.createContext(sandbox);
vm.runInContext(readFileSync(path.join(here, "..", "src", "store.js"), "utf8"), sandbox);

let fails = 0;
function ok(what, cond, extra) {
  console.log((cond ? "  ok   " : "  FAIL ") + what + (extra ? "  [" + extra + "]" : ""));
  if (!cond) fails++;
}

const BASE = {
  sessions: {}, carriers: {}, bands: {}, techs: {}, dasOnly: false,
  cell: true, wifi: true, ble: true, showHits: true, rsrpMin: -140, rsrpMax: 0,
  colour: "rsrp", aggregate: "worst"
};

function loadInto(store, files) {
  return new Promise((res, rej) => {
    sandbox.loadFiles(files, store, {
      file: () => {}, progress: () => {},
      done: r => r.errors.length ? rej(new Error(r.errors[0].file + ": " + r.errors[0].message))
                                 : res(r)
    });
  });
}

/* A snapshot of everything a user would read off the screen. If de-duplication is
 * exact, every one of these is identical after a second load of the same data. */
function snapshot(store) {
  const st = store.setFilters(BASE);
  return {
    points: st.total, visible: st.visible,
    covTotal: st.coverage.total, covOk: st.coverage.ok,
    covPct: st.coverage.pct.toFixed(6), covNone: st.coverage.noReading,
    fine: st.fineCells, coarse: st.coarseCells,
    sessions: store.sessions().length, hits: store.hits().length,
    miles: store.sessions().reduce((a, s) => a + s.dist, 0).toFixed(3)
  };
}

const bundleBuf = readFileSync(bundlePath);
const truth = JSON.parse(bundleBuf.toString("utf8"));
const chunkFiles = readdirSync(chunkDir).filter(f => f.endsWith(".ndjson.gz")).sort();

console.log(`-- real bundle --  ${path.basename(bundlePath)}, `
  + `${(bundleBuf.length / 1048576).toFixed(2)} MB`);
ok("the file really is format 2", truth.format === 2, String(truth.format));
ok("rows carry dev and seq",
   truth.obs.every(o => o.dev !== undefined && typeof o.seq === "number"));

/* ---- 1. a format-2 bundle loads at all ---- */
const A = new sandbox.MemoryStore();
await loadInto(A, [fileOf(bundleBuf, path.basename(bundlePath))]);
const sA = snapshot(A);
ok("format 2 is accepted, not refused", A.n > 0, A.n + " points");
ok("every observation read", A.n === truth.obs.length, `${A.n} vs ${truth.obs.length}`);
ok("the detector hit survived", A.hits().length === truth.hits.length);
ok("sessions carried their labels",
   A.sessions().filter(s => !s.synthetic).length === truth.sessions.length,
   `${A.sessions().filter(s => !s.synthetic).length} labelled`);

const kinds = {};
for (let i = 0; i < A.n; i++) {
  const k = ["cell","wifi","ble","other"][A.kind.a[i]];
  kinds[k] = (kinds[k] || 0) + 1;
}
console.log("  kinds held: " + JSON.stringify(kinds));
ok("BLE rows are kept, not silently dropped", (kinds.ble || 0) > 0, String(kinds.ble));
ok("every positioned row is visible under default filters", sA.visible === A.n,
   `${sA.visible} of ${A.n}`);

/* The real file carries 16 BLE rows with dbm=127 — Android's "unavailable" sentinel.
 * They must load, keep their position, and be held as having no reading rather than
 * the strongest reading in the survey. */
let sentinel = 0, positive = 0;
for (let i = 0; i < A.n; i++) {
  if (A.dbm.a[i] !== -32768 && A.dbm.a[i] >= 0) positive++;
}
for (const o of truth.obs) if (typeof o.dbm === "number" && o.dbm > 0) sentinel++;
ok("the file does contain the +dBm sentinel to test against", sentinel > 0, String(sentinel));
ok("no positive dBm is stored as a reading", positive === 0, String(positive));
ok("NUL padding stripped from names",
   !A.dName.vals.some(v => v && v.includes("\u0000")),
   A.dName.vals.filter(v => v && v.includes("\u0000")).length + " left");

/* ---- 2. the same bundle twice changes nothing ---- */
console.log("\n-- the same bundle loaded twice --");
await loadInto(A, [fileOf(bundleBuf, path.basename(bundlePath))]);
const sA2 = snapshot(A);
for (const k of Object.keys(sA)) {
  ok("unchanged: " + k, String(sA[k]) === String(sA2[k]), `${sA[k]} -> ${sA2[k]}`);
}
ok("every row of the second load was recognised as a duplicate",
   A.dupes === truth.obs.length, `${A.dupes} vs ${truth.obs.length}`);
ok("no row was un-deduplicable", A.unidentified === 0, String(A.unidentified));

/* ---- 3. chunks ---- */
console.log(`\n-- ${chunkFiles.length} gzipped NDJSON chunks --`);
const C = new sandbox.MemoryStore();
const chunkBlobs = chunkFiles.map(f =>
  fileOf(readFileSync(path.join(chunkDir, f)), f));
await loadInto(C, chunkBlobs);
const sC = snapshot(C);
ok("all chunks read", C.n === truth.obs.length, `${C.n} vs ${truth.obs.length}`);
ok("chunk rows got their device from the header",
   C.dDev.vals.length === 1 && C.dDev.vals[0] === truth.device,
   JSON.stringify(C.dDev.vals));
ok("no chunk row was left un-deduplicable", C.unidentified === 0, String(C.unidentified));
ok("chunks carry no hits", C.hits().length === 0);
ok("sessions from chunks are synthesised and flagged",
   C.sessions().length > 0 && C.sessions().every(s => s.synthetic),
   C.sessions().length + " sessions, all unlabelled");
ok("synthesised sessions still get times from their rows",
   C.sessions().every(s => s.started && s.ended));

/* The headline: chunks and the bundle describe the same readings. */
console.log("\n-- chunks vs the bundle they came from --");
ok("same point count", sC.points === sA.points, `${sC.points} vs ${sA.points}`);
ok("same coverage percentage", sC.covPct === sA.covPct, `${sC.covPct} vs ${sA.covPct}`);
ok("same fine cell count", sC.fine === sA.fine, `${sC.fine} vs ${sA.fine}`);
ok("same mileage", sC.miles === sA.miles, `${sC.miles} vs ${sA.miles}`);

/* ---- 4. the overlap case: bundle + chunks together ---- */
console.log("\n-- bundle and its own chunks loaded together --");
const M = new sandbox.MemoryStore();
await loadInto(M, [fileOf(bundleBuf, path.basename(bundlePath))]);
const before = snapshot(M);
await loadInto(M, chunkBlobs);
const after = snapshot(M);
ok("adding the chunks adds no points", after.points === before.points,
   `${before.points} -> ${after.points}`);
ok("coverage does not move", after.covPct === before.covPct,
   `${before.covPct} -> ${after.covPct}`);
ok("mileage does not move", after.miles === before.miles);
ok("every chunk row was matched against the bundle",
   M.dupes === truth.obs.length, `${M.dupes} dupes`);

/* ---- 5. loading chunks twice ---- */
console.log("\n-- the same chunks twice --");
const D = new sandbox.MemoryStore();
await loadInto(D, chunkBlobs);
const d1 = snapshot(D);
await loadInto(D, chunkBlobs);
const d2 = snapshot(D);
ok("totals unchanged", d1.points === d2.points, `${d1.points} -> ${d2.points}`);
ok("coverage unchanged", d1.covPct === d2.covPct);

/* ---- 6. rows with no identity must not collide with each other ---- */
console.log("\n-- pre-identity rows (no dev, no seq) --");
const P = new sandbox.MemoryStore();
for (let i = 0; i < 500; i++) {
  P.addObs({ kind: "cell", lat: 40 + i * 1e-4, lon: -105, dbm: -90, session: 1 });
}
ok("500 identity-less rows all load", P.n === 500, String(P.n));
ok("none were treated as duplicates of each other", P.dupes === 0, String(P.dupes));
ok("they are counted as un-deduplicable", P.unidentified === 500, String(P.unidentified));

/* ---- 7. guards ---- */
console.log("\n-- guards --");
const future = Buffer.from(bundleBuf.toString("utf8").replace('"format":2', '"format":9'));
let refused = null;
await new Promise(res => {
  new sandbox.BundleStream(fileOf(future, "future.json"), {
    obs(){}, session(){}, hit(){}, progress(){},
    error: m => { refused = m; res(); }, done: () => res()
  }).start();
});
ok("a newer bundle format is still refused", !!refused && /format 9/.test(refused));
ok("the refusal names the bundle format, not the chunk format",
   !!refused && /understands up to 2/.test(refused), (refused || "").slice(0, 60));

console.log("\n" + (fails ? fails + " failure(s)" : "all real-data checks passed"));
process.exit(fails ? 1 : 0);
