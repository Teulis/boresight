package com.boresight;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;

import java.util.List;

/**
 * The part that keeps running: sample the radio, follow the fix, write the survey log,
 * and keep the notification, widget and tile truthful.
 *
 * Recording is off unless the user turns it on, and the notification always says which
 * state it is in. Nothing is transmitted anywhere; the only way data leaves the phone is
 * an export the user asks for.
 */
public class MonitorService extends Service {

    private static final String TAG = "cellmon";

    /**
     * Wi-Fi scan cadence. The platform allows a foreground app four scans per two
     * minutes, so 30 s is the fastest sustainable rate while throttling is on, and
     * asking faster just wastes battery.
     *
     * That cadence is a real limit on driving surveys, not a detail: at 45 mph a scan
     * every 30 s is one sample every 2,000 feet, while a camera is in range for a couple
     * of hundred. Most of the road is simply never looked at. When throttling has been
     * turned off — see Throttle, which can do it through the privileged backend — we
     * scan far more often and the gaps close.
     */
    private static final long WIFI_SCAN_MS = 30_000;
    private static final long WIFI_SCAN_FAST_MS = 4_000;
    private static final long BLE_WINDOW_MS = 12_000;

    /**
     * Drop scan results older than this. The platform cache happily hands back entries
     * several minutes stale, which would otherwise be geotagged at the current fix.
     */
    private static final long MAX_SCAN_AGE_MS = 20_000;

    public static boolean running;

    private Sampler sampler;
    private Store store;
    private Detector detector;
    /**
     * Catches whatever is riding in the car that no name list names. Static because the
     * recording screen shows what it has caught, and the service is a singleton anyway;
     * cleared at the start of every session.
     */
    private static final Noise.Riders riders = new Noise.Riders();

    public static Noise.Riders riders() { return riders; }

    private HandlerThread worker;
    private Handler bg;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LocationManager lm;
    private volatile Location fix;

    private WifiManager wifi;
    private long lastWifiScan;

    private BluetoothLeScanner ble;
    private boolean bleScanning;

    private long session;
    private int logged;
    private long lastRecordAt;
    private boolean recording;

    // ------------------------------------------------------------------ lifecycle

    @Override public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        sampler = new Sampler(this);
        store = Store.of(this);
        detector = new Detector(this);
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        Notif.ensureChannels(this);
        // One-time background housekeeping: the duplicate index, and clearing levels
        // that were sentinels rather than measurements. Guarded internally so this
        // costs nothing after the first call.
        store.ensureDedupIndexAsync();

        worker = new HandlerThread("cellmon-sampler");
        worker.start();
        bg = new Handler(worker.getLooper());

        registerReceiver(wifiResults, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                Context.RECEIVER_NOT_EXPORTED);

        purgeOldData();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (Notif.ACTION_STOP.equals(action)) {
            stopRecording();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (Notif.ACTION_RESET.equals(action)) {
            Trace.shared().clear();
            Tracker.shared().clear();
        }
        if (Notif.ACTION_RECORD.equals(action)) {
            boolean want = !Prefs.bool(this, Prefs.WARDRIVE_ON, false);
            Prefs.put(this, Prefs.WARDRIVE_ON, want);
            if (want) startRecording(); else stopRecording();
        }

        running = true;
        startInForeground();
        if (Prefs.bool(this, Prefs.WARDRIVE_ON, false) && !recording) startRecording();
        requestLocation();
        bg.removeCallbacks(loop);
        bg.post(loop);
        return START_STICKY;
    }

    private void startInForeground() {
        Sampler.Snap snap = sampler.sample();
        android.app.Notification n = Notif.build(this, snap,
                Prefs.bool(this, Prefs.WARDRIVE_ON, false), logged, null);
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        if (hasLocation()) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        try {
            startForeground(Notif.ID, n, type);
        } catch (Throwable t) {
            // Some OEM builds refuse a type combination; a plain foreground is still better
            // than dying on start.
            try { startForeground(Notif.ID, n); } catch (Throwable ignored) {}
        }
    }

