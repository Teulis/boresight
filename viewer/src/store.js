"use strict";
/* ===========================================================================
 * Boresight viewer — data access layer.
 *
 * Everything the UI knows about the survey goes through DataSource. That seam is
 * deliberate and it is the most important thing in this file: the collection rate is
 * about 5,000 miles a month, which at the measured ~295 bytes and ~1.7 points per
 * second works out to roughly
 *
 *     16-hour drive   ~100,000 points     ~29 MB
 *     one month       ~700,000 points    ~200 MB
 *     one year      ~8,000,000 points    ~2.4 GB
 *
 * A browser cannot hold the last of those as one JSON document — a 2.4 GB UTF-8 file
 * is more characters than a single JavaScript string is allowed to contain, so
 * readAsText does not merely get slow, it throws. The in-memory store below is
 * therefore explicitly the *small* implementation, good to roughly a million points,
 * and SqliteSource or HttpSource can replace it without the renderer changing, because
 * the renderer only ever calls these methods:
 *
 *     open(file, cbs)                     load a bundle
 *     setFilters(f)                       rebuild the working set
 *     bins(bbox, project, cellPx)         aggregated cells for the current view
 *     points(bbox, project, limit)        individual rows, high zoom only
 *     row(id)                             one full record, for the detail pane
 *     facets() / sessions() / stats()     what the sidebar needs
 *
 * Note what is NOT in that list: any way to get at the underlying arrays. The renderer
 * cannot linearly scan the dataset even if it wanted to.
 * ======================================================================== */

/* Absent is not zero.
 *
 * Sync.writeBundle omits a field entirely when there is no reading — put() skips empty
 * strings, num() skips null integers. A row with no RSRP has no "dbm" key at all, and
 * storing that as 0 would paint an impossible -0 dBm reading as the strongest signal on
 * the map. Every numeric column carries an explicit sentinel and every read goes
 * through has(). Nothing defaults to zero, anywhere. */
var NONE = -32768;
var NONE32 = -2147483648;
var NONEF = NaN;
function has(v) { return v !== NONE && v !== NONE32 && v !== undefined && v !== null && !(typeof v === "number" && isNaN(v)); }

var DAS_BANDS = { 2:1, 4:1, 5:1, 12:1, 13:1, 14:1, 25:1, 26:1, 29:1, 66:1 };
var BAND_FAMILY = {
  2:"PCS", 25:"PCS", 4:"AWS", 66:"AWS", 5:"cell 850", 26:"cell 850",
  12:"700", 13:"700", 29:"700", 14:"FirstNet 700",
  41:"BRS 2.5", 71:"600", 48:"CBRS", 30:"WCS"
};
var RSRP_TARGET = -95;
var COVERAGE_PCT = 95;

/* Two format numbers that must not be confused with each other.
 *
 * BUNDLE_FORMAT is the whole-bundle JSON written by Sync.writeBundle. Format 2 added
 * `dev` and `seq` to every observation, which together identify a row globally and are
 * what makes exact de-duplication possible.
 *
 * CHUNK_FORMAT is the NDJSON chunk written by Feed.java, counted separately and
 * currently at 1. A chunk header saying "format":1 is a current chunk, not an old
 * bundle, and reading one number with the other's guard would reject good files. */
var BUNDLE_FORMAT = 2;
var CHUNK_FORMAT = 1;

/* Samsung pads some advertised names with NUL to a fixed width — 34 rows in the real
 * 43,630-point file. Left in, they render as blanks or boxes and silently corrupt any
 * copy-paste of the value. */
function clean(s) {
  if (typeof s !== "string") return s;
  var t = s.replace(/\u0000+/g, "");
  return t === "" ? undefined : t;
}

/* ===========================================================================
 * BundleStream — incremental reader for the JSON bundle format.
 *
 * Never materialises the whole file as a string and never builds a parse tree for the
 * whole document. The file is read in slices as ArrayBuffers and decoded with a
 * streaming TextDecoder, which is what keeps a multi-byte UTF-8 character split across
 * a slice boundary from becoming a replacement character.
 *
 * The scanner is deliberately not a full JSON parser. It walks the top level looking
 * for the three arrays that matter, then for each array element finds the matching
 * close brace — tracking string state so a brace inside an SSID does not confuse it —
 * and hands that one element to JSON.parse. One small object at a time, discarded
 * immediately. That is the whole trick, and it is why this scales with the file rather
 * than with available memory.
 * ======================================================================== */
function BundleStream(file, cb) {
  this.file = file;
  this.cb = cb;
  this.buf = "";
  this.pos = 0;              // read cursor within buf
  this.offset = 0;           // bytes consumed from the file
  this.dec = new TextDecoder("utf-8");
  this.state = "top";
  this.key = null;
  this.meta = {};
  this.depth = 0;
  this.counts = { obs: 0, sessions: 0, hits: 0 };
  this.aborted = false;
  this.SLICE = 4 * 1024 * 1024;
}

BundleStream.prototype.start = function () {
  this._next();
};

BundleStream.prototype.abort = function (msg) {
  if (this.aborted) return;
  this.aborted = true;
  this.cb.error(msg);
};

BundleStream.prototype._next = function () {
  if (this.aborted) return;
  var self = this;
  if (this.offset >= this.file.size) {
    // Flush whatever the decoder is holding, run the scanner one last time, done.
    this.buf += this.dec.decode();
    this._scan(true);
    if (!this.aborted) this.cb.done(this.meta, this.counts);
    return;
  }
  var end = Math.min(this.file.size, this.offset + this.SLICE);
  var slice = this.file.slice(this.offset, end);
  var fr = new FileReader();
  fr.onerror = function () { self.abort("Could not read the file."); };
  fr.onload = function () {
    if (self.aborted) return;
    self.buf += self.dec.decode(new Uint8Array(fr.result), { stream: true });
    self.offset = end;
    try {
      self._scan(false);
    } catch (e) {
      self.abort("Failed while reading the bundle.\n\n" + (e && e.message ? e.message : e));
      return;
    }
    if (self.aborted) return;
    self.cb.progress(self.offset / self.file.size, self.counts);
    // Yield to the event loop so the progress bar paints and the tab stays alive.
    setTimeout(function () { self._next(); }, 0);
  };
  fr.readAsArrayBuffer(slice);
};

