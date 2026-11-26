package com.boresight;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A task manager, in the shape of the one this app's look came from.
 *
 * Two tiers, and the screen is explicit about which is which. System-wide load, memory,
 * power, throughput and disk come from files any app may read, so they work on a bare
 * device. The per-process list does not: /proc has been hidepid-restricted since
 * Android 7, so an ordinary app sees only itself no matter how it asks. That part needs
 * the privileged backend, and says so rather than showing a list of one.
 *
 * Everything is sampled as a delta between two readings a second apart. A single
 * reading of /proc/stat is cumulative since boot and says nothing about now — the
 * classic way to produce a CPU graph that is beautiful, stable, and meaningless.
 */
public class TaskActivity extends Screen {

    private static final long TICK_MS = 1000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService pool;
    private boolean live;

    private Graph cpuGraph, memGraph, netGraph, powerGraph;
    private TextView cpuValue, memValue, netValue, powerValue, diskValue, loadLine;
    private LinearLayout coreBox, procBox;
    private TextView procNote;
    private final List<TextView> coreBars = new ArrayList<>();

    /** Previous cumulative counters, so each tick reports a rate rather than a total. */
    private long[] prevIdle, prevTotal;

    /** Byte and sector counters, averaged over a window — see Rate. */
    private final Rate rxRate = new Rate(), txRate = new Rate();
    private final Rate readRate = new Rate(), writeRate = new Rate();

    private int sortBy = 0;   // 0 = cpu, 1 = memory

    @Override protected String title() { return "TASKS"; }

    @Override protected void content() {
        pool = Executors.newSingleThreadExecutor();

        LinearLayout cpu = pane("Processor");
        cpuValue = kv(cpu, "Load", "sampling…");
        loadLine = kv(cpu, "Load average", "—");
        cpuGraph = graph(cpu, Skin.CH_RSRP, "CPU %", 0, 100, 25, 92);
        coreBox = th.column(this);
        cpu.addView(coreBox, Theme.wrap());

        LinearLayout mem = pane("Memory");
        memValue = kv(mem, "In use", "sampling…");
        memGraph = graph(mem, Skin.CH_RSRQ, "RAM %", 0, 100, 25, 80);

        LinearLayout pow = pane("Power");
        powerValue = kv(pow, "Draw", "sampling…");
        powerGraph = graph(pow, Skin.CH_SAT, "watts", -2, 12, 2, 80);
        hint(pow, "Negative is charging. Current comes from the battery fuel gauge, "
                + "which reports the whole device — there is no per-app power meter "
                + "on Android that is not a modelled estimate.");

        LinearLayout net = pane("Throughput");
        netValue = kv(net, "Now", "sampling…");
        netGraph = graph(net, Skin.CH_FREQ, "KB/s", 0, 500, 100, 80);

        LinearLayout disk = pane("Storage activity");
        diskValue = kv(disk, "Now", "sampling…");

        LinearLayout proc = pane("Processes");
        LinearLayout prow = actionRow(proc);
        smallAction(prow, "By CPU", new Runnable() {
            @Override public void run() { sortBy = 0; toast("Sorted by CPU"); }
        });
        smallAction(prow, "By memory", new Runnable() {
            @Override public void run() { sortBy = 1; toast("Sorted by memory"); }
        });
        smallAction(prow, "Refresh", new Runnable() {
            @Override public void run() { pool.execute(sampleProcesses); }
        });
        procNote = hint(proc, "");
        procBox = th.column(this);
        proc.addView(procBox, Theme.wrap());
    }

    @Override protected void onResume() {
        super.onResume();
        live = true;
        tick();
        pool.execute(sampleProcesses);
    }

    @Override protected void onPause() {
        super.onPause();
        live = false;
    }

    // ------------------------------------------------------------------ sampling

    private void tick() {
        if (!live) return;
        pool.execute(new Runnable() {
            @Override public void run() {
                final Sample s = sample();
                ui.post(new Runnable() {
                    @Override public void run() { paint(s); }
                });
            }
        });
        ui.postDelayed(new Runnable() { @Override public void run() { tick(); } }, TICK_MS);
    }