    @Override public void onDestroy() {
        running = false;
        bg.removeCallbacksAndMessages(null);
        worker.quitSafely();
        try { unregisterReceiver(wifiResults); } catch (Throwable ignored) {}
        try { lm.removeUpdates(locListener); } catch (Throwable ignored) {}
        stopBle();
        stopRecording();
        Widget.pushAll(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    // ------------------------------------------------------------------ sampling loop

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            long period = Prefs.refresh(MonitorService.this);
            try { step(); } catch (Throwable t) { /* never let a sample kill the loop */ }
            if (running) bg.postDelayed(this, period);
        }
    };

    private void step() {
        Sampler.Snap snap = Tracker.step(this, sampler);

        boolean want = Prefs.bool(this, Prefs.WARDRIVE_ON, false);
        if (want && !recording) startRecording();
        if (!want && recording) stopRecording();

        String note = null;
        if (want) {
            int n = maybeRecord(snap);
            if (n > 0) logged += n;
        }
        if (fix == null && hasLocation()) note = "waiting for fix";

        maybeScanRadios();

        final android.app.Notification n = Notif.build(this, snap, want, logged, note);
        ui.post(new Runnable() {
            @Override public void run() {
                try {
                    android.app.NotificationManager nm =
                            getSystemService(android.app.NotificationManager.class);
                    if (nm != null) nm.notify(Notif.ID, n);
                } catch (Throwable ignored) {}
                Widget.pushAll(MonitorService.this);
                StatsTile.refresh(MonitorService.this);
            }
        });
    }

    // ------------------------------------------------------------------ recording

    private void startRecording() {
        if (recording) return;
        session = store.startSession(
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                        .format(new java.util.Date()));
        logged = 0;
        // A radio that rode with you yesterday can be a fixed access point today, so the
        // verdicts do not survive into a new session.
        riders.reset();
        recording = true;
    }

    private void stopRecording() {
        if (!recording) return;
        store.endSession(session);
        recording = false;
    }

    /**
     * Write this sweep to the log, subject to every privacy and sanity gate.
     * Returns how many rows were written.
     */
    private int maybeRecord(Sampler.Snap snap) {
        long now = System.currentTimeMillis();
        if (now - lastRecordAt < Prefs.interval(this)) return 0;

        Location l = fix;
        if (l == null) return 0;

        float minAcc = Prefs.integer(this, Prefs.MIN_ACCURACY_M, 50);
        if (minAcc > 0 && l.getAccuracy() > minAcc) return 0;

        if (Prefs.bool(this, Prefs.MOVING_ONLY, false)) {
            float need = Prefs.real(this, Prefs.MOVING_MPS, 1.5f);
            if (!l.hasSpeed() || l.getSpeed() < need) return 0;
        }

        if (Prefs.inPrivateZone(this, l.getLatitude(), l.getLongitude())) return 0;

        lastRecordAt = now;
        if (!Prefs.bool(this, Prefs.LOG_CELLS, true)) return 0;

        boolean neigh = Prefs.bool(this, Prefs.LOG_NEIGHBOURS, true);

        // Keep the component list warm from the service as well as from a screen: a
        // recording run is exactly when nothing is open, and aggregation recorded only
        // while someone was watching would be worse than not recording it at all.
        AggFeed.refresh(this);
        java.util.List<Agg.Cc> ccs = Agg.components(this);
        int ccCount = ccs.size();
        int ccBw = Agg.totalBandwidthKhz(this);

        int wrote = 0;
        for (Sampler.Cell c : snap.cells) {
            if (!c.serving && !neigh) continue;
            Store.Obs o = new Store.Obs();
            o.session = session;
            o.ts = now;
            o.kind = Store.KIND_CELL;
            o.lat = Prefs.blur(this, l.getLatitude());
            o.lon = Prefs.blur(this, l.getLongitude());
            o.acc = l.getAccuracy();
            o.alt = (float) l.getAltitude();
            o.speed = l.hasSpeed() ? l.getSpeed() : 0;
            o.bearing = l.hasBearing() ? l.getBearing() : 0;
            o.tech = c.tech;
            o.carrier = c.carrier;
            o.mcc = c.mcc; o.mnc = c.mnc;
            o.ident = c.ident;
            o.pci = c.pci; o.tac = c.tac;
            o.arfcn = c.arfcn; o.band = c.band;
            o.dbm = c.dbm; o.qual = c.qual; o.ta = c.ta;
            o.rsrq = c.rsrq; o.rssi = c.rssi; o.cqi = c.cqi;
            o.bwKhz = c.bwKhz; o.ageMs = c.ageMs;
            o.ntn = snap.ntn && c.serving;
            o.dlMhz = c.dlMhz;
            o.serving = c.serving;
            // Aggregation belongs to the radio at this instant, not to a neighbour we
            // merely observed, so only the serving row carries it. Stays unset without
            // a privileged backend, which is the ordinary case.
            if (c.serving && ccCount > 1) {
                o.cc = ccCount;
                o.ccBwKhz = ccBw;
            }
            // One row per cell per roughly 25 m of ground, keeping the strongest.
            if (store.insertIfNew(o, 0.00025)) wrote++;
        }
        return wrote;
    }