/* Find the end of the JSON value starting at i (an object or array), honouring strings
 * and escapes. Returns the index just past it, or -1 when the buffer does not yet hold
 * the whole value. */
BundleStream.prototype._valueEnd = function (s, i) {
  var depth = 0, inStr = false, esc = false;
  for (var k = i; k < s.length; k++) {
    var c = s.charCodeAt(k);
    if (inStr) {
      if (esc) { esc = false; continue; }
      if (c === 92) { esc = true; continue; }      // backslash
      if (c === 34) inStr = false;                 // quote
      continue;
    }
    if (c === 34) { inStr = true; continue; }
    if (c === 123 || c === 91) depth++;            // { [
    else if (c === 125 || c === 93) {              // } ]
      depth--;
      if (depth === 0) return k + 1;
    }
  }
  return -1;
};

BundleStream.prototype._scan = function (final) {
  var s = this.buf;

  for (;;) {
    if (this.aborted) return;

    if (this.state === "top") {
      // Looking for  "key" :
      var q = s.indexOf('"', this.pos);
      if (q < 0) break;
      var q2 = s.indexOf('"', q + 1);
      if (q2 < 0) break;
      var key = s.slice(q + 1, q2);
      var colon = s.indexOf(":", q2 + 1);
      if (colon < 0) break;
      var vs = colon + 1;
      while (vs < s.length && /\s/.test(s[vs])) vs++;
      if (vs >= s.length) break;

      if (s[vs] === "[") {
        if (key === "obs" || key === "sessions" || key === "hits") {
          this.key = key;
          this.state = "array";
          this.pos = vs + 1;
          continue;
        }
        // An array we do not care about: skip it whole.
        var e = this._valueEnd(s, vs);
        if (e < 0) break;
        this.pos = e;
        continue;
      }
      if (s[vs] === "{") {
        var eo = this._valueEnd(s, vs);
        if (eo < 0) break;
        this.pos = eo;
        continue;
      }
      // A scalar header field.
      var ve = vs;
      var inStr = s[vs] === '"', escp = false;
      if (inStr) {
        ve = vs + 1;
        while (ve < s.length) {
          if (escp) { escp = false; ve++; continue; }
          if (s[ve] === "\\") { escp = true; ve++; continue; }
          if (s[ve] === '"') { ve++; break; }
          ve++;
        }
        if (ve > s.length) break;
      } else {
        while (ve < s.length && ",}]\n\r\t ".indexOf(s[ve]) < 0) ve++;
        if (ve >= s.length && !final) break;
      }
      var raw = s.slice(vs, ve);
      try { this.meta[key] = JSON.parse(raw); } catch (ex) { /* partial; ignore */ }

      // The two header checks that must happen before anything is trusted. They appear
      // near the front of the file, so this fires within the first slice.
      if (key === "app" && this.meta.app !== "boresight") {
        this.abort('That is not a Boresight bundle — its "app" field says '
                   + JSON.stringify(this.meta.app) + ".");
        return;
      }
      if (key === "format" && this.meta.format > BUNDLE_FORMAT) {
        this.abort("This bundle is format " + this.meta.format
          + " and this viewer understands up to " + BUNDLE_FORMAT
          + ".\n\nIt was written by a newer build of Boresight. Update the viewer rather "
          + "than reading it with this one — a field whose meaning changed would be "
          + "shown wrong, which is worse than not shown.");
        return;
      }
      this.pos = ve;
      continue;
    }

    if (this.state === "array") {
      var i = this.pos;
      while (i < s.length && (s[i] === "," || /\s/.test(s[i]))) i++;
      if (i >= s.length) { this.pos = i; break; }
      if (s[i] === "]") { this.pos = i + 1; this.state = "top"; this.key = null; continue; }
      if (s[i] !== "{") { this.pos = i + 1; continue; }
      var end = this._valueEnd(s, i);
      if (end < 0) { this.pos = i; break; }
      var obj;
      try { obj = JSON.parse(s.slice(i, end)); }
      catch (ex) { this.abort("Malformed record in the bundle:\n\n" + ex.message); return; }
      this.counts[this.key]++;
      /* The device id is passed down with every record. writeBundle emits "device"
       * before "sessions" and "obs", so by the time either array is reached the id is
       * known — which is what lets a format-1 bundle (no per-row dev) still be keyed
       * by the phone that wrote it. */
      if (this.key === "obs") this.cb.obs(obj, this.meta.device);
      else if (this.key === "sessions") this.cb.session(obj, this.meta.device);
      else this.cb.hit(obj);
      this.pos = end;
      continue;
    }

    break;
  }

  // Drop what has been consumed so the buffer does not grow with the file.
  if (this.pos > 0) {
    this.buf = this.buf.slice(this.pos);
    this.pos = 0;
  }
};

/* ===========================================================================
 * ChunkStream — gzipped NDJSON, the shape continuous collection actually produces.
 *
 *   bs-<dev>-<from>-<to>-<stamp>.ndjson.gz
 *   line 0      {"format":1,"app":"boresight","chunk":true,"device":...,"deviceName":...}
 *   lines 1..n  one observation each, same field names as a bundle row
 *
 * Two differences from a bundle that are easy to miss and expensive to get wrong.
 * The rows carry `seq` but not `dev` — the device is named once, in the header — so the
 * header's id has to be threaded down to every row or nothing can be de-duplicated
 * against the bundle holding the same readings. And a chunk has no sessions array and
 * no hits, so session ids turn up that nothing has a label for; those are reported as
 * unlabelled rather than left blank or invented.
 * ======================================================================== */