    private static final class Sample {
        float cpu = Float.NaN;
        float[] cores;
        int[] freqKhz, maxKhz;
        /** Mean of each core's clock as a percentage of its maximum. */
        float clockPct = Float.NaN;
        long memTotal, memAvail, memCached;
        float watts = Float.NaN;
        int currentMa, voltageMv, tempTenthC;
        boolean charging;
        float rxKbs, txKbs;
        float readKbs, writeKbs;
        String loadAvg = "—";
    }

    private Sample sample() {
        Sample s = new Sample();
        // One round trip for everything. Vfs reads what it can directly and bundles
        // only the refused paths into a single backend call, so a tick costs one
        // process at most rather than one per file.
        String[] got = Vfs.readMany(this,
                "/proc/stat", "/proc/meminfo", "/proc/diskstats", "/proc/loadavg");
        readCpu(s, got[0]);
        readClocks(s);
        readMem(s, got[1]);
        readDisk(s, got[2]);
        s.loadAvg = got[3].isEmpty() ? "" : got[3].split("\n")[0].trim();
        readPower(s);
        readNet(s);
        return s;
    }

    /**
     * CPU busy fraction from /proc/stat, per core and in total.
     *
     * idle+iowait are the not-working columns; everything else counts as work. The
     * figure is the change in those between this reading and the last, which is why
     * the first tick after opening the screen shows nothing.
     */
    private void readCpu(Sample s, String statBody) {
        // Measured on a Samsung Android 15 build: /proc/stat is NOT readable by an
        // ordinary app — SELinux refuses untrusted_app, so the direct read returns
        // nothing however world-readable the mode bits look. The shell uid can read
        // it, so the backend is the fallback rather than giving up on a CPU graph.
        List<long[]> rows = parseStat(statBody);
        if (rows.isEmpty()) return;

        long[] idle = new long[rows.size()], total = new long[rows.size()];
        for (int i = 0; i < rows.size(); i++) { idle[i] = rows.get(i)[0]; total[i] = rows.get(i)[1]; }

        if (prevIdle != null && prevIdle.length == idle.length) {
            float[] busy = new float[idle.length];
            for (int i = 0; i < idle.length; i++) {
                long dt = total[i] - prevTotal[i];
                long di = idle[i] - prevIdle[i];
                busy[i] = dt <= 0 ? 0 : Math.max(0, Math.min(100, 100f * (dt - di) / dt));
            }
            s.cpu = busy[0];
            s.cores = new float[busy.length - 1];
            System.arraycopy(busy, 1, s.cores, 0, busy.length - 1);
        }
        prevIdle = idle;
        prevTotal = total;
    }

    /**
     * Per-core clock, read straight from sysfs, and a load proxy when /proc/stat is shut.
     *
     * This used to hang off the core count that /proc/stat produced, so on a phone where
     * /proc/stat is refused there was no core list, therefore no clocks, therefore an
     * empty Processor pane. Measured on an Android 17 S25: /proc/stat is labelled
     * proc_stat and untrusted_app cannot read it, while every core's scaling_cur_freq
     * and cpuinfo_max_freq is mode 444 and labelled sysfs_devices_system_cpu, which an
     * app can. So the clocks are read independently and stand in for the load when
     * nothing better is available.
     *
     * The proxy is honest about being one: a core parked at its maximum clock is not
     * necessarily busy, and the readout says "clock" rather than "load".
     */
    private void readClocks(Sample s) {
        List<Integer> cur = new ArrayList<>(), max = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            String dir = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            int f = Vfs.integer(this, dir + "scaling_cur_freq", -1);
            if (f <= 0) { if (i >= 4) break; else continue; }
            cur.add(f);
            max.add(Vfs.integer(this, dir + "cpuinfo_max_freq", -1));
        }
        if (cur.isEmpty()) return;