    private void purgeOldData() {
        int days = Prefs.integer(this, Prefs.RETAIN_DAYS, 0);
        if (days <= 0) return;
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        try { store.purgeOlderThan(cutoff); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ location

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private final LocationListener locListener = new LocationListener() {
        @Override public void onLocationChanged(final Location l) {
            fix = l;
            // Off the main thread: this reads the camera table, and a query on the
            // location callback would stutter the driving view once a second.
            if (bg != null) bg.post(new Runnable() {
                @Override public void run() { checkKnownAhead(l); }
            });
            maybeLoadCameras(l);
        }
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
        @Override public void onStatusChanged(String p, int s, android.os.Bundle e) {}
    };

    private void requestLocation() {
        if (!hasLocation()) return;
        try { lm.removeUpdates(locListener); } catch (Throwable ignored) {}
        long interval = Math.min(Prefs.interval(this), 10_000);
        String provider = LocationManager.GPS_PROVIDER;
        if (Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
            provider = LocationManager.FUSED_PROVIDER;
        }
        try {
            lm.requestLocationUpdates(provider, interval, 0f, locListener, worker.getLooper());
        } catch (Throwable t) {
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, interval, 0f,
                        locListener, worker.getLooper());
            } catch (Throwable ignored) {}
        }
        try {
            Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null && fix == null) fix = last;
        } catch (Throwable ignored) {}
    }

    private static volatile MonitorService INSTANCE;

    /** The service's current fix, for screens that want it without their own listener. */
    public static Location lastFix() {
        MonitorService s = INSTANCE;
        return s == null ? null : s.fix;
    }

    /** Rows written since this recording session began, for the driving view. */
    public static int loggedCount() {
        MonitorService s = INSTANCE;
        return s == null ? 0 : s.logged;
    }

    /** True when the service is up and actually writing, not merely running. */
    /** Scan cadence used once throttling is off, for the settings screen to quote. */
    public static int fastScanSeconds() { return (int) (WIFI_SCAN_FAST_MS / 1000); }

    public static boolean recording() {
        MonitorService s = INSTANCE;
        return s != null && s.recording;
    }

    // ------------------------------------------------------------------ Wi-Fi and BLE

    private void maybeScanRadios() {
        boolean wantWifi = Prefs.bool(this, Prefs.LOG_WIFI, false)
                || Prefs.bool(this, Prefs.FLOCK_ON, false);
        boolean wantBle = Prefs.bool(this, Prefs.LOG_BLE, false)
                || Prefs.bool(this, Prefs.FLOCK_ON, false);

        long every = Throttle.isThrottled(this) ? WIFI_SCAN_MS : WIFI_SCAN_FAST_MS;
        if (wantWifi && System.currentTimeMillis() - lastWifiScan > every) {
            lastWifiScan = System.currentTimeMillis();
            try { if (wifi != null) wifi.startScan(); } catch (Throwable ignored) {}
            // The results broadcast may never arrive on a throttled scan, so read the
            // cache as well; it still carries the last successful sweep.
            handleWifiResults();
        }

        if (wantBle && !bleScanning) startBle();
        if (!wantBle && bleScanning) stopBle();
    }

    private final BroadcastReceiver wifiResults = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            bg.post(new Runnable() { @Override public void run() { handleWifiResults(); } });
        }
    };

    private void handleWifiResults() {
        if (wifi == null) return;
        List<android.net.wifi.ScanResult> results;
        try { results = wifi.getScanResults(); } catch (Throwable t) { return; }
        if (results == null || results.isEmpty()) return;

        Location l = fix;
        boolean logWifi = Prefs.bool(this, Prefs.LOG_WIFI, false)
                && Prefs.bool(this, Prefs.WARDRIVE_ON, false);
        boolean detect = Prefs.bool(this, Prefs.FLOCK_ON, false);
        boolean zoneBlocked = l != null
                && Prefs.inPrivateZone(this, l.getLatitude(), l.getLongitude());

        // getScanResults returns the platform's cache, which can be minutes old — a live
        // device here handed back results aged 330 seconds. Geotagging those at the
        // current fix pins a camera five minutes of driving away from where it actually
        // is, and re-pins the same stale rows on every pass. Age them out instead.
        long nowUs = android.os.SystemClock.elapsedRealtime() * 1000L;

        for (android.net.wifi.ScanResult r : results) {
            long ageMs = (nowUs - r.timestamp) / 1000L;
            if (ageMs > MAX_SCAN_AGE_MS) continue;
            String ssid = ssidOf(r);
            if (detect && !zoneBlocked) {
                Detector.Match m = detector.test("wifi", ssid, r.BSSID, r.level);
                if (m != null) record(m, l);
            }
            // Filtered after the detector has had its look, never before: suppressing a
            // possible camera to tidy up the log would be the wrong trade.
            if (logWifi && !zoneBlocked && l != null
                    && !Noise.isVehicle(this, ssid, r.BSSID)) {
                String mac = Prefs.mac(this, r.BSSID);
                String ident = mac != null ? "wifi:" + mac : "wifi:" + ssid;
                // The geometric rule, on the real fix rather than the stored one: a
                // blurred position would flatten the very span being measured.
                if (riders.ridesWithYou(ident, l.getLatitude(), l.getLongitude())) continue;

                Store.Obs o = new Store.Obs();
                o.session = session;
                o.kind = Store.KIND_WIFI;
                o.ts = System.currentTimeMillis();
                o.lat = Prefs.blur(this, l.getLatitude());
                o.lon = Prefs.blur(this, l.getLongitude());
                o.acc = l.getAccuracy();
                o.tech = bandOf(r);
                o.name = ssid;
                o.mac = mac;
                o.ident = ident;
                o.dbm = rssiOrAbsent(r.level);
                o.arfcn = r.frequency;
                o.extra = capabilities(r);
                store.insertIfNew(o, 0.00025);
            }
        }
    }

    private static String ssidOf(android.net.wifi.ScanResult r) {
        String s = r.SSID;
        if (s == null) return "";
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String bandOf(android.net.wifi.ScanResult r) {
        int f = r.frequency;
        if (f >= 5925) return "6 GHz";
        if (f >= 4900) return "5 GHz";
        if (f >= 2400) return "2.4 GHz";
        return f + " MHz";
    }

    private static String capabilities(android.net.wifi.ScanResult r) {
        String c = r.capabilities;
        if (c == null) return null;
        if (c.contains("WPA3") || c.contains("SAE")) return "WPA3";
        if (c.contains("WPA2") || c.contains("RSN")) return "WPA2";
        if (c.contains("WPA")) return "WPA";
        if (c.contains("WEP")) return "WEP";
        return "open";
    }

    private void startBle() {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                   != PackageManager.PERMISSION_GRANTED) return;
        try {
            BluetoothManager bm = getSystemService(BluetoothManager.class);
            BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad == null || !ad.isEnabled()) return;
            ble = ad.getBluetoothLeScanner();
            if (ble == null) return;
            // LOW_POWER duty-cycles at roughly 0.5 s of radio per 5 s. Standing still that
            // is the right trade; at 45 mph it is not. A camera is in BLE range for about
            // four seconds as you pass, so a 10% duty cycle listens for well under half a
            // second of that, and the advertisement has to land inside that sliver. This
            // is a large part of why a drive past thirty cameras can return nothing.
            boolean detecting = Prefs.bool(this, Prefs.FLOCK_ON, false);
            ScanSettings s = new ScanSettings.Builder()
                    .setScanMode(detecting ? ScanSettings.SCAN_MODE_LOW_LATENCY
                                           : ScanSettings.SCAN_MODE_LOW_POWER)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setReportDelay(0)
                    .build();
            ble.startScan(null, s, bleCallback);
            bleScanning = true;
            // Detection runs continuously; passive logging still takes a window, because
            // there it is a background nicety and not the point of the drive.
            if (!detecting) {
                bg.postDelayed(new Runnable() {
                    @Override public void run() { stopBle(); }
                }, BLE_WINDOW_MS);
            }
        } catch (Throwable ignored) {}
    }

    private void stopBle() {
        if (!bleScanning || ble == null) return;
        try { ble.stopScan(bleCallback); } catch (Throwable ignored) {}
        bleScanning = false;
    }

    private final ScanCallback bleCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            if (r == null) return;
            String name = null;
            int[] companyIds = null;
            try {
                android.bluetooth.le.ScanRecord rec = r.getScanRecord();
                if (rec != null) {
                    name = rec.getDeviceName();
                    // The company id is the part of an advertisement that survives a
                    // product rename, so it is worth pulling even though the name is
                    // usually what matches.
                    android.util.SparseArray<byte[]> mfg = rec.getManufacturerSpecificData();
                    if (mfg != null && mfg.size() > 0) {
                        companyIds = new int[mfg.size()];
                        for (int i = 0; i < mfg.size(); i++) companyIds[i] = mfg.keyAt(i);
                    }
                }
                if (name == null && r.getDevice() != null) name = r.getDevice().getName();
            } catch (Throwable ignored) {}
            String mac = r.getDevice() != null ? r.getDevice().getAddress() : null;

            Location l = fix;
            boolean zoneBlocked = l != null
                    && Prefs.inPrivateZone(MonitorService.this, l.getLatitude(), l.getLongitude());
            if (zoneBlocked) return;

            if (Prefs.bool(MonitorService.this, Prefs.FLOCK_ON, false)) {
                Detector.Match m = detector.test("ble", name, mac,
                        rssiOrAbsent(r.getRssi()), companyIds);
                if (m != null) record(m, l);
            }
            if (Prefs.bool(MonitorService.this, Prefs.LOG_BLE, false)
                    && Prefs.bool(MonitorService.this, Prefs.WARDRIVE_ON, false)
                    && l != null
                    // The real address, not the stored one: hashing is a privacy choice
                    // about the log and must not stop the user's own filter matching.
                    && !Noise.isVehicle(MonitorService.this, name, mac)) {
                String stored = Prefs.mac(MonitorService.this, mac);
                String ident = stored != null ? "ble:" + stored
                             : (name != null ? "ble:" + name : null);
                if (ident == null) return;
                if (riders.ridesWithYou(ident, l.getLatitude(), l.getLongitude())) return;

                Store.Obs o = new Store.Obs();
                o.session = session;
                o.kind = Store.KIND_BLE;
                o.ts = System.currentTimeMillis();
                o.lat = Prefs.blur(MonitorService.this, l.getLatitude());
                o.lon = Prefs.blur(MonitorService.this, l.getLongitude());
                o.acc = l.getAccuracy();
                o.name = name;
                o.mac = stored;
                o.ident = ident;
                o.dbm = rssiOrAbsent(r.getRssi());
                store.insertIfNew(o, 0.00025);
            }
        }
    };

    /**
     * A received level, or absent when the radio did not actually supply one.
     *
     * Android reports 127 from BLE scans to mean "RSSI unavailable", and the old code
     * stored it as though it were a measurement. It is not merely wrong, it is the
     * most damaging wrong value possible: +127 dBm is stronger than anything real, so
     * it wins every "strongest sighting" comparison, takes over the colour scale, and
     * places the device wherever that non-reading happened to be. Sixteen rows of it
     * were enough to skew the desktop viewer's whole map until they were found.
     *
     * Any non-negative level gets the same treatment. A radio a hundred metres away
     * cannot deliver positive dBm to a phone antenna, so a value at or above zero is a
     * sentinel or a bug either way, and recording nothing is the honest answer.
     */
    private static int rssiOrAbsent(int rssi) {
        return rssi >= 0 ? Integer.MAX_VALUE : rssi;
    }

    private void record(Detector.Match m, Location l) {
        double lat = l != null ? l.getLatitude() : Double.NaN;
        double lon = l != null ? l.getLongitude() : Double.NaN;
        float acc = l != null ? l.getAccuracy() : 0;
        if (!detector.record(m, lat, lon, acc)) return;

        // Sound first. A notification is something you read at the next light; the
        // point of this screen is that you do not have to.
        Alert.detection(this, m.observedMac != null ? m.observedMac : m.observedName);

        if (!Prefs.bool(this, Prefs.FLOCK_ALERT, true)) return;
        List<Store.Hit> recent = store.hits(1);
        if (!recent.isEmpty()) Notif.detection(this, recent.get(0));
    }

    /**
     * Alert on cameras that are already on the map and are coming up ahead.
     *
     * This is the part that actually works. A camera has to be broadcasting for the
     * detector to see it, and most of them are not — a drive past dozens of them yields
     * a single radio match. A position someone already recorded needs the camera to do
     * nothing at all.
     */
    /** True while a camera fetch is in flight, so we never stack them up. */
    private volatile boolean fetchingCameras;

    /**
     * Keep the camera layer loaded ahead of the drive.
     *
     * On its own thread rather than the sampler's: this is a network call to a public
     * server that can take tens of seconds or hang, and the sampler thread is what
     * keeps the survey log and the driving view alive.
     */
    private void maybeLoadCameras(final Location l) {
        if (l == null || fetchingCameras) return;
        if (!Prefs.bool(this, Prefs.ALPR_AUTO, true)) return;
        fetchingCameras = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Alpr.autoLoad(MonitorService.this, l.getLatitude(), l.getLongitude());
                } catch (Throwable ignored) {
                } finally {
                    fetchingCameras = false;
                }
            }
        }, "alpr-autoload").start();
    }

    private void checkKnownAhead(Location l) {
        if (l == null) return;
        if (!Prefs.bool(this, Prefs.ALERT_KNOWN, true)) return;
        if (Prefs.inPrivateZone(this, l.getLatitude(), l.getLongitude())) return;

        int range = Prefs.integer(this, Prefs.ALERT_RANGE_M, 400);
        List<Alpr.Cam> near;
        try {
            near = Alpr.near(this, l.getLatitude(), l.getLongitude(), range, 12);
        } catch (Throwable t) {
            return;
        }
        float speed = l.hasSpeed() ? l.getSpeed() : 0f;
        float heading = l.hasBearing() ? l.getBearing() : 0f;
        for (Alpr.Cam m : near) {
            // Behind you is not a warning, it is noise. Below walking pace the GPS
            // bearing is meaningless, so everything within range counts as ahead.
            if (speed >= 1.5f && !Alpr.ahead(m, heading, speed)) continue;
            Alert.approaching(this, "osm:" + m.osm);
        }
    }

    // ------------------------------------------------------------------ control

    public static void start(Context c) {
        Intent i = new Intent(c, MonitorService.class);
        try { c.startForegroundService(i); } catch (Throwable t) { c.startService(i); }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, MonitorService.class));
    }
}