function ChunkStream(file, cb) {
  this.file = file;
  this.cb = cb;
  this.aborted = false;
}

ChunkStream.supported = function () {
  return typeof DecompressionStream === "function";
};

ChunkStream.prototype.abort = function (m) {
  if (this.aborted) return;
  this.aborted = true;
  this.cb.error(m);
};

ChunkStream.prototype.start = function () {
  var self = this;
  if (!ChunkStream.supported()) {
    this.abort("This browser cannot decompress .ndjson.gz: it has no "
      + "DecompressionStream. Chromium and Edge do; older browsers and some "
      + "Firefox builds do not.\n\nEither open this file in Edge, or gunzip it "
      + "first and load the plain .ndjson.");
    return;
  }

  var header = null, count = 0, lineNo = 0, buf = "";
  var reader;
  try {
    reader = this.file.stream()
      .pipeThrough(new DecompressionStream("gzip"))
      .pipeThrough(new TextDecoderStream("utf-8"))
      .getReader();
  } catch (e) {
    this.abort("Could not open that file as gzip.\n\n" + (e && e.message ? e.message : e));
    return;
  }

  var readBytes = 0;
  function handleLine(line) {
    line = line.trim();
    if (!line) return;
    var o;
    try { o = JSON.parse(line); }
    catch (e) {
      throw new Error("Line " + (lineNo + 1) + " of the chunk is not valid JSON:\n\n"
                      + e.message);
    }
    if (lineNo === 0) {
      // The header. Checked before a single row is trusted.
      if (o.app !== "boresight") {
        throw new Error('That is not a Boresight chunk — its "app" field says '
                        + JSON.stringify(o.app) + ".");
      }
      if (!o.chunk) {
        throw new Error("That file's first line is not a chunk header. A whole bundle "
                      + "should be opened as .json, not as .ndjson.gz.");
      }
      var cf = o.format === undefined ? 1 : o.format;
      if (cf > CHUNK_FORMAT) {
        throw new Error("This chunk is format " + cf + " and this viewer understands up "
          + "to " + CHUNK_FORMAT + ".\n\nIt was written by a newer build of Boresight. "
          + "Note this is the chunk format, counted separately from the bundle format.");
      }
      header = o;
      self.cb.header(o);
      lineNo++;
      return;
    }
    self.cb.obs(o, header ? header.device : undefined);
    count++;
    lineNo++;
  }

  function pump() {
    reader.read().then(function (r) {
      if (self.aborted) return;
      if (r.done) {
        try { if (buf.length) handleLine(buf); } catch (e) { self.abort(e.message); return; }
        if (!header) {
          self.abort("That chunk is empty — it has no header line.");
          return;
        }
        self.cb.done(header, { obs: count, sessions: 0, hits: 0 });
        return;
      }
      buf += r.value;
      readBytes += r.value.length;
      var nl;
      try {
        while ((nl = buf.indexOf("\n")) >= 0) {
          var line = buf.slice(0, nl);
          buf = buf.slice(nl + 1);
          handleLine(line);
        }
      } catch (e) { self.abort(e.message); return; }
      self.cb.progress(Math.min(0.99, readBytes / Math.max(1, self.file.size * 4)),
                       { obs: count, sessions: 0, hits: 0 });
      pump();
    }).catch(function (e) {
      self.abort("Failed while decompressing the chunk.\n\n"
                 + (e && e.message ? e.message : e));
    });
  }
  pump();
}

/** gzip starts 1f 8b. Sniffing the bytes beats trusting a filename. */
function looksGzipped(file) {
  return new Promise(function (res) {
    var fr = new FileReader();
    fr.onerror = function () { res(false); };
    fr.onload = function () {
      var b = new Uint8Array(fr.result);
      res(b.length >= 2 && b[0] === 0x1f && b[1] === 0x8b);
    };
    fr.readAsArrayBuffer(file.slice(0, 2));
  });
}

/* ===========================================================================
 * Growable typed-array columns.
 *
 * Columnar rather than one object per row, because at these volumes the difference is
 * decisive: a JavaScript object per point costs a few hundred bytes once headers and
 * hidden classes are counted, while these columns cost about 90 bytes a row total.
 * That is the difference between a month of driving fitting in a tab and not.
 * ======================================================================== */
function Col(Type, fill) {
  this.Type = Type; this.fill = fill;
  this.a = new Type(1 << 16); this.n = 0;
}
Col.prototype.push = function (v) {
  if (this.n === this.a.length) {
    var b = new this.Type(this.a.length * 2);
    b.set(this.a); this.a = b;
  }
  this.a[this.n++] = v;
};

/* Dictionary encoding for repeated strings. Carrier, tech, MCC and MNC have a handful
 * of distinct values across millions of rows; ident and MAC have many more but still
 * repeat heavily along a drive. */
function Dict() { this.ix = new Map(); this.vals = []; }
Dict.prototype.put = function (s) {
  if (s === undefined || s === null || s === "") return NONE32;
  var v = this.ix.get(s);
  if (v === undefined) { v = this.vals.length; this.vals.push(s); this.ix.set(s, v); }
  return v;
};
Dict.prototype.get = function (i) { return has(i) ? this.vals[i] : null; };

/* ===========================================================================
 * MemoryStore — the in-browser DataSource.
 * ======================================================================== */
