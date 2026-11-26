/*
 * Where does the in-browser viewer stop being the right tool?
 *
 * The collection plan is ~5,000 miles a month, continuously. Guessing at the ceiling
 * is worthless; this measures it. Reports read time, memory held and the cost of a
 * filter change — the three things that decide whether a tab stays usable.
 *
 *   node viewer/test/scale-bench.mjs <bundle.json> [...]
 */
import { readFileSync } from "node:fs";
import { TextDecoder } from "node:util";
import vm from "node:vm";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));

class FakeFile {
  constructor(b, n) { this._b = b; this.size = b.length; this.name = n; }
  slice(a, z) { return new FakeFile(this._b.subarray(a, z), this.name); }
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

function newSandbox() {
  const s = {
    TextDecoder, FileReader: FakeFileReader, Map, console, setTimeout, setImmediate,
    Float64Array, Float32Array, Int32Array, Int16Array, Uint8Array, Array, JSON, Math,
    isNaN, parseInt, Object, String, Number
  };
  vm.createContext(s);
  vm.runInContext(readFileSync(path.join(here, "..", "src", "store.js"), "utf8"), s);
  return s;
}

const BASE = {
  sessions: {}, carriers: {}, bands: {}, techs: {}, dasOnly: false,
  cell: true, wifi: true, showHits: true, rsrpMin: -140, rsrpMax: 0,
  colour: "rsrp", aggregate: "worst"
};

function mb(b) { return (b / 1048576).toFixed(1); }

async function bench(file) {
  const buf = readFileSync(file);
  const sb = newSandbox();
  const DB = new sb.MemoryStore();
  const t0 = Date.now();

  await new Promise((res, rej) => {
    new sb.BundleStream(new FakeFile(buf, path.basename(file)), {
      obs: o => DB.addObs(o), session: s => DB.addSession(s), hit: h => DB.addHit(h),
      progress: () => {}, error: m => rej(new Error(m)), done: () => res()
    }).start();
  });

  const readMs = Date.now() - t0;
  const t1 = Date.now();
  const st = DB.setFilters(BASE);
  const filterMs = Date.now() - t1;

  // A frame at continental zoom: every fine cell is in view, which is the worst case.
  const all = { south: -90, north: 90, west: -180, east: 180 };
  const proj = (la, lo) => ({ x: (lo + 180) * 8, y: (90 - la) * 8 });
  const t2 = Date.now();
  let cells = 0;
  for (let k = 0; k < 5; k++) cells = DB.bins(all, proj, 7, "worst").cells.length;
  const frameMs = (Date.now() - t2) / 5;

  const t3 = Date.now();
  DB.setFilters({ ...BASE, dasOnly: true });
  const refilterMs = Date.now() - t3;

  const heap = process.memoryUsage().heapUsed;
  console.log(
    [path.basename(file).padEnd(30),
     String(DB.n).padStart(9) + " pts",
     (mb(buf.length) + " MB").padStart(10),
     ("read " + (readMs / 1000).toFixed(1) + "s").padStart(11),
     ("filter " + filterMs + "ms").padStart(14),
     ("refilter " + refilterMs + "ms").padStart(16),
     ("frame " + frameMs.toFixed(0) + "ms").padStart(13),
     ("cols " + mb(DB.bytes()) + " MB").padStart(15),
     ("heap " + mb(heap) + " MB").padStart(16),
     (st.fineCells + " cells").padStart(13),
     (cells + " drawn").padStart(12)
    ].join(" "));
}

const files = process.argv.slice(2);
if (!files.length) { console.error("usage: scale-bench.mjs <bundle.json> [...]"); process.exit(2); }
for (const f of files) {
  try { await bench(f); }
  catch (e) { console.log(path.basename(f).padEnd(30) + "  FAILED: " + e.message); }
}
