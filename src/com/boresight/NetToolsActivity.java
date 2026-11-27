package com.boresight;

import android.content.Context;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reachability and reconnaissance from the handset: ping, traceroute, a TCP connect
 * scanner with banner grabbing, and a few facts about how the carrier has wired you up.
 *
 * Deliberately unsubtle. Android gives apps no raw sockets, so every probe here is an
 * ordinary connection that the far end will log. There is no SYN scan, no spoofing and
 * no evasion — not as a matter of restraint but because the platform cannot do them.
 * Point it at networks you are allowed to test.
 */
public class NetToolsActivity extends Screen {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService pool;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private EditText target;
    private TextView console, summary, consolePlaceholder;
    private ScrollView consoleScroll;
    private Graph rttGraph;

    /** The ports worth trying first, with what usually answers on them. */
    private static final int[] TOP_PORTS = {
            21, 22, 23, 25, 53, 80, 110, 111, 135, 139, 143, 161, 389, 443, 445,
            465, 515, 554, 587, 631, 993, 995, 1080, 1433, 1723, 1883, 1900, 2049,
            3306, 3389, 5000, 5060, 5353, 5432, 5555, 5900, 6379, 7000, 8000, 8008,
            8080, 8081, 8443, 8888, 9000, 9100, 11211, 27017, 32400, 62078,
    };

    @Override protected String title() { return "NET TOOLS"; }