function MemoryStore() {
  this.meta = null;
  this.sessionRows = [];
  this.hitRows = [];
  this.n = 0;

  this.lat = new Col(Float64Array);
  this.lon = new Col(Float64Array);
  this.ts  = new Col(Float64Array);
  this.dbm = new Col(Int16Array);
  this.band = new Col(Int16Array);
  this.qual = new Col(Int16Array);
  this.ta   = new Col(Int16Array);
  this.sess = new Col(Int16Array);
  this.pci  = new Col(Int32Array);
  this.tac  = new Col(Int32Array);
  this.arfcn = new Col(Int32Array);
  this.acc = new Col(Float32Array);
  this.speed = new Col(Float32Array);
  this.bearing = new Col(Float32Array);
  this.dlMhz = new Col(Float32Array);
  this.kind = new Col(Uint8Array);
  this.serving = new Col(Uint8Array);

  this.dCarrier = new Dict(); this.cCarrier = new Col(Int32Array);
  this.dTech = new Dict();    this.cTech = new Col(Int32Array);
  this.dMcc = new Dict();     this.cMcc = new Col(Int32Array);
  this.dMnc = new Dict();     this.cMnc = new Col(Int32Array);
  this.dIdent = new Dict();   this.cIdent = new Col(Int32Array);
  this.dName = new Dict();    this.cName = new Col(Int32Array);
  this.dMac = new Dict();     this.cMac = new Col(Int32Array);
  this.dExtra = new Dict();   this.cExtra = new Col(Int32Array);

  /* Row identity, added by bundle format 2 and carried by every chunk row. */
  this.dDev = new Dict();     this.cDev = new Col(Int32Array);
  this.seq = new Col(Int32Array);

  /* Sessions are numbered per device, so session 3 from one phone is not session 3
   * from another. Keyed by "<dev>:<id>" rather than by the bare id, or merging two
   * devices' chunks would silently fuse unrelated drives into one session. */
  this.dSessKey = new Dict(); this.cSessKey = new Col(Int32Array);
  this.sessionMeta = {};      // "<dev>:<id>" -> the session record, when one was seen

  /* Exact de-duplication on (dev, seq).
   *
   * Chunks overlap whole bundles, the same chunk gets loaded twice, and a folder gets
   * dropped on the viewer more than once. Counting a row twice would inflate every
   * total and move the coverage percentage, which is the failure that matters: the
   * number still looks plausible and is wrong. A Set of seq per device costs roughly
   * 35 MB at a million rows, which is the price of the guarantee. */
  this.seen = new Map();      // devIdx -> Set of seq
  this.dupes = 0;
  this.unidentified = 0;
  this.sources = [];          // every file that contributed to this view

  this.bandsSeen = {};
  this.filters = null;
  this.fine = null;          // Map(key -> aggregated cell) for the current filters
  this.fineArr = [];
  this.coarseArr = [];       // the same cells rolled up ~400x, for zoomed-out frames
  this.visCount = 0;
  this.coverage = null;
  this.gridIdx = null;       // Map(cellKey -> Int32Array of row ids), for point queries
}

/* Fine bin resolution. ~55 m at this latitude. Chosen so a month of driving produces a
 * few hundred thousand cells rather than a few million points: the render path then
 * scales with road covered, not with time spent parked. */
MemoryStore.FINE_DEG = 0.0005;

/* A second, coarser level, ~1.1 km.
 *
 * Measured, not guessed: the render loop walks bins, and a bin is a length of road, so
 * frame cost scales with miles driven rather than with points or with time. At the fine
 * level alone, one month of this collection rate (~146,000 cells) costs 38 ms to draw
 * fully zoomed out, three months 110 ms, and a year 424 ms — which is a dead map.
 *
 * Rolling the fine cells up once per filter change into a level 400 times coarser caps
 * that: a year becomes a few tens of thousands of coarse cells and draws in the low
 * tens of milliseconds. Zoomed in, the fine level is used and the bbox test skips the
 * rest. This is the same trick a tile server does, done locally. */
MemoryStore.COARSE_DEG = 0.01;

/**
 * Add one observation, unless it is a row we already hold.
 *
 * @param defaultDev device id from a chunk header. Chunk rows carry `seq` but not
 *                   `dev` — the device appears once, in the header — so without this
 *                   every chunk row would look like it came from nowhere and could
 *                   never be de-duplicated against the bundle holding the same data.
 * @return true when the row was stored, false when it was a duplicate
 */