        s.freqKhz = new int[cur.size()];
        s.maxKhz = new int[cur.size()];
        float sum = 0; int counted = 0;
        for (int i = 0; i < cur.size(); i++) {
            s.freqKhz[i] = cur.get(i);
            s.maxKhz[i] = max.get(i);
            if (max.get(i) > 0) { sum += 100f * cur.get(i) / max.get(i); counted++; }
        }
        if (counted > 0) s.clockPct = sum / counted;
    }

    private static List<long[]> parseStat(String body) {
        List<long[]> rows = new ArrayList<>();
        if (body == null) return rows;
        for (String line : body.split("\n")) {
            if (!line.startsWith("cpu")) break;
            String[] p = line.trim().split("\\s+");
            if (p.length < 8) continue;
            try {
                long idle = Long.parseLong(p[4]) + Long.parseLong(p[5]);
                long total = 0;
                for (int i = 1; i < p.length; i++) total += Long.parseLong(p[i]);
                rows.add(new long[]{ idle, total });
            } catch (Exception ignored) { }
        }
        return rows;
    }

    private void readMem(Sample s, String body) {
        for (String line : body.split("\n")) {
            if (line.startsWith("MemTotal:"))          s.memTotal = kb(line);
            else if (line.startsWith("MemAvailable:")) s.memAvail = kb(line);
            else if (line.startsWith("Cached:") && s.memCached == 0) s.memCached = kb(line);
        }
    }

    private static long kb(String line) {
        try {
            String[] p = line.trim().split("\\s+");
            return Long.parseLong(p[1]);
        } catch (Exception e) { return 0; }
    }

    private void readPower(Sample s) {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            // Reported in microamps. Sign convention differs between vendors; Samsung
            // reports negative while discharging, so the magnitude is used and the
            // direction comes from the charging flag instead.
            // Documented as microamps; Samsung reports milliamps. Dividing
            // unconditionally turned a real 12 mA into 0, so the unit is inferred
            // from the magnitude instead: a phone never draws 20 A, so anything that
            // large must already be microamps.
            int raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            s.currentMa = Math.abs(raw) >= 20000 ? raw / 1000 : raw;
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                s.voltageMv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
                s.tempTenthC = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                s.charging = st == BatteryManager.BATTERY_STATUS_CHARGING
                          || st == BatteryManager.BATTERY_STATUS_FULL;
            }
            if (s.voltageMv > 0) {
                float w = Math.abs(s.currentMa) / 1000f * (s.voltageMv / 1000f);
                s.watts = s.charging ? -w : w;
            }
        } catch (Throwable ignored) { }
    }

    /**
     * Throughput over a window rather than between two ticks.
     *
     * TrafficStats' counters do not advance on the same beat as this screen samples
     * them, so one tick would catch two updates' worth of bytes and the next would
     * catch none: the graph read 15 Mb/s, 0, 14 Mb/s, 0 through a perfectly steady
     * transfer. Rate averages across the last few seconds, which is the true figure
     * rather than a smoothed one.
     */
    private void readNet(Sample s) {
        long now = System.currentTimeMillis();
        float rx = rxRate.perSecond(TrafficStats.getTotalRxBytes(), now);
        float tx = txRate.perSecond(TrafficStats.getTotalTxBytes(), now);
        s.rxKbs = Float.isNaN(rx) ? 0 : rx / 1024f;
        s.txKbs = Float.isNaN(tx) ? 0 : tx / 1024f;
    }

    /**
     * Block device throughput from /proc/diskstats, in 512-byte sectors.
     *
     * Only the real storage devices are counted. The file lists ram disks, loop and
     * device-mapper nodes as well, and summing all of them double counts every write
     * that passes through a mapper on its way to the same flash.
     */
    private void readDisk(Sample s, String body) {
        long read = 0, write = 0;
        for (String line : body.split("\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 10) continue;
            // Whole devices only. This phone lists sda plus sda1..sda30, and summing
            // the partitions as well as their parent counted every byte twice. The
            // earlier attempt at this test was tangled enough that it matched nothing
            // at all, which is why the pane read "idle" while the disk was busy.
            if (!p[2].matches("sd[a-z]+|mmcblk\\d+|nvme\\d+n\\d+")) continue;
            try {
                read  += Long.parseLong(p[5]);
                write += Long.parseLong(p[9]);
            } catch (Exception ignored) { }
        }
        // Windowed for the same reason as the network counters: diskstats moves in
        // bursts and tick-to-tick subtraction turns a steady copy into a square wave.
        long now = System.currentTimeMillis();
        float r = readRate.perSecond(read, now);
        float w = writeRate.perSecond(write, now);
        s.readKbs  = Float.isNaN(r) ? 0 : r * 512f / 1024f;
        s.writeKbs = Float.isNaN(w) ? 0 : w * 512f / 1024f;
    }

    // ------------------------------------------------------------------ painting

    private void paint(Sample s) {
        if (!Float.isNaN(s.cpu)) {
            cpuValue.setText(String.format(Locale.US, "%.0f %%", s.cpu));
            cpuGraph.push(s.cpu);
        } else if (!Float.isNaN(s.clockPct)) {
            // No load source, but the clocks are readable. Say which it is: a core
            // sitting at its top clock is not proof that it is working.
            cpuValue.setText(String.format(Locale.US,
                    "%.0f %% of maximum clock   ·   %s", s.clockPct, whyNoLoad()));
            cpuGraph.push(s.clockPct);
        } else {
            cpuValue.setText(prevTotal == null ? "no source — " + whyNoLoad()
                                               : "first sample…");
        }
        loadLine.setText(s.loadAvg.isEmpty() ? "—" : s.loadAvg);
        paintCores(s);

        if (s.memTotal > 0) {
            long used = s.memTotal - s.memAvail;
            float pct = 100f * used / s.memTotal;
            memValue.setText(String.format(Locale.US, "%s of %s   ·   %.0f%%   ·   %s cached",
                    gb(used), gb(s.memTotal), pct, gb(s.memCached)));
            memGraph.push(pct);
        }

        if (!Float.isNaN(s.watts)) {
            powerValue.setText(String.format(Locale.US,
                    "%.2f W   ·   %d mA   ·   %.2f V   ·   %.1f °C%s",
                    Math.abs(s.watts), Math.abs(s.currentMa), s.voltageMv / 1000f,
                    s.tempTenthC / 10f, s.charging ? "   ·   charging" : ""));
            powerGraph.push(s.watts);
        }

        netValue.setText(String.format(Locale.US, "down %s   ·   up %s",
                rate(s.rxKbs), rate(s.txKbs)));
        netGraph.push(s.rxKbs + s.txKbs);

        diskValue.setText(String.format(Locale.US, "read %s   ·   write %s",
                rate(s.readKbs), rate(s.writeKbs)));
    }

    /**
     * Why there is no true load figure, in terms of this phone rather than in general.
     *
     * /proc/stat carries the SELinux label proc_stat, which untrusted_app has not been
     * allowed to read for several releases. The shell uid can, so a running backend
     * turns the graph back into real load — and saying so is more use than "no source".
     */
    private String whyNoLoad() {
        switch (Backend.state(this)) {
            case Backend.READY:
                return "/proc/stat refused even to the backend";
            case Backend.NOT_RUNNING:
                return "/proc/stat needs the shell uid; the backend is installed but "
                     + "not started";
            case Backend.NOT_GRANTED:
                return "/proc/stat needs the shell uid; the backend has not granted "
                     + "access";
            default:
                return "/proc/stat is closed to apps, and there is no backend installed";
        }
    }

    /**
     * One bar per core, from whichever signal this phone actually allows.
     *
     * Driven off the clock list rather than off /proc/stat, because the clocks are the
     * part that still works when the load is refused. With both, the bar is busy time
     * and the clock is printed beside it; with only the clocks, the bar is the clock.
     */
    private void paintCores(Sample s) {
        int n = s.cores != null ? s.cores.length
              : s.freqKhz != null ? s.freqKhz.length : 0;
        if (n == 0) return;
        final boolean haveLoad = s.cores != null;

        if (coreBars.size() != n) {
            coreBox.removeAllViews();
            coreBars.clear();
            for (int i = 0; i < n; i++) {
                LinearLayout row = th.row(this);
                row.setPadding(0, th.dp(2), 0, th.dp(2));
                TextView name = th.num(this, "cpu" + i, 10, skin.inkSecondary);
                name.setWidth(th.dp(44));
                row.addView(name);
                TextView bar = th.num(this, "", 10, skin.inkPrimary);
                bar.setTypeface(Typeface.MONOSPACE);
                row.addView(bar, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                coreBox.addView(row, Theme.wrap());
                coreBars.add(bar);
            }
        }
        for (int i = 0; i < n && i < coreBars.size(); i++) {
            int pct;
            if (haveLoad) {
                pct = Math.round(s.cores[i]);
            } else if (s.maxKhz != null && i < s.maxKhz.length && s.maxKhz[i] > 0) {
                pct = Math.round(100f * s.freqKhz[i] / s.maxKhz[i]);
            } else {
                pct = 0;
            }
            int filled = Math.round(pct / 100f * 16);
            StringBuilder b = new StringBuilder();
            for (int j = 0; j < 16; j++) b.append(j < filled ? '█' : '░');
            String f = s.freqKhz != null && i < s.freqKhz.length && s.freqKhz[i] > 0
                    ? String.format(Locale.US, "  %.2f GHz", s.freqKhz[i] / 1000000f) : "";
            TextView bar = coreBars.get(i);
            bar.setText(b + String.format(Locale.US, " %3d%%", pct) + f);
            bar.setTextColor(pct > 80 ? skin.accent(Skin.CH_ALERT)
                           : pct > 40 ? skin.accent(Skin.CH_SAT)
                           : skin.accent(Skin.CH_FREQ));
        }
    }

    private static String gb(long kbytes) {
        float g = kbytes / 1048576f;
        return g >= 1 ? String.format(Locale.US, "%.2f GB", g)
                      : String.format(Locale.US, "%.0f MB", kbytes / 1024f);
    }

    private static String rate(float kbs) {
        if (kbs >= 1024) return String.format(Locale.US, "%.2f MB/s", kbs / 1024f);
        if (kbs >= 1)    return String.format(Locale.US, "%.0f KB/s", kbs);
        return "idle";
    }

    // ------------------------------------------------------------------ processes

    private final Runnable sampleProcesses = new Runnable() {
        @Override public void run() {
            final Shell sh = Shell.get(TaskActivity.this);
            // -o chooses the columns; without it toybox picks a set that varies by
            // build and the parse drifts between devices.
            Shell.Result r = sh.exec(
                    "ps -A -o PID,USER,RSS,%CPU,NAME --sort=-%cpu 2>/dev/null "
                  + "|| ps -A -o PID,USER,RSS,NAME", 12);
            final List<String[]> rows = new ArrayList<>();
            if (r.ran && r.out != null) {
                String[] lines = r.out.split("\n");
                for (int i = 1; i < lines.length; i++) {
                    String[] p = lines[i].trim().split("\\s+", 5);
                    if (p.length < 4) continue;
                    rows.add(p);
                }
            }
            final boolean priv = sh.privileged();
            ui.post(new Runnable() {
                @Override public void run() { paintProcesses(rows, priv); }
            });
        }
    };

    private void paintProcesses(List<String[]> rows, boolean priv) {
        procBox.removeAllViews();
        if (rows.isEmpty()) {
            procNote.setText("Nothing came back. " + (priv ? "" : "An ordinary app can "
                    + "only see its own processes — /proc has been restricted since "
                    + "Android 7 — so this list needs the privileged backend."));
            return;
        }
        if (!priv && rows.size() <= 3) {
            procNote.setText("Only this app's own processes are visible. /proc is "
                    + "hidepid-restricted, so no amount of asking shows another app's "
                    + "entry; the privileged backend lifts it.");
        } else {
            procNote.setText(rows.size() + " processes  ·  "
                    + (priv ? "via the privileged backend" : "this app only")
                    + "  ·  tap one for actions");
        }

        final boolean byMem = sortBy == 1;
        Collections.sort(rows, new Comparator<String[]>() {
            @Override public int compare(String[] a, String[] b) {
                return Float.compare(num(b, byMem ? 2 : 3), num(a, byMem ? 2 : 3));
            }
        });

        int shown = 0;
        for (final String[] p : rows) {
            if (++shown > 60) break;
            final String pid = p[0], user = p[1], name = p[p.length - 1];
            long rssKb = (long) num(p, 2);
            float cpu = p.length >= 5 ? num(p, 3) : 0;

            LinearLayout row = th.column(this);
            row.setPadding(0, th.dp(5), 0, th.dp(5));
            row.setBackground(th.ripple(th.solid(0x00000000, 0)));
            row.setClickable(true);

            TextView a = th.num(this, name, 11, skin.inkPrimary);
            row.addView(a);
            TextView b = th.num(this, String.format(Locale.US,
                    "pid %-7s %-12s %8s%s", pid, user, gb(rssKb),
                    p.length >= 5 ? String.format(Locale.US, "   %.1f%% cpu", cpu) : ""),
                    9, skin.inkSecondary);
            b.setTypeface(Typeface.MONOSPACE);
            row.addView(b);

            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { processActions(pid, name, user); }
            });
            procBox.addView(row);
        }
    }

    private static float num(String[] p, int i) {
        try { return Float.parseFloat(p[i]); } catch (Exception e) { return 0; }
    }

    private void processActions(final String pid, final String name, final String user) {
        final Shell sh = Shell.get(this);
        List<String> items = new ArrayList<>();
        items.add("Details");
        if (sh.privileged()) { items.add("Force stop the app"); items.add("Kill the process"); }
        final String[] arr = items.toArray(new String[0]);

        Sheet menu = Sheet.of(this, name);
        for (int i = 0; i < arr.length; i++) {
            final int w = i;
            menu.choice(arr[i], null, false, new Runnable() {
                @Override public void run() {
                    if (w == 0) { details(pid, name, user); return; }
                    final boolean force = w == 1;
                    Sheet.of(TaskActivity.this,
                                    force ? "Force stop?" : "Kill pid " + pid + "?")
                            .text(force
                                    ? "Stops " + name + " and everything it is doing. "
                                    + "Unsaved work in that app is lost."
                                    : "Sends SIGKILL to pid " + pid + ". Killing a "
                                    + "system process can destabilise the phone "
                                    + "until it restarts or you reboot.")
                            .action(force ? "Force stop" : "Kill", true, new Runnable() {
                                @Override public void run() {
                                    String cmd = force
                                            ? "am force-stop " + pkgOf(name)
                                            : "kill -9 " + pid;
                                    Shell.Result r = sh.exec(cmd, 10);
                                    toast(r.out == null || r.out.trim().isEmpty()
                                            ? "Done" : r.firstLine());
                                    pool.execute(sampleProcesses);
                                }
                            })
                            .close("Cancel")
                            .show();
                }
            });
        }
        menu.close("Cancel").show();
    }

    /** A process name is usually the package; anything after a colon is a sub-process. */
    private static String pkgOf(String procName) {
        int i = procName.indexOf(':');
        return i > 0 ? procName.substring(0, i) : procName;
    }

    private void details(String pid, String name, String user) {
        Shell sh = Shell.get(this);
        StringBuilder b = new StringBuilder();
        b.append("pid     ").append(pid).append('\n');
        b.append("user    ").append(user).append('\n');
        b.append("name    ").append(name).append("\n\n");
        Shell.Result st = sh.exec("cat /proc/" + pid + "/status 2>/dev/null "
                + "| grep -E 'Name|State|Threads|VmRSS|VmSize|Uid'", 8);
        if (st.ran && !st.out.trim().isEmpty()) b.append(st.out.trim()).append("\n\n");
        Shell.Result io = sh.exec("cat /proc/" + pid + "/io 2>/dev/null", 8);
        if (io.ran && !io.out.trim().isEmpty()) {
            b.append("-- io --\n").append(io.out.trim()).append('\n');
        } else {
            b.append("-- io --\nnot readable for this process\n");
        }
        info(name, b.toString());
    }
}