    @Override protected void content() {
        LinearLayout box = pane("Target");

        target = new EditText(this);
        target.setHint("host or IP");
        target.setSingleLine(true);
        target.setTextColor(skin.inkPrimary);
        target.setHintTextColor(skin.inkSecondary);
        target.setTypeface(Typeface.MONOSPACE);
        target.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(target, Theme.wrap());

        FlowLayout quick = new FlowLayout(this).gap(th.dp(5));
        quick.addView(chip("gateway", new Runnable() {
            @Override public void run() { target.setText(gateway()); }
        }));
        quick.addView(chip("dns", new Runnable() {
            @Override public void run() { target.setText(firstDns()); }
        }));
        quick.addView(chip("1.1.1.1", new Runnable() {
            @Override public void run() { target.setText("1.1.1.1"); }
        }));
        quick.addView(chip("portquiz.net", new Runnable() {
            @Override public void run() { target.setText("portquiz.net"); }
        }));
        LinearLayout.LayoutParams qlp = Theme.wrap();
        qlp.setMargins(0, th.dp(6), 0, 0);
        box.addView(quick, qlp);

        FlowLayout actions = new FlowLayout(this).gap(th.dp(5));
        actions.addView(chip("Ping", new Runnable() {
            @Override public void run() { start("ping"); }
        }));
        actions.addView(chip("Traceroute", new Runnable() {
            @Override public void run() { start("trace"); }
        }));
        actions.addView(chip("Scan top ports", new Runnable() {
            @Override public void run() { start("scan"); }
        }));
        actions.addView(chip("Scan 1-1024", new Runnable() {
            @Override public void run() { start("scanlow"); }
        }));
        actions.addView(chip("Link facts", new Runnable() {
            @Override public void run() { start("facts"); }
        }));
        actions.addView(chip("Stop", new Runnable() {
            @Override public void run() { cancelled.set(true); line("-- cancelled --"); }
        }));
        LinearLayout.LayoutParams alp = Theme.wrap();
        alp.setMargins(0, th.dp(6), 0, 0);
        box.addView(actions, alp);

        LinearLayout plot = pane("Round trip");
        rttGraph = graph(plot, Skin.CH_RSRP, "ms", 0, 200, 50, 96);
        rttGraph.autoScale(true);
        summary = kv(plot, "Result", "-");

        LinearLayout out = pane("Output");
        // Collapsed until a tool has run. 420dp of empty black above the buttons looks
        // like a broken view, not like an empty log.
        consolePlaceholder = th.text(this, "Output appears here.", 11, skin.inkSecondary);
        out.addView(consolePlaceholder);
        consoleScroll = new ScrollView(this);
        LinearLayout.LayoutParams clp = Theme.wrap();
        clp.height = 0;
        console = th.num(this, "", 10, skin.inkPrimary);
        console.setTypeface(Typeface.MONOSPACE);
        console.setTextIsSelectable(true);
        consoleScroll.addView(console);
        out.addView(consoleScroll, clp);

        LinearLayout row = actionRow(out);
        smallAction(row, "Clear", new Runnable() {
            @Override public void run() { ui.post(new Runnable() {
                @Override public void run() { console.setText(""); sizeConsole(); } }); }
        });
        smallAction(row, "Copy", new Runnable() {
            @Override public void run() {
                android.content.ClipboardManager cb = (android.content.ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                cb.setPrimaryClip(android.content.ClipData.newPlainText(
                        "nettools", console.getText().toString()));
                toast("Copied");
            }
        });

        hint(out, "Every probe is a full TCP connection or an ICMP echo — nothing here is "
                + "stealthy, because an app cannot open raw sockets. Scan only what you "
                + "are authorised to.");
        hint(out, "On mobile data the target is left empty rather than filled with the "
                + "default gateway. That gateway belongs to the carrier, and a target "
                + "you did not type is the wrong thing to aim a probe at. The gateway "
                + "chip still fills it when you mean to.");

        // Only when the way out is Wi-Fi or a cable.
        //
        // On mobile data the default gateway is inside the carrier's network, and
        // nobody asked to send anything to it. Filling the box means the next tap on
        // Ping aims at infrastructure the user never chose — not illegal, and not
        // stealthy either, but it is traffic they did not intend toward equipment that
        // is not theirs. A target you did not type is the wrong default; the gateway
        // chip is still there when you do mean it.
        if (onCellular()) {
            target.setHint("address or host — not prefilled on mobile data");
        } else {
            target.setText(gateway());
        }
    }

    /** True when the active network leaves through the modem. */
    private boolean onCellular() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            android.net.Network n = cm.getActiveNetwork();
            if (n == null) return false;
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null) return false;
            // Wi-Fi or ethernet wins even on a phone that also has data up: that is
            // the route the probe will actually take.
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
             || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return false;
            }
            return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR);
        } catch (Throwable t) {
            // Unsure means do not prefill. The safe default is an empty box.
            return true;
        }
    }

    private View chip(String label, final Runnable run) {
        TextView t = th.num(this, label, 11, skin.accent(Skin.CH_RSRP));
        t.setTypeface(skin.face(true, Typeface.BOLD));
        t.setPadding(th.dp(10), th.dp(6), th.dp(10), th.dp(6));
        t.setBackground(th.ripple(
                skin.badgeBg(th, Skin.alpha(skin.accent(Skin.CH_RSRP), 0.10f))));
        t.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, th.dp(5), 0);
        t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { run.run(); }
        });
        return t;
    }

    // ------------------------------------------------------------------ driver

    private void start(final String what) {
        final String host = target.getText().toString().trim();
        if (host.isEmpty() && !"facts".equals(what)) { toast("Enter a target"); return; }
        cancelled.set(false);
        if (pool != null) pool.shutdownNow();
        pool = Executors.newFixedThreadPool(64);
        rttGraph.clear();

        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    switch (what) {
                        case "ping":    doPing(host); break;
                        case "trace":   doTrace(host); break;
                        case "scan":    doScan(host, TOP_PORTS); break;
                        case "scanlow": doScan(host, range(1, 1024)); break;
                        case "facts":   doFacts(); break;
                    }
                } catch (Throwable t) {
                    line("error: " + t);
                }
            }
        });
    }

    private static int[] range(int from, int to) {
        int[] p = new int[to - from + 1];
        for (int i = 0; i < p.length; i++) p[i] = from + i;
        return p;
    }

    @Override protected void onPause() {
        super.onPause();
        cancelled.set(true);
        if (pool != null) pool.shutdownNow();
    }

    // ------------------------------------------------------------------ ping

    /**
     * Uses the system ping binary, which carries the capability to open an ICMP socket
     * that an app cannot. If exec is refused we say so rather than quietly substituting
     * a TCP handshake and calling it a ping.
     */
    private void doPing(String host) {
        line("ping " + host);
        List<Double> times = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "/system/bin/ping", "-c", "10", "-W", "2", host);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String s;
            while ((s = r.readLine()) != null && !cancelled.get()) {
                line(s);
                double t = parseTime(s);
                if (!Double.isNaN(t)) {
                    times.add(t);
                    pushRtt(t);
                }
            }
            r.close();
            p.destroy();
        } catch (Exception e) {
            line("could not run the system ping: " + e.getMessage());
            line("falling back to a TCP handshake against port 80, which measures a");
            line("connect, not an ICMP echo.");
            tcpPing(host, 80, times);
        }
        stats(times);
    }

    /** Honest fallback: a connect time, clearly labelled as such. */
    private void tcpPing(String host, int port, List<Double> times) {
        for (int i = 0; i < 6 && !cancelled.get(); i++) {
            long t0 = System.nanoTime();
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(host, port), 2000);
                double ms = (System.nanoTime() - t0) / 1e6;
                times.add(ms);
                pushRtt(ms);
                line(String.format(java.util.Locale.US,
                        "tcp %s:%d  %.1f ms", host, port, ms));
            } catch (Exception e) {
                line("tcp " + host + ":" + port + "  no answer");
            } finally {
                try { s.close(); } catch (Exception ignored) {}
            }
            sleep(400);
        }
    }

    private static double parseTime(String s) {
        int i = s.indexOf("time=");
        if (i < 0) return Double.NaN;
        int j = i + 5, k = j;
        while (k < s.length() && (Character.isDigit(s.charAt(k)) || s.charAt(k) == '.')) k++;
        try { return Double.parseDouble(s.substring(j, k)); } catch (Exception e) { return Double.NaN; }
    }

    private void stats(List<Double> times) {
        if (times.isEmpty()) { setSummary("no replies"); return; }
        double min = Double.MAX_VALUE, max = 0, sum = 0;
        for (double t : times) { min = Math.min(min, t); max = Math.max(max, t); sum += t; }
        double avg = sum / times.size();
        double jitter = 0;
        for (int i = 1; i < times.size(); i++) jitter += Math.abs(times.get(i) - times.get(i - 1));
        if (times.size() > 1) jitter /= (times.size() - 1);
        setSummary(String.format(java.util.Locale.US,
                "min %.1f   avg %.1f   max %.1f   jitter %.1f ms   n=%d",
                min, avg, max, jitter, times.size()));
    }

    // ------------------------------------------------------------------ traceroute

    /**
     * There is no traceroute binary on Android and no way to read ICMP replies directly,
     * so each hop is a single ping with the TTL pinned. The intermediate router names
     * itself in the "time to live exceeded" line, which is all a traceroute really is.
     */
    private void doTrace(String host) {
        line("traceroute to " + host + ", 30 hops max");
        for (int ttl = 1; ttl <= 30 && !cancelled.get(); ttl++) {
            String hop = null, time = null;
            boolean arrived = false;
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "/system/bin/ping", "-c", "1", "-W", "2", "-t",
                        String.valueOf(ttl), host);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String s;
                while ((s = r.readLine()) != null) {
                    if (s.contains("Time to live exceeded") || s.contains("Time exceeded")) {
                        hop = between(s, "From ", " ");
                        if (hop == null) hop = between(s, "from ", " ");
                    } else if (s.contains("bytes from")) {
                        hop = between(s, "from ", ":");
                        if (hop == null) hop = between(s, "from ", " ");
                        double t = parseTime(s);
                        if (!Double.isNaN(t)) time = String.format(java.util.Locale.US, "%.1f ms", t);
                        arrived = true;
                    }
                }
                r.close();
                p.destroy();
            } catch (Exception e) {
                line("cannot run ping with a TTL on this build: " + e.getMessage());
                return;
            }
            line(String.format("%2d  %s%s", ttl,
                    hop == null ? "*" : hop, time == null ? "" : "   " + time));
            if (arrived) { line("-- reached --"); return; }
        }
    }

    private static String between(String s, String a, String b) {
        int i = s.indexOf(a);
        if (i < 0) return null;
        i += a.length();
        int j = s.indexOf(b, i);
        if (j < 0) j = s.length();
        String out = s.substring(i, j).trim();
        while (out.endsWith(":")) out = out.substring(0, out.length() - 1);
        return out.isEmpty() ? null : out;
    }

    // ------------------------------------------------------------------ scan

    private void doScan(final String host, final int[] ports) {
        line("scanning " + host + ", " + ports.length + " ports");
        final InetAddress addr;
        try {
            addr = InetAddress.getByName(host);
        } catch (Exception e) {
            line("cannot resolve " + host);
            return;
        }
        line("resolved to " + addr.getHostAddress());

        final java.util.concurrent.atomic.AtomicInteger open =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger closed =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(ports.length);
        final long started = System.currentTimeMillis();

        for (final int port : ports) {
            pool.execute(new Runnable() {
                @Override public void run() {
                    if (cancelled.get()) { latch.countDown(); return; }
                    Socket s = new Socket();
                    long t0 = System.nanoTime();
                    try {
                        s.connect(new InetSocketAddress(addr, port), 900);
                        double ms = (System.nanoTime() - t0) / 1e6;
                        open.incrementAndGet();
                        String banner = grab(s, port);
                        line(String.format(java.util.Locale.US, "  %5d open   %6.0f ms  %s  %s",
                                port, ms, service(port), banner == null ? "" : banner));
                    } catch (java.net.ConnectException e) {
                        closed.incrementAndGet();           // refused: host is alive
                    } catch (Exception e) {
                        // timeout: filtered, or simply nothing there
                    } finally {
                        try { s.close(); } catch (Exception ignored) {}
                        latch.countDown();
                    }
                }
            });
        }
        try { latch.await(); } catch (InterruptedException ignored) {}
        long secs = (System.currentTimeMillis() - started) / 1000;
        line(String.format("done in %ds: %d open, %d refused, %d no answer",
                secs, open.get(), closed.get(), ports.length - open.get() - closed.get()));
        setSummary(open.get() + " open of " + ports.length);
        line("refused means the host is alive but nothing is listening; no answer means");
        line("filtered, or no host at all.");
    }

    /**
     * Read whatever the service announces. For HTTP, ask politely first — a bare
     * connection to a web server yields nothing until you speak.
     */
    private String grab(Socket s, int port) {
        try {
            s.setSoTimeout(1200);
            if (port == 80 || port == 8080 || port == 8000 || port == 8008
                    || port == 8081 || port == 5000 || port == 9000) {
                OutputStream o = s.getOutputStream();
                o.write("HEAD / HTTP/1.0\r\n\r\n".getBytes("UTF-8"));
                o.flush();
            } else if (port == 6379) {
                // Redis: an unauthenticated PING answering +PONG is a real finding.
                OutputStream o = s.getOutputStream();
                o.write("PING\r\n".getBytes("UTF-8"));
                o.flush();
            }
            InputStream in = s.getInputStream();
            byte[] buf = new byte[256];
            int n = in.read(buf);
            if (n <= 0) return null;
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < n; i++) {
                char c = (char) (buf[i] & 0xFF);
                if (c == '\r' || c == '\n') { if (b.length() > 0) break; continue; }
                b.append(c >= 32 && c < 127 ? c : '.');
            }
            String out = b.toString().trim();
            return out.isEmpty() ? null : (out.length() > 90 ? out.substring(0, 90) : out);
        } catch (Exception e) {
            return null;
        }
    }

    private static String service(int port) {
        switch (port) {
            case 21: return "ftp";     case 22: return "ssh";     case 23: return "telnet";
            case 25: return "smtp";    case 53: return "dns";     case 80: return "http";
            case 110: return "pop3";   case 135: return "msrpc";  case 139: return "netbios";
            case 143: return "imap";   case 161: return "snmp";   case 389: return "ldap";
            case 443: return "https";  case 445: return "smb";    case 515: return "lpd";
            case 554: return "rtsp";   case 587: return "submis"; case 631: return "ipp";
            case 993: return "imaps";  case 995: return "pop3s";  case 1433: return "mssql";
            case 1883: return "mqtt";  case 1900: return "ssdp";  case 2049: return "nfs";
            case 3306: return "mysql"; case 3389: return "rdp";   case 5060: return "sip";
            case 5353: return "mdns";  case 5432: return "pgsql"; case 5555: return "adb";
            case 5900: return "vnc";   case 6379: return "redis"; case 9100: return "jetdirect";
            case 11211: return "memcache"; case 27017: return "mongo"; case 32400: return "plex";
            case 62078: return "iphone-sync";
            default: return "";
        }
    }

    // ------------------------------------------------------------------ facts

    /**
     * How the carrier or access point has actually wired you up. The interesting part on
     * mobile is usually that there is no real IPv4 at all.
     */
    private void doFacts() {
        line("link facts");
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = cm.getActiveNetwork();
        LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
        if (lp == null) { line("no active link"); return; }

        line("interface  " + lp.getInterfaceName());
        boolean v4 = false, v6 = false, specialV4 = false;
        for (LinkAddress la : lp.getLinkAddresses()) {
            InetAddress a = la.getAddress();
            line("address    " + a.getHostAddress() + "/" + la.getPrefixLength());
            if (a instanceof Inet4Address) {
                v4 = true;
                byte[] b = a.getAddress();
                if ((b[0] & 0xFF) == 192 && (b[1] & 0xFF) == 0 && (b[2] & 0xFF) == 0) {
                    specialV4 = true;
                }
            }
            if (a instanceof Inet6Address && !a.isLinkLocalAddress()) v6 = true;
        }
        line("mtu        " + lp.getMtu());
        try {
            line("nat64      " + (lp.getNat64Prefix() == null ? "not advertised"
                    : String.valueOf(lp.getNat64Prefix())));
        } catch (Throwable ignored) {}

        // RFC 7050: if the resolver answers this v4-only name with an AAAA, it is
        // synthesising, which means NAT64 is in the path.
        try {
            InetAddress[] probe = InetAddress.getAllByName("ipv4only.arpa");
            boolean synthesised = false;
            for (InetAddress a : probe) {
                line("ipv4only   " + a.getHostAddress());
                if (a instanceof Inet6Address) synthesised = true;
            }
            line(synthesised
                    ? "DNS64 is synthesising AAAA records, so NAT64 is in the path."
                    : "no AAAA synthesis seen, so no DNS64 on this resolver.");
            if (synthesised) {
                String prefix = nat64Prefix(probe);
                if (prefix != null) {
                    line("translation prefix  " + prefix);
                    line("recovered from the synthesised answer: RFC 7050 puts the known");
                    line("address 192.0.0.170 in the low bits, so the rest is the prefix.");
                }
            }
        } catch (Exception e) {
            line("ipv4only.arpa lookup failed: " + e.getMessage());
        }

        if (specialV4 && v6) {
            line("");
            line("The IPv4 address is inside 192.0.0.0/24, which IANA reserves for");
            line("protocol assignments rather than real hosts. Combined with a global");
            line("IPv6 address and a reduced MTU, that is the signature of 464XLAT:");
            line("the bearer is IPv6 only and IPv4 is synthesised on the handset.");
        } else if (v4 && !v6) {
            line("IPv4 only.");
        }
        setSummary(lp.getInterfaceName() + "   mtu " + lp.getMtu());
    }

    /**
     * RFC 7050 asks the resolver for ipv4only.arpa, whose only real addresses are
     * 192.0.0.170 and .171. Whatever a DNS64 hands back must therefore be those bytes
     * wrapped in the translation prefix, so the prefix can be read straight off.
     * Only the /96 form is decoded, which is what essentially every carrier uses.
     */
    private static String nat64Prefix(InetAddress[] answers) {
        for (InetAddress a : answers) {
            if (!(a instanceof Inet6Address)) continue;
            byte[] b = a.getAddress();
            if (b.length != 16) continue;
            int last = ((b[12] & 0xFF) << 24) | ((b[13] & 0xFF) << 16)
                     | ((b[14] & 0xFF) << 8) | (b[15] & 0xFF);
            // 192.0.0.170 and 192.0.0.171
            if (last != 0xC00000AA && last != 0xC00000AB) continue;
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 12; i += 2) {
                if (i > 0) s.append(':');
                s.append(Integer.toHexString(((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF)));
            }
            return s.toString().replaceAll("(:0)+$", "::") + "/96";
        }
        return null;
    }
    // ------------------------------------------------------------------ plumbing

    private String gateway() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
            if (lp == null) return "";
            for (android.net.RouteInfo r : lp.getRoutes()) {
                if (r.getGateway() != null && !r.getGateway().isAnyLocalAddress()
                        && r.getGateway() instanceof Inet4Address) {
                    return r.getGateway().getHostAddress();
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private String firstDns() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
            if (lp != null && !lp.getDnsServers().isEmpty()) {
                return lp.getDnsServers().get(0).getHostAddress();
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private void pushRtt(final double ms) {
        ui.post(new Runnable() { @Override public void run() { rttGraph.push((float) ms); } });
    }

    private void setSummary(final String s) {
        ui.post(new Runnable() { @Override public void run() { summary.setText(s); } });
    }

    private void line(final String s) {
        ui.post(new Runnable() {
            @Override public void run() {
                console.append(s + "\n");
                sizeConsole();
                consoleScroll.post(new Runnable() {
                    @Override public void run() {
                        consoleScroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    /** Give the log room once it has something in it, and none before. */
    private void sizeConsole() {
        boolean empty = console.getText().length() == 0;
        consolePlaceholder.setVisibility(empty ? View.VISIBLE : View.GONE);
        android.view.ViewGroup.LayoutParams lp = consoleScroll.getLayoutParams();
        int want = empty ? 0 : th.dp(420);
        if (lp.height != want) {
            lp.height = want;
            consoleScroll.setLayoutParams(lp);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