MemoryStore.prototype.addObs = function (o, defaultDev) {
  var devStr = o.dev !== undefined ? o.dev : defaultDev;
  var seqNum = (typeof o.seq === "number") ? o.seq : null;
  var devIx = this.dDev.put(devStr);

  if (devIx !== NONE32 && seqNum !== null) {
    var set = this.seen.get(devIx);
    if (set === undefined) { set = new Set(); this.seen.set(devIx, set); }
    if (set.has(seqNum)) { this.dupes++; return false; }
    set.add(seqNum);
  } else {
    /* A row from before identities existed. It has no key, so it cannot be matched
     * against anything — and crucially it must not be treated as sharing a null key
     * with every other such row, which would collapse an entire old bundle into one
     * point. Accepted unconditionally, and counted so the UI can say how many rows
     * are un-deduplicable rather than implying the whole load was exact. */
    this.unidentified++;
  }

  var i = this.n++;
  this.cDev.push(devIx);
  this.seq.push(seqNum === null ? NONE32 : seqNum);

  var sk = (devStr === undefined || devStr === null ? "?" : devStr) + ":"
         + (typeof o.session === "number" ? o.session : "?");
  this.cSessKey.push(this.dSessKey.put(sk));
  var lat = (typeof o.lat === "number") ? o.lat : NaN;
  var lon = (typeof o.lon === "number") ? o.lon : NaN;
  this.lat.push(lat); this.lon.push(lon);
  this.ts.push(typeof o.ts === "number" ? o.ts : NaN);
  /* A positive dBm is not a reading.
   *
   * The real file carries 16 BLE rows with dbm = 127, which is Android's "RSSI
   * unavailable" sentinel leaking through the export as though it were a measurement.
   * +127 dBm is not a signal level any receiver reports; taken at face value it is the
   * strongest reading in the dataset, so it wins every "best in bin" comparison and
   * paints bright green on a map coloured by signal. Stored as absent instead: the
   * sighting is real and keeps its position, only its level is unknown. */
  this.dbm.push((typeof o.dbm === "number" && o.dbm < 0) ? o.dbm : NONE);
  this.band.push(typeof o.band === "number" ? o.band : NONE);
  this.qual.push(typeof o.qual === "number" ? o.qual : NONE);
  this.ta.push(typeof o.ta === "number" ? o.ta : NONE);
  this.sess.push(typeof o.session === "number" ? o.session : NONE);
  this.pci.push(typeof o.pci === "number" ? o.pci : NONE32);
  this.tac.push(typeof o.tac === "number" ? o.tac : NONE32);
  this.arfcn.push(typeof o.arfcn === "number" ? o.arfcn : NONE32);
  this.acc.push(typeof o.acc === "number" ? o.acc : NaN);
  this.speed.push(typeof o.speed === "number" ? o.speed : NaN);
  this.bearing.push(typeof o.bearing === "number" ? o.bearing : NaN);
  this.dlMhz.push(typeof o.dlMhz === "number" ? o.dlMhz : NaN);
  var k = o.kind;
  this.kind.push(k === "cell" ? 0 : k === "wifi" ? 1 : k === "ble" ? 2 : 3);
  this.serving.push(o.serving ? 1 : 0);
  this.cCarrier.push(this.dCarrier.put(o.carrier));
  this.cTech.push(this.dTech.put(o.tech));
  this.cMcc.push(this.dMcc.put(o.mcc));
  this.cMnc.push(this.dMnc.put(o.mnc));
  this.cIdent.push(this.dIdent.put(clean(o.ident)));
  this.cName.push(this.dName.put(clean(o.name)));
  this.cMac.push(this.dMac.put(clean(o.mac)));
  this.cExtra.push(this.dExtra.put(clean(o.extra)));
  if (typeof o.band === "number") this.bandsSeen[o.band] = 1;
  return true;
};

/* Session records, keyed per device for the same reason the column is. A session seen
 * twice (bundle plus chunk) keeps the first label rather than appending a duplicate. */
MemoryStore.prototype.addSession = function (s, defaultDev) {
  var dev = s.dev !== undefined ? s.dev : defaultDev;
  var key = (dev === undefined || dev === null ? "?" : dev) + ":" + s.id;
  if (this.sessionMeta[key]) return;
  var rec = { id: s.id, dev: dev, key: key, label: s.label,
              started: s.started, ended: s.ended };
  this.sessionMeta[key] = rec;
  this.sessionRows.push(rec);
};

/* Detector hits have no (dev, seq), so they are de-duplicated on what they are: the
 * same address matched by the same rule at the same instant is the same sighting. */
MemoryStore.prototype.addHit = function (h) {
  var key = (h.ts || 0) + "|" + (h.mac || "") + "|" + (h.rule || "");
  if (!this._hitKeys) this._hitKeys = {};
  if (this._hitKeys[key]) return;
  this._hitKeys[key] = 1;
  this.hitRows.push(h);
};

/**
 * Record which files went into this view.
 *
 * Worth keeping rather than just counting: once a folder of chunks and a bundle are
 * merged, "what am I actually looking at" stops being obvious, and a viewer that
 * cannot answer it invites the user to draw conclusions from a half-loaded dataset.
 */
MemoryStore.prototype.noteSource = function (name, meta, isChunk) {
  this.sources.push({
    name: name,
    chunk: !!isChunk,
    device: meta && (meta.device || meta.deviceName) || "?",
    deviceName: meta && meta.deviceName || "?",
    format: meta && meta.format,
    written: meta && meta.written || 0
  });
};

/* Approximate memory held, so the UI can warn before the tab dies rather than after. */
MemoryStore.prototype.bytes = function () {
  // Columns, plus the (dev, seq) de-duplication set, which is not free.
  var perRow = 8*4 + 2*5 + 4*3 + 4*4 + 1*2 + 4*8 + 4*3 + 36;
  var dicts = 0;
  [this.dCarrier, this.dTech, this.dMcc, this.dMnc, this.dIdent, this.dName,
   this.dMac, this.dExtra, this.dDev, this.dSessKey].forEach(function (d) {
    dicts += d.vals.length * 48;
  });
  return this.n * perRow + dicts;
};

/* ------------------------------------------------------------------ filters */

function anyOn(set) { for (var k in set) if (set[k]) return true; return false; }

