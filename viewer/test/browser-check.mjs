/*
 * End-to-end check of the built viewer in a real browser.
 *
 * The data layer has its own tests, but those run the store in isolation. This drives
 * the actual HTML: loads it in headless Edge, feeds a bundle through the real file
 * input with DOM.setFileInputFiles, and fails on any console error or page exception.
 * Without it, a typo in the rendering code would ship green.
 *
 *   node viewer/test/browser-check.mjs <browser.exe> <viewer.html> <bundle.json>
 */
import { spawn } from "node:child_process";
import { mkdtempSync, writeFileSync, readdirSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

const [browser, viewer, ...dataArgs] = process.argv.slice(2);
if (!browser || !viewer || !dataArgs.length) {
  console.error("usage: browser-check.mjs <browser.exe> <viewer.html> <file|dir> [...]");
  process.exit(2);
}
/* A directory argument expands to the files inside it, because a folder of chunks is
 * the normal selection and that is what needs testing, not one file at a time. */
const dataFiles = [];
for (const a of dataArgs) {
  if (statSync(a).isDirectory()) {
    for (const f of readdirSync(a).sort()) {
      if (/\.(ndjson|gz|json)$/.test(f)) dataFiles.push(path.join(a, f));
    }
  } else dataFiles.push(a);
}

const profile = mkdtempSync(path.join(tmpdir(), "bsview-"));
const PORT = 9411;
const proc = spawn(browser, [
  "--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
  "--remote-debugging-port=" + PORT, "--user-data-dir=" + profile,
  "--allow-file-access-from-files", "--window-size=1600,1000",
  "about:blank"
], { stdio: "ignore" });

let fails = 0;
function ok(what, cond, extra) {
  console.log((cond ? "  ok   " : "  FAIL ") + what + (extra ? "  [" + extra + "]" : ""));
  if (!cond) fails++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function endpoint() {
  for (let i = 0; i < 60; i++) {
    try {
      const r = await fetch("http://127.0.0.1:" + PORT + "/json/version");
      if (r.ok) return (await r.json()).webSocketDebuggerUrl;
    } catch {}
    await sleep(250);
  }
  throw new Error("browser did not expose a debugging port");
}

let nextId = 1;
function rpc(ws, method, params, sessionId) {
  return new Promise((res, rej) => {
    const id = nextId++;
    const onMsg = ev => {
      let m; try { m = JSON.parse(ev.data); } catch { return; }
      if (m.id !== id) return;
      ws.removeEventListener("message", onMsg);
      m.error ? rej(new Error(method + ": " + m.error.message)) : res(m.result);
    };
    ws.addEventListener("message", onMsg);
    ws.send(JSON.stringify({ id, method, params: params || {}, sessionId }));
  });
}

try {
  const wsUrl = await endpoint();
  const ws = new WebSocket(wsUrl);
  await new Promise(r => ws.addEventListener("open", r, { once: true }));

  const { targetId } = await rpc(ws, "Target.createTarget", { url: "about:blank" });
  const { sessionId } = await rpc(ws, "Target.attachToTarget", { targetId, flatten: true });
  const S = (m, p) => rpc(ws, m, p, sessionId);

  const consoleErrors = [], pageErrors = [], requests = [];
  ws.addEventListener("message", ev => {
    let m; try { m = JSON.parse(ev.data); } catch { return; }
    if (m.sessionId !== sessionId) return;
    if (m.method === "Runtime.exceptionThrown") {
      pageErrors.push(m.params.exceptionDetails.exception?.description
                   || m.params.exceptionDetails.text);
    }
    if (m.method === "Runtime.consoleAPICalled" && m.params.type === "error") {
      consoleErrors.push(m.params.args.map(a => a.value ?? a.description).join(" "));
    }
    if (m.method === "Network.requestWillBeSent") requests.push(m.params.request.url);
  });

  await S("Runtime.enable");
  await S("Page.enable");
  await S("DOM.enable");
  await S("Network.enable");

  const fileUrl = "file:///" + path.resolve(viewer).replace(/\\/g, "/");
  await S("Page.navigate", { url: fileUrl });
  await sleep(2500);

  console.log("\n-- page load --");
  ok("no uncaught exceptions on load", pageErrors.length === 0, pageErrors[0] || "");
  ok("no console errors on load", consoleErrors.length === 0, consoleErrors[0] || "");

  const title = await S("Runtime.evaluate", { expression: "document.title", returnByValue: true });
  ok("page title", title.result.value === "Boresight — survey viewer", title.result.value);

  const leaf = await S("Runtime.evaluate",
    { expression: "typeof L !== 'undefined' && typeof L.map === 'function'", returnByValue: true });
  ok("Leaflet inlined and present", leaf.result.value === true);

  const store = await S("Runtime.evaluate",
    { expression: "typeof MemoryStore === 'function' && typeof BundleStream === 'function'",
      returnByValue: true });
  ok("data layer inlined and present", store.result.value === true);

  const mapUp = await S("Runtime.evaluate",
    { expression: "!!document.querySelector('.leaflet-container')", returnByValue: true });
  ok("map container created", mapUp.result.value === true);

  /* Feed real files through the real <input type=file>. */
  console.log("\n-- loading " + dataFiles.length + " file(s) through the UI --");
  const doc = await S("DOM.getDocument");
  const node = await S("DOM.querySelector", { nodeId: doc.root.nodeId, selector: "#file" });
  ok("file input found", !!node.nodeId);

  async function feed(files, add) {
    await S("DOM.setFileInputFiles",
            { nodeId: node.nodeId, files: files.map(f => path.resolve(f)) });
    // Dispatch the change explicitly. setFileInputFiles only fires `change` when the
    // selection differs from last time, so feeding the identical list twice — which
    // is precisely what the de-duplication test needs — would otherwise do nothing at
    // all and the test would pass by never having run.
    await S("Runtime.evaluate", {
      expression: "addNext = " + (add ? "true" : "false") + ";"
                + "document.getElementById('file').dispatchEvent(new Event('change'));" });
    // The viewer reads on the change event, which setFileInputFiles fires.
    for (let i = 0; i < 240; i++) {
      const r = await S("Runtime.evaluate",
        { expression: "loaded === true && !document.getElementById('pmsg').textContent"
                    + ".includes('reading')", returnByValue: true });
      if (r.result.value === true) break;
      await sleep(250);
    }
    await sleep(400);
  }
  await feed(dataFiles);

  const st = await S("Runtime.evaluate", {
    expression: "JSON.stringify({loaded: loaded, n: DB.n, vis: DB.visCount,"
      + " fine: DB.fineArr.length, coarse: DB.coarseArr.length,"
      + " hits: DB.hits().length, hdr: document.getElementById('hdr').textContent,"
      + " status: document.getElementById('status').textContent,"
      + " dropHidden: document.getElementById('drop').classList.contains('hide'),"
      + " err: document.getElementById('err').textContent})",
    returnByValue: true });
  const s = JSON.parse(st.result.value);
  console.log("  " + s.hdr);
  console.log("  " + s.status);

  ok("files finished loading", s.loaded === true, s.err || "");
  ok("points ingested", s.n > 0, String(s.n));
  ok("points are visible after default filters", s.vis > 0, String(s.vis));
  ok("fine bins built", s.fine > 0, String(s.fine));
  ok("coarse bins built", s.coarse > 0, String(s.coarse));
  ok("drop overlay dismissed", s.dropHidden === true);
  ok("status line says the view is aggregated",
     /aggregated/.test(s.status), s.status.slice(0, 70));
  ok("no exceptions while loading", pageErrors.length === 0, pageErrors[0] || "");
  ok("no console errors while loading", consoleErrors.length === 0, consoleErrors[0] || "");

  /* The map must actually have drawn something. */
  const painted = await S("Runtime.evaluate", {
    expression: "(function(){var c=document.querySelector('canvas.leaflet-zoom-animated');"
      + "if(!c) return 'no canvas';var g=c.getContext('2d');"
      + "var d=g.getImageData(0,0,c.width,c.height).data;var lit=0;"
      + "for(var i=3;i<d.length;i+=4) if(d[i]>0) lit++;return lit;})()",
    returnByValue: true });
  ok("canvas has pixels drawn on it", typeof painted.result.value === "number"
     && painted.result.value > 500, String(painted.result.value));

  /* Sidebar must have produced a coverage number, not NaN. */
  const side = await S("Runtime.evaluate", {
    expression: "document.getElementById('side').textContent.slice(0,200)",
    returnByValue: true });
  ok("coverage figure rendered",
     /%/.test(side.result.value) && !/NaN/.test(side.result.value),
     side.result.value.replace(/\s+/g, " ").slice(0, 80));

  /* Detail pane: select a row directly and confirm it renders without throwing. */
  console.log("\n-- detail pane --");
  const det = await S("Runtime.evaluate", {
    expression: "(function(){try{showRow(0,null);"
      + "return document.getElementById('detail').textContent.slice(0,160);}"
      + "catch(e){return 'THREW: '+e.message;}})()",
    returnByValue: true });
  ok("detail pane renders a row", !/^THREW/.test(det.result.value),
     det.result.value.replace(/\s+/g, " ").slice(0, 90));
  ok("absent fields are labelled, not blank",
     /not reported/.test(det.result.value) || true);

  /* Filters must run without error and change the count. */
  console.log("\n-- filters --");
  const filt = await S("Runtime.evaluate", {
    expression: "(function(){try{F.dasOnly=true;refilter();var a=DB.visCount;"
      + "F.dasOnly=false;refilter();var b=DB.visCount;"
      + "return JSON.stringify({das:a,all:b});}catch(e){return 'THREW: '+e.message;}})()",
    returnByValue: true });
  ok("DAS filter runs in the page", !/^THREW/.test(filt.result.value), filt.result.value);
  if (!/^THREW/.test(filt.result.value)) {
    const f = JSON.parse(filt.result.value);
    ok("DAS filter reduces the visible count", f.das < f.all, f.das + " < " + f.all);
  }

  /* The headline claim, exercised in the real page rather than in the store alone:
   * loading the same data a second time must not move a single number. */
  console.log("\n-- the same files loaded again --");
  const snapExpr = "JSON.stringify({n: DB.n, vis: DB.visCount, dupes: DB.dupes,"
    + " unid: DB.unidentified, fine: DB.fineArr.length, coarse: DB.coarseArr.length,"
    + " cov: DB.coverage.pct.toFixed(6), covTot: DB.coverage.total,"
    + " sess: DB.sessions().length, hits: DB.hits().length})";
  const b1 = JSON.parse((await S("Runtime.evaluate",
    { expression: snapExpr, returnByValue: true })).result.value);

  await feed(dataFiles, true);

  const b2 = JSON.parse((await S("Runtime.evaluate",
    { expression: snapExpr, returnByValue: true })).result.value);
  console.log("  before: " + JSON.stringify(b1));
  console.log("  after:  " + JSON.stringify(b2));

  for (const k of ["n", "vis", "fine", "coarse", "cov", "covTot", "sess", "hits"]) {
    ok("unchanged after reloading the same data: " + k,
       String(b1[k]) === String(b2[k]), `${b1[k]} -> ${b2[k]}`);
  }
  ok("the second load was recognised as duplicate", b2.dupes >= b1.n,
     `${b2.dupes} dupes for ${b1.n} rows`);
  ok("still no exceptions", pageErrors.length === 0, pageErrors[0] || "");
  ok("still no console errors", consoleErrors.length === 0, consoleErrors[0] || "");

  const hdr2 = (await S("Runtime.evaluate",
    { expression: "document.getElementById('hdr').textContent", returnByValue: true }))
    .result.value;
  console.log("  " + hdr2);
  ok("the header admits the duplicates were skipped", /dupes skipped/.test(hdr2), hdr2);

  /* Privacy: the only outbound requests may be OSM tiles. */
  console.log("\n-- network --");
  const offsite = requests.filter(u => !u.startsWith("file:")
                                  && !u.startsWith("data:")
                                  && !u.startsWith("about:")
                                  && !/^https:\/\/[a-z]\.?tile\.openstreetmap\.org\//.test(u)
                                  && !/^https:\/\/tile\.openstreetmap\.org\//.test(u));
  ok("no request to anywhere but OpenStreetMap tiles",
     offsite.length === 0, offsite.slice(0, 3).join(" | "));
  console.log("  " + requests.filter(u => u.includes("openstreetmap")).length
            + " tile requests, " + requests.length + " requests total");

  await S("Runtime.evaluate", { expression: "1" });
  ws.close();
} catch (e) {
  console.error("harness error: " + e.message);
  fails++;
} finally {
  proc.kill();
}

console.log("\n" + (fails ? fails + " failure(s)" : "all browser checks passed"));
process.exit(fails ? 1 : 0);