MemoryStore.prototype.setFilters = function (F) {
  this.filters = F;
  var useS = anyOn(F.sessions), useC = anyOn(F.carriers),
      useB = anyOn(F.bands),    useT = anyOn(F.techs);

  var fine = new Map();
  var G = MemoryStore.FINE_DEG;
  var lat = this.lat.a, lon = this.lon.a, dbm = this.dbm.a, band = this.band.a,
      sess = this.cSessKey.a, kind = this.kind.a, car = this.cCarrier.a,
      tech = this.cTech.a;

  var vis = 0, covTot = 0, covOk = 0, covNone = 0;

  for (var i = 0; i < this.n; i++) {
    var la = lat[i];
    if (la !== la) continue;                     // NaN: no position, nothing to draw

    var k = kind[i];
    if (k === 0 && !F.cell) continue;
    if (k === 1 && !F.wifi) continue;
    // BLE is 17% of the real file (7,592 of 43,630 rows). An earlier version dropped
    // it with a comment about having no map story, which meant a sixth of the survey
    // vanished with nothing on screen admitting it.
    if (k === 2 && !F.ble) continue;
    if (k > 2) continue;

    if (useS && !F.sessions[sess[i]]) continue;
    if (useC && !F.carriers[car[i]]) continue;
    if (useT && !F.techs[tech[i]]) continue;

    var b = band[i];
    if (useB && !(b !== NONE && F.bands[b])) continue;
    if (F.dasOnly) {
      // A row with no band cannot be shown to be on a DAS band, so it is excluded
      // rather than quietly let through. Honest, and it keeps the count meaningful.
      if (b === NONE || !DAS_BANDS[b]) continue;
    }

    var d = dbm[i];
    if (d !== NONE) {
      if (d < F.rsrpMin || d > F.rsrpMax) continue;
    } else if (F.rsrpMin > -140) {
      continue;                                  // no reading cannot clear a floor
    }

    vis++;
    if (k === 0) {
      if (d === NONE) covNone++;
      else { covTot++; if (d >= RSRP_TARGET) covOk++; }
    }

    // Fine binning happens here, in the same pass as filtering, so a filter change
    // costs one sweep and every subsequent frame costs only the bins in view.
    var lo = lon[i];
    var gx = Math.round(lo / G), gy = Math.round(la / G);
    var key = gx * 4000000 + gy;
    var c = fine.get(key);
    if (c === undefined) {
      fine.set(key, {
        lat: la, lon: lo, n: 1,
        min: d !== NONE ? d : NONE, max: d !== NONE ? d : NONE,
        sum: d !== NONE ? d : 0, cnt: d !== NONE ? 1 : 0,
        rep: i, repMin: i, repMax: i, kind: k
      });
    } else {
      c.n++;
      if (d !== NONE) {
        c.sum += d; c.cnt++;
        if (c.min === NONE || d < c.min) { c.min = d; c.repMin = i; }
        if (c.max === NONE || d > c.max) { c.max = d; c.repMax = i; }
      }
    }
  }

  this.fine = fine;
  this.fineArr = Array.from(fine.values());

  // Roll the fine level up into the coarse one. Done here, once per filter change,
  // rather than per frame.
  var CG = MemoryStore.COARSE_DEG, coarse = new Map();
  for (var fi = 0; fi < this.fineArr.length; fi++) {
    var fc = this.fineArr[fi];
    var ck = Math.round(fc.lon / CG) * 4000000 + Math.round(fc.lat / CG);
    var cc = coarse.get(ck);
    if (cc === undefined) {
      coarse.set(ck, {
        lat: fc.lat, lon: fc.lon, n: fc.n,
        min: fc.min, max: fc.max, sum: fc.sum, cnt: fc.cnt,
        rep: fc.rep, repMin: fc.repMin, repMax: fc.repMax, kind: fc.kind
      });
    } else {
      cc.n += fc.n; cc.sum += fc.sum; cc.cnt += fc.cnt;
      if (fc.min !== NONE && (cc.min === NONE || fc.min < cc.min)) { cc.min = fc.min; cc.repMin = fc.repMin; }
      if (fc.max !== NONE && (cc.max === NONE || fc.max > cc.max)) { cc.max = fc.max; cc.repMax = fc.repMax; }
    }
  }
  this.coarseArr = Array.from(coarse.values());

  this.visCount = vis;
  this.coverage = {
    total: covTot, ok: covOk, noReading: covNone,
    pct: covTot ? (100 * covOk / covTot) : NaN,
    target: RSRP_TARGET, criterion: COVERAGE_PCT
  };
  this.gridIdx = null;        // invalidated; rebuilt lazily for point queries
  return this.stats();
};

/* ------------------------------------------------------------------ queries */

/**
 * Aggregated cells for a viewport.
 *
 * Returns binned summaries, never a sample of individuals: a thinned scatter looks
 * like sparse coverage, which is a lie the eye believes. Each returned cell says how
 * many readings it stands for, and the caller is expected to say so on screen.
 *
 * @param project  (lat,lon) -> {x,y} in container pixels
 * @param cellPx   screen-space bin size
 */
MemoryStore.prototype.bins = function (bbox, project, cellPx, agg) {
  var out = new Map();
  var s = bbox.south, n = bbox.north, w = bbox.west, e = bbox.east;
  var total = 0;

  /* Pick the pyramid level. One screen bin covers degPerCell degrees of longitude; if
   * that is already wider than a coarse cell, the fine level cannot add any visible
   * detail and walking it would only cost time. Measured by projecting a known span
   * rather than assuming a projection. */
  var p0 = project(bbox.south, w), p1 = project(bbox.south, w + 0.01);
  var pxPerDeg = Math.abs(p1.x - p0.x) / 0.01;
  var degPerCell = pxPerDeg > 0 ? cellPx / pxPerDeg : 1e9;
  var arr = (degPerCell >= MemoryStore.COARSE_DEG && this.coarseArr)
          ? this.coarseArr : this.fineArr;
  var level = (arr === this.coarseArr) ? "coarse" : "fine";

  for (var j = 0; j < arr.length; j++) {
    var c = arr[j];
    if (c.lat < s || c.lat > n || c.lon < w || c.lon > e) continue;
    var p = project(c.lat, c.lon);
    var gx = (p.x / cellPx) | 0, gy = (p.y / cellPx) | 0;
    var key = gx * 100000 + gy;
    var b = out.get(key);
    total += c.n;
    if (b === undefined) {
      out.set(key, {
        x: p.x, y: p.y, n: c.n,
        min: c.min, max: c.max, sum: c.sum, cnt: c.cnt,
        rep: c.rep, repMin: c.repMin, repMax: c.repMax, kind: c.kind
      });
    } else {
      b.n += c.n; b.sum += c.sum; b.cnt += c.cnt;
      if (c.min !== NONE && (b.min === NONE || c.min < b.min)) { b.min = c.min; b.repMin = c.repMin; }
      if (c.max !== NONE && (b.max === NONE || c.max > b.max)) { b.max = c.max; b.repMax = c.repMax; }
    }
  }
  return { cells: Array.from(out.values()), points: total,
           considered: arr.length, level: level };
};

/** Lazily built uniform grid over row ids, for individual-point queries at high zoom. */
MemoryStore.prototype._buildGrid = function () {
  if (this.gridIdx) return;
  var G = MemoryStore.FINE_DEG * 8;          // ~450 m cells
  var tmp = new Map();
  var lat = this.lat.a, lon = this.lon.a;
  var F = this.filters;
  // Re-apply the filter predicate cheaply by reusing the fine map: a row is visible if
  // its fine cell exists. Not exact for cells shared by filtered and unfiltered rows,
  // so the caller re-checks each candidate.
  for (var i = 0; i < this.n; i++) {
    var la = lat[i]; if (la !== la) continue;
    var key = Math.round(lon[i] / G) * 4000000 + Math.round(la / G);
    var a = tmp.get(key);
    if (a === undefined) tmp.set(key, [i]); else a.push(i);
  }
  this.gridIdx = tmp;
  this.gridDeg = G;
};

MemoryStore.prototype._passes = function (i) {
  var F = this.filters;
  if (!F) return true;
  var k = this.kind.a[i];
  if (k === 0 && !F.cell) return false;
  if (k === 1 && !F.wifi) return false;
  if (k === 2 && !F.ble) return false;
  if (k > 2) return false;
  if (anyOn(F.sessions) && !F.sessions[this.cSessKey.a[i]]) return false;
  if (anyOn(F.carriers) && !F.carriers[this.cCarrier.a[i]]) return false;
  if (anyOn(F.techs) && !F.techs[this.cTech.a[i]]) return false;
  var b = this.band.a[i];
  if (anyOn(F.bands) && !(b !== NONE && F.bands[b])) return false;
  if (F.dasOnly && (b === NONE || !DAS_BANDS[b])) return false;
  var d = this.dbm.a[i];
  if (d !== NONE) { if (d < F.rsrpMin || d > F.rsrpMax) return false; }
  else if (F.rsrpMin > -140) return false;
  return true;
};

/** Individual rows inside a bbox. Capped: the caller must handle a truncated answer. */
MemoryStore.prototype.points = function (bbox, limit) {
  this._buildGrid();
  var G = this.gridDeg, out = [], truncated = false;
  var x0 = Math.round(bbox.west / G), x1 = Math.round(bbox.east / G);
  var y0 = Math.round(bbox.south / G), y1 = Math.round(bbox.north / G);
  var lat = this.lat.a, lon = this.lon.a;
  outer:
  for (var gx = x0; gx <= x1; gx++) {
    for (var gy = y0; gy <= y1; gy++) {
      var cell = this.gridIdx.get(gx * 4000000 + gy);
      if (!cell) continue;
      for (var k = 0; k < cell.length; k++) {
        var i = cell[k];
        if (lat[i] < bbox.south || lat[i] > bbox.north) continue;
        if (lon[i] < bbox.west || lon[i] > bbox.east) continue;
        if (!this._passes(i)) continue;
        if (out.length >= limit) { truncated = true; break outer; }
        out.push(i);
      }
    }
  }
  return { ids: out, truncated: truncated };
};

MemoryStore.prototype.row = function (i) {
  if (i < 0 || i >= this.n) return null;
  var g = function (c, v) { return has(v) ? v : null; };
  return {
    id: i,
    lat: this.lat.a[i], lon: this.lon.a[i],
    ts: isNaN(this.ts.a[i]) ? null : this.ts.a[i],
    kind: ["cell","wifi","ble","other"][this.kind.a[i]],
    dbm: this.dbm.a[i] === NONE ? null : this.dbm.a[i],
    band: this.band.a[i] === NONE ? null : this.band.a[i],
    qual: this.qual.a[i] === NONE ? null : this.qual.a[i],
    ta: this.ta.a[i] === NONE ? null : this.ta.a[i],
    session: this.sess.a[i] === NONE ? null : this.sess.a[i],
    sessKey: this.dSessKey.get(this.cSessKey.a[i]),
    dev: this.dDev.get(this.cDev.a[i]),
    seq: this.seq.a[i] === NONE32 ? null : this.seq.a[i],
    pci: this.pci.a[i] === NONE32 ? null : this.pci.a[i],
    tac: this.tac.a[i] === NONE32 ? null : this.tac.a[i],
    arfcn: this.arfcn.a[i] === NONE32 ? null : this.arfcn.a[i],
    acc: isNaN(this.acc.a[i]) ? null : this.acc.a[i],
    speed: isNaN(this.speed.a[i]) ? null : this.speed.a[i],
    bearing: isNaN(this.bearing.a[i]) ? null : this.bearing.a[i],
    dlMhz: isNaN(this.dlMhz.a[i]) ? null : this.dlMhz.a[i],
    serving: !!this.serving.a[i],
    carrier: this.dCarrier.get(this.cCarrier.a[i]),
    tech: this.dTech.get(this.cTech.a[i]),
    mcc: this.dMcc.get(this.cMcc.a[i]),
    mnc: this.dMnc.get(this.cMnc.a[i]),
    ident: this.dIdent.get(this.cIdent.a[i]),
    name: this.dName.get(this.cName.a[i]),
    mac: this.dMac.get(this.cMac.a[i]),
    extra: this.dExtra.get(this.cExtra.a[i])
  };
};

MemoryStore.prototype.facets = function () {
  return {
    carriers: this.dCarrier.vals.slice(),
    techs: this.dTech.vals.slice(),
    bands: Object.keys(this.bandsSeen).map(Number).sort(function (a, b) { return a - b; })
  };
};

MemoryStore.prototype.hits = function () { return this.hitRows; };

MemoryStore.prototype.stats = function () {
  return {
    total: this.n,
    visible: this.visCount,
    fineCells: this.fineArr.length,
    coarseCells: this.coarseArr.length,
    dupes: this.dupes,
    unidentified: this.unidentified,
    devices: this.dDev.vals.slice(),
    sources: this.sources.slice(),
    coverage: this.coverage,
    bytes: this.bytes()
  };
};

/**
 * Per-session summary. Computed in one sweep over all rows rather than one sweep per
 * session, because at a million points the difference is a visible stall.
 */
MemoryStore.prototype.sessions = function () {
  var by = {};
  var self = this;

  /* Start from the sessions that arrived with a label, keyed per device. */
  this.sessionRows.forEach(function (s) {
    var ix = self.dSessKey.ix.get(s.key);
    if (ix === undefined) ix = self.dSessKey.put(s.key);
    by[ix] = { key: s.key, id: s.id, dev: s.dev, label: s.label,
               started: s.started, ended: s.ended, synthetic: false,
               points: 0, carriers: {}, bands: {}, dist: 0, pl: NaN, po: NaN,
               first: Infinity, last: -Infinity };
  });

  var lat = this.lat.a, lon = this.lon.a, sess = this.cSessKey.a, kind = this.kind.a,
      ts = this.ts.a;
  for (var i = 0; i < this.n; i++) {
    if (kind[i] !== 0) continue;
    var sk = sess[i];
    var e = by[sk];
    if (!e) {
      /* A session nothing gave us a label for. Chunks carry observations only — no
       * sessions array at all — so once chunks are in use this is the normal case,
       * not an anomaly. Synthesised from what is actually known (device and id) and
       * flagged, so the UI can say "no label in the files loaded" rather than print a
       * blank that reads as an unnamed drive. Times come from the rows themselves. */
      var keyStr = self.dSessKey.get(sk) || "?:?";
      var parts = String(keyStr).split(":");
      e = by[sk] = {
        key: keyStr, id: parts[1] === undefined ? "?" : parts[1],
        dev: parts[0] === "?" ? null : parts[0],
        label: null, started: null, ended: null, synthetic: true,
        points: 0, carriers: {}, bands: {}, dist: 0, pl: NaN, po: NaN,
        first: Infinity, last: -Infinity
      };
    }
    e.points++;
    var t = ts[i];
    if (t === t) { if (t < e.first) e.first = t; if (t > e.last) e.last = t; }
    var c = this.cCarrier.a[i]; if (has(c)) e.carriers[this.dCarrier.vals[c]] = 1;
    var b = this.band.a[i]; if (b !== NONE) e.bands[b] = 1;
    var la = lat[i];
    if (la === la) {
      if (e.pl === e.pl) {
        var d = haversine(e.pl, e.po, la, lon[i]);
        // A gap larger than this is the recorder being paused or the fix jumping, not
        // distance travelled. Counting it would inflate every mileage figure.
        if (d < 2000) e.dist += d;
      }
      e.pl = la; e.po = lon[i];
    }
  }
  return Object.keys(by).map(function (k) {
    var e = by[k];
    // For a synthesised session the only honest timestamps are the ones observed.
    if (e.synthetic) {
      if (e.first !== Infinity) e.started = e.first;
      if (e.last !== -Infinity) e.ended = e.last;
    }
    return e;
  });
};

/* ===========================================================================
 * loadFiles — open a selection of files into one store.
 *
 * A folder of chunks is the normal case, not one file, so this accumulates rather than
 * replacing: each file is read in turn into the same store and de-duplication happens
 * naturally on the way in. Mixing whole bundles and chunks in one selection is fine and
 * expected, since the chunks for a drive and the bundle exported from it overlap.
 *
 * Files are sniffed by their first two bytes rather than trusted by extension, because
 * a chunk that someone renamed is still a chunk and a .json that is actually gzip
 * should not produce a parse error about an unexpected character.
 * ======================================================================== */
function loadFiles(files, store, cb) {
  var list = Array.prototype.slice.call(files);
  // Chunk filenames embed a zero-padded seq range precisely so a sort puts them in
  // write order; honouring that keeps the de-duplication reporting intuitive.
  list.sort(function (a, b) { return a.name < b.name ? -1 : a.name > b.name ? 1 : 0; });

  var idx = 0, errors = [];
  var totalBytes = list.reduce(function (s, f) { return s + f.size; }, 0);
  var doneBytes = 0;

  function nextFile() {
    if (idx >= list.length) {
      cb.done({ files: list.length, errors: errors });
      return;
    }
    var file = list[idx++];
    cb.file(file, idx, list.length);

    looksGzipped(file).then(function (gz) {
      var reader;
      var common = {
        obs: function (o, devFromHeader) { store.addObs(o, devFromHeader); },
        session: function (s, dev) { store.addSession(s, dev); },
        hit: function (h) { store.addHit(h); },
        header: function (h) {
          store.noteSource(file.name, h, true);
        },
        progress: function (p, counts) {
          cb.progress((doneBytes + p * file.size) / Math.max(1, totalBytes), counts, file);
        },
        error: function (msg) {
          // One bad file must not abandon the rest of a folder.
          errors.push({ file: file.name, message: msg });
          doneBytes += file.size;
          nextFile();
        },
        done: function (meta, counts) {
          if (!gz) store.noteSource(file.name, meta, false);
          doneBytes += file.size;
          nextFile();
        }
      };

      reader = gz ? new ChunkStream(file, common) : new BundleStream(file, common);
      reader.start();
    });
  }
  nextFile();
}

function haversine(a1, o1, a2, o2) {
  var R = 6371008.8, p1 = a1 * Math.PI/180, p2 = a2 * Math.PI/180;
  var dp = (a2-a1) * Math.PI/180, dl = (o2-o1) * Math.PI/180;
  var x = Math.sin(dp/2)*Math.sin(dp/2) + Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)*Math.sin(dl/2);
  return 2*R*Math.atan2(Math.sqrt(x), Math.sqrt(1-x));
}
