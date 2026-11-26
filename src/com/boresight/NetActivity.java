package com.boresight;

import android.content.Context;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.net.wifi.WifiInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * What the data path actually is: which transport, what the system thinks it can carry,
 * every capability flag, the addressing underneath, and live throughput.
 *
 * The capability list is the interesting part. Android decides a great deal from these
 * flags — whether an app may use the link at all, whether it is treated as metered, and
 * on this phone whether satellite data is allowed through. Most of them are invisible in
 * Settings, which is how a link can be "connected" and still refuse to carry traffic.
 */
public class NetActivity extends Screen {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;

    private ConnectivityManager cm;
    private TelephonyManager tm;

    private Graph downGraph, upGraph;
    private TextView downNow, upNow, capacity;
    private LinearLayout capsBox, addrBox, wifiBox, voiceBox, allBox;
    private FlowLayout capFlow;
    private final List<TextView> capChips = new ArrayList<>();
    private final List<String> capLabels = new ArrayList<>();
    /** Current on/off per chip. Kept explicitly: inferring it back out of the rendered
     *  view is guesswork, and guessed wrong. */
    private final List<Boolean> capOn = new ArrayList<>();
    private TextView capDetail, transportsValue, signalValue, subsValue;
    private String selectedCap;

    private long lastRx, lastTx, lastAt;
    private long peakDown, peakUp;

    @Override protected String title() { return "NETWORK"; }

    @Override protected void content() {
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

        LinearLayout meter = pane("Throughput");
        LinearLayout row = th.row(this);
        downNow = th.num(this, "0", 26, skin.accent(Skin.CH_RSRP));
        downNow.setTypeface(skin.face(true, Typeface.BOLD));
        LinearLayout dCol = th.column(this);
        dCol.addView(th.caption(this, "Down kbit/s"));
        dCol.addView(downNow);
        row.addView(dCol, Theme.weight(1f));

        upNow = th.num(this, "0", 26, skin.accent(Skin.CH_SINR));
        upNow.setTypeface(skin.face(true, Typeface.BOLD));
        LinearLayout uCol = th.column(this);
        uCol.addView(th.caption(this, "Up kbit/s"));
        uCol.addView(upNow);
        row.addView(uCol, Theme.weight(1f));
        meter.addView(row, Theme.wrap());

        downGraph = graph(meter, Skin.CH_RSRP, "down kbit/s", 0, 20000, 5000, 92);
        downGraph.autoScale(true);
        upGraph = graph(meter, Skin.CH_SINR, "up kbit/s", 0, 5000, 1000, 92);
        upGraph.autoScale(true);

        capacity = kv(meter, "Link estimate", "-");
        hint(meter, "Throughput is measured from the interface counters, so it only moves "
                  + "when something is actually transferring. The link estimate is the "
                  + "modem's or driver's own claim about the pipe, not a measurement — "
                  + "treat it as the headline figure, not the real one.");

        buildCaps();
        addrBox  = pane("Addressing");
        wifiBox  = pane("Wi-Fi");
        voiceBox = pane("Voice, SMS and registration");
        allBox   = pane("Every network");
    }

    @Override protected void onResume() {
        super.onResume();
        running = true;
        lastAt = 0;
        tick();
    }

    @Override protected void onPause() {
        super.onPause();
        running = false;
        handler.removeCallbacksAndMessages(null);
    }

    private void tick() {
        if (!running) return;
        try { refresh(); } catch (Throwable t) { /* a readout must never take the page down */ }
        handler.postDelayed(new Runnable() { @Override public void run() { tick(); } },
                Math.max(500, Prefs.refresh(this)));
    }

    // ------------------------------------------------------------------ refresh

    private void refresh() {
        throughput();

        Network active = cm.getActiveNetwork();
        NetworkCapabilities nc = active == null ? null : cm.getNetworkCapabilities(active);
        LinkProperties lp = active == null ? null : cm.getLinkProperties(active);

        paintCaps(nc);
        paintAddresses(lp);
        paintWifi(nc);
        paintVoice();
        paintAll();

        String head = nc == null ? "no active network" : transports(nc);
        status(head, lp != null && lp.getInterfaceName() != null ? lp.getInterfaceName() : "");
    }

    /** Interface counters, differenced. The only honest measure of what is moving. */
    private void throughput() {
        long rx = TrafficStats.getTotalRxBytes();
        long tx = TrafficStats.getTotalTxBytes();
        long now = android.os.SystemClock.elapsedRealtime();

        if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED) {
            downNow.setText("n/a");
            upNow.setText("n/a");
            return;
        }
        if (lastAt != 0 && now > lastAt) {
            double secs = (now - lastAt) / 1000.0;
            long dKbps = Math.round((rx - lastRx) * 8 / 1000.0 / secs);
            long uKbps = Math.round((tx - lastTx) * 8 / 1000.0 / secs);
            if (dKbps < 0) dKbps = 0;
            if (uKbps < 0) uKbps = 0;
            peakDown = Math.max(peakDown, dKbps);
            peakUp = Math.max(peakUp, uKbps);
            downNow.setText(fmtRate(dKbps));
            upNow.setText(fmtRate(uKbps));
            downGraph.push(dKbps);
            upGraph.push(uKbps);
        }
        lastRx = rx; lastTx = tx; lastAt = now;
    }

    private static String fmtRate(long kbps) {
        if (kbps >= 10000) return String.format(java.util.Locale.US, "%.1f M", kbps / 1000.0);
        return String.valueOf(kbps);
    }

    // ------------------------------------------------------------------ capabilities

    private static final int[] CAP_IDS = {
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_VALIDATED,
            NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
            NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED,
            NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED,
            NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING,
            NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED,
            NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED,
            NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
            NetworkCapabilities.NET_CAPABILITY_TRUSTED,
            NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL,
            NetworkCapabilities.NET_CAPABILITY_FOREGROUND,
            NetworkCapabilities.NET_CAPABILITY_IMS,
            NetworkCapabilities.NET_CAPABILITY_MMTEL,
            NetworkCapabilities.NET_CAPABILITY_RCS,
            NetworkCapabilities.NET_CAPABILITY_MMS,
            NetworkCapabilities.NET_CAPABILITY_SUPL,
            NetworkCapabilities.NET_CAPABILITY_DUN,
            NetworkCapabilities.NET_CAPABILITY_XCAP,
            NetworkCapabilities.NET_CAPABILITY_EIMS,
            NetworkCapabilities.NET_CAPABILITY_FOTA,
            NetworkCapabilities.NET_CAPABILITY_CBS,
            NetworkCapabilities.NET_CAPABILITY_IA,
            NetworkCapabilities.NET_CAPABILITY_MCX,
            NetworkCapabilities.NET_CAPABILITY_ENTERPRISE,
            NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_LATENCY,
            NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_BANDWIDTH,
            NetworkCapabilities.NET_CAPABILITY_WIFI_P2P,
    };
    private static final String[] CAP_NAMES = {
            "INTERNET", "VALIDATED", "NOT_METERED", "TEMP_NOT_METERED", "NOT_RESTRICTED",
            "NOT_ROAMING", "NOT_CONGESTED", "NOT_SUSPENDED", "NOT_VPN", "TRUSTED",
            "CAPTIVE_PORTAL", "FOREGROUND", "IMS", "MMTEL", "RCS", "MMS", "SUPL", "DUN",
            "XCAP", "EIMS", "FOTA", "CBS", "IA", "MCX", "ENTERPRISE",
            "PRIORITIZE_LATENCY", "PRIORITIZE_BANDWIDTH", "WIFI_P2P",
    };

    /**
     * Built once, then only re-styled. The pane refreshes several times a second, and
     * rebuilding the chips each time would swap the view out from under a finger
     * mid-tap.
     */
    private void buildCaps() {
        capsBox = pane("Capabilities");

        capFlow = new FlowLayout(this).gap(th.dp(5));
        for (int i = 0; i < CAP_NAMES.length; i++) addChip(CAP_NAMES[i]);
        addChip("NOT_BW_CONSTRAINED*");
        capsBox.addView(capFlow, Theme.wrap());

        // The explanation lands here, directly under the grid.
        capDetail = th.text(this, "", 12, skin.inkPrimary);
        capDetail.setBackground(th.solid(th.panelHi, skin.badgeCornerRadius));
        capDetail.setPadding(th.dp(10), th.dp(9), th.dp(10), th.dp(10));
        capDetail.setLineSpacing(th.dpf(2f), 1f);
        capDetail.setVisibility(View.GONE);
        LinearLayout.LayoutParams dlp = Theme.wrap();
        dlp.setMargins(0, th.dp(8), 0, th.dp(2));
        capsBox.addView(capDetail, dlp);

        transportsValue = kv(capsBox, "Transports", "-");
        signalValue = kv(capsBox, "Reported signal", "-");
        subsValue = kv(capsBox, "Subscriptions", "-");

        hint(capsBox, "Tap any flag for what it means. The starred one is read from the "
                    + "platform's own description of the network, because it has no "
                    + "public constant — it is the flag that governs whether satellite "
                    + "data will carry normal traffic.");
    }

    private void addChip(final String name) {
        TextView t = th.num(this, name, 10, skin.inkSecondary);
        t.setPadding(th.dp(8), th.dp(4), th.dp(8), th.dp(4));
        t.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, th.dp(4), 0);
        t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // Tapping the open one closes it again.
                selectedCap = name.equals(selectedCap) ? null : name;
                showDetail();
            }
        });
        capFlow.addView(t);
        capChips.add(t);
        capLabels.add(name);
        capOn.add(Boolean.FALSE);
    }

    /** Re-style the chips for the current network. No views are created or removed. */
    private void paintCaps(NetworkCapabilities nc) {
        if (nc == null) {
            for (int i = 0; i < capChips.size(); i++) {
                capOn.set(i, Boolean.FALSE);
                style(capChips.get(i), false, capLabels.get(i).equals(selectedCap));
            }
            transportsValue.setText("no active network");
            signalValue.setText("-");
            subsValue.setText("-");
            capacity.setText("-");
            return;
        }

        capacity.setText(rateOrDash(nc.getLinkDownstreamBandwidthKbps()) + " down   "
                + rateOrDash(nc.getLinkUpstreamBandwidthKbps()) + " up   (claimed)");

        String described = String.valueOf(nc);
        for (int i = 0; i < capChips.size(); i++) {
            String name = capLabels.get(i);
            boolean on;
            if (name.endsWith("*")) {
                on = described.contains("NOT_BANDWIDTH_CONSTRAINED");
            } else {
                try { on = nc.hasCapability(CAP_IDS[i]); } catch (Throwable t) { on = false; }
            }
            capOn.set(i, on);
            style(capChips.get(i), on, name.equals(selectedCap));
        }

        transportsValue.setText(transports(nc));
        int sig = nc.getSignalStrength();
        signalValue.setText(sig == Integer.MIN_VALUE ? "-" : sig + " dBm");
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                java.util.Set<Integer> subs = nc.getSubscriptionIds();
                subsValue.setText(subs == null || subs.isEmpty() ? "-" : subs.toString());
            } catch (Throwable ignored) { subsValue.setText("-"); }
        }
        showDetail();
    }

    private void style(TextView chip, boolean on, boolean selected) {
        chip.setTextColor(on ? skin.windowFlat() : skin.inkSecondary);
        chip.setTypeface(skin.face(true, on ? Typeface.BOLD : Typeface.NORMAL));
        android.graphics.drawable.GradientDrawable base = on
                ? th.solid(skin.accent(Skin.CH_FREQ), skin.badgeCornerRadius)
                : (android.graphics.drawable.GradientDrawable)
                        skin.badgeBg(th, 0x00000000);
        if (selected) base.setStroke(Math.max(2, th.dp(2)), skin.accent(Skin.CH_ALERT));
        chip.setBackground(th.ripple(base));
    }

    /** Render the chosen flag's explanation inline, under the grid. */
    private void showDetail() {
        if (selectedCap == null) {
            capDetail.setVisibility(View.GONE);
            return;
        }
        String key = selectedCap.replace("*", "");
        int idx = capLabels.indexOf(selectedCap);
        boolean on = idx >= 0 && Boolean.TRUE.equals(capOn.get(idx));

        String body = CAP_HELP.get(key);
        if (body == null) body = "No description for this flag.";

        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        sb.append(key).append("   ");
        sb.setSpan(new android.text.style.ForegroundColorSpan(skin.accent(Skin.CH_RSRP)),
                0, key.length(), android.text.Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),
                0, key.length(), android.text.Spanned.SPAN_INCLUSIVE_EXCLUSIVE);

        int from = sb.length();
        String state = on ? "present" : "absent";
        sb.append(state);
        sb.setSpan(new android.text.style.ForegroundColorSpan(
                        on ? skin.accent(Skin.CH_FREQ) : skin.accent(Skin.CH_ALERT)),
                from, sb.length(), android.text.Spanned.SPAN_INCLUSIVE_EXCLUSIVE);

        sb.append("\n\n").append(body);
        capDetail.setText(sb);
        capDetail.setVisibility(View.VISIBLE);
    }
    private static String transports(NetworkCapabilities nc) {
        List<String> out = new ArrayList<>();
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) out.add("Wi-Fi");
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) out.add("Cellular");
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) out.add("VPN");
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) out.add("Ethernet");
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) out.add("Bluetooth");
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_USB)) out.add("USB");
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) out.add("Wi-Fi Aware");
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN)) out.add("LoWPAN");
        try {
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE)) out.add("SATELLITE");
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_THREAD)) out.add("Thread");
        } catch (Throwable ignored) {}
        return out.isEmpty() ? "-" : android.text.TextUtils.join(" + ", out);
    }

    // ------------------------------------------------------------------ addressing

    private void paintAddresses(LinkProperties lp) {
        addrBox.removeAllViews();
        addrBox.addView(caption("Addressing"));
        if (lp == null) {
            addrBox.addView(note("No active link."));
            return;
        }
        LinearLayout t = th.column(this);
        kv(t, "Interface", lp.getInterfaceName());

        for (LinkAddress la : lp.getLinkAddresses()) {
            InetAddress a = la.getAddress();
            String label = a instanceof Inet4Address ? "IPv4" : "IPv6";
            String value = a.getHostAddress() + "/" + la.getPrefixLength();
            if (a instanceof Inet4Address) {
                value += "   mask " + maskOf(la.getPrefixLength())
                       + "   net " + networkOf(a, la.getPrefixLength());
            }
            kv(t, label, value);
        }

        List<String> dns = new ArrayList<>();
        for (InetAddress d : lp.getDnsServers()) dns.add(d.getHostAddress());
        kv(t, "DNS", dns.isEmpty() ? "-" : android.text.TextUtils.join("  ", dns));

        if (Build.VERSION.SDK_INT >= 28) {
            kv(t, "Private DNS", lp.isPrivateDnsActive()
                    ? (lp.getPrivateDnsServerName() == null
                        ? "on, opportunistic" : "on, " + lp.getPrivateDnsServerName())
                    : "off");
        }
        if (lp.getDomains() != null) kv(t, "Search domains", lp.getDomains());

        List<String> routes = new ArrayList<>();
        for (android.net.RouteInfo r : lp.getRoutes()) {
            if (r.getGateway() != null && !r.getGateway().isAnyLocalAddress()) {
                routes.add(r.getGateway().getHostAddress());
            }
        }
        kv(t, "Gateway", routes.isEmpty() ? "-" : android.text.TextUtils.join("  ", routes));
        kv(t, "Routes", String.valueOf(lp.getRoutes().size()));
        if (lp.getMtu() > 0) kv(t, "MTU", String.valueOf(lp.getMtu()));
        if (lp.getHttpProxy() != null) kv(t, "Proxy", String.valueOf(lp.getHttpProxy()));
        try {
            if (lp.getNat64Prefix() != null) kv(t, "NAT64", String.valueOf(lp.getNat64Prefix()));
        } catch (Throwable ignored) {}
        addrBox.addView(t, Theme.wrap());
    }

    private static String maskOf(int prefix) {
        if (prefix < 0 || prefix > 32) return "-";
        int bits = prefix == 0 ? 0 : 0xFFFFFFFF << (32 - prefix);
        return ((bits >>> 24) & 0xFF) + "." + ((bits >>> 16) & 0xFF) + "."
             + ((bits >>> 8) & 0xFF) + "." + (bits & 0xFF);
    }

    private static String networkOf(InetAddress a, int prefix) {
        byte[] b = a.getAddress();
        if (b.length != 4 || prefix < 0 || prefix > 32) return "-";
        int v = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16)
              | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        int mask = prefix == 0 ? 0 : 0xFFFFFFFF << (32 - prefix);
        int n = v & mask;
        return ((n >>> 24) & 0xFF) + "." + ((n >>> 16) & 0xFF) + "."
             + ((n >>> 8) & 0xFF) + "." + (n & 0xFF) + "/" + prefix;
    }

    // ------------------------------------------------------------------ wifi

    private void paintWifi(NetworkCapabilities nc) {
        wifiBox.removeAllViews();
        wifiBox.addView(caption("Wi-Fi"));

        WifiInfo wi = null;
        if (nc != null && nc.getTransportInfo() instanceof WifiInfo) {
            wi = (WifiInfo) nc.getTransportInfo();
        }
        if (wi == null) {
            wifiBox.addView(note("Not on Wi-Fi."));
            return;
        }
        LinearLayout t = th.column(this);
        kv(t, "SSID", strip(wi.getSSID()));
        kv(t, "BSSID", wi.getBSSID());
        int f = wi.getFrequency();
        kv(t, "Frequency", f + " MHz   " + wifiBand(f) + "   ch " + wifiChannel(f));
        kv(t, "RSSI", wi.getRssi() + " dBm");
        kv(t, "Link speed", wi.getLinkSpeed() + " Mbit/s");
        if (Build.VERSION.SDK_INT >= 29) {
            kv(t, "Tx / Rx rate", wi.getTxLinkSpeedMbps() + " / "
                    + wi.getRxLinkSpeedMbps() + " Mbit/s");
        }
        if (Build.VERSION.SDK_INT >= 30) kv(t, "Standard", wifiStandard(wi.getWifiStandard()));
        if (Build.VERSION.SDK_INT >= 31) {
            try { kv(t, "Security", security(wi.getCurrentSecurityType())); }
            catch (Throwable ignored) {}
        }
        kv(t, "MAC", wi.getMacAddress());
        kv(t, "Hidden SSID", String.valueOf(wi.getHiddenSSID()));
        wifiBox.addView(t, Theme.wrap());
        hint(wifiBox, "The MAC is usually randomised per network, so it is not the "
                    + "radio's real hardware address.");
    }

    private static String strip(String ssid) {
        if (ssid == null) return "-";
        if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() > 1) {
            return ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }

    private static String wifiBand(int mhz) {
        if (mhz >= 5925) return "6 GHz";
        if (mhz >= 4900) return "5 GHz";
        if (mhz >= 2400) return "2.4 GHz";
        return "?";
    }

    private static int wifiChannel(int mhz) {
        if (mhz == 2484) return 14;
        if (mhz >= 2412 && mhz <= 2472) return (mhz - 2412) / 5 + 1;
        if (mhz >= 5160 && mhz <= 5885) return (mhz - 5000) / 5;
        if (mhz >= 5955 && mhz <= 7115) return (mhz - 5955) / 5 + 1;
        return -1;
    }

    private static String wifiStandard(int s) {
        switch (s) {
            case 1: return "802.11 legacy";
            case 4: return "802.11n  (Wi-Fi 4)";
            case 5: return "802.11ac  (Wi-Fi 5)";
            case 6: return "802.11ax  (Wi-Fi 6)";
            case 7: return "802.11ad";
            case 8: return "802.11be  (Wi-Fi 7)";
            default: return "unknown";
        }
    }

    private static String security(int s) {
        switch (s) {
            case 0:  return "open";
            case 1:  return "WEP";
            case 2:  return "WPA personal";
            case 4:  return "WPA2 personal";
            case 5:  return "WPA2 enterprise";
            case 6:  return "WPA3 SAE";
            case 7:  return "WPA3 enterprise 192-bit";
            case 8:  return "OWE";
            case 9:  return "WAPI personal";
            case 11: return "WPA3 enterprise";
            default: return "type " + s;
        }
    }

    // ------------------------------------------------------------------ voice

    private void paintVoice() {
        voiceBox.removeAllViews();
        voiceBox.addView(caption("Voice, SMS and registration"));
        LinearLayout t = th.column(this);

        try {
            kv(t, "Voice capable", yn(tm.isVoiceCapable()));
            kv(t, "SMS capable", yn(tm.isSmsCapable()));
        } catch (Throwable ignored) {}
        try {
            kv(t, "Voice network", Rf.netName(tm.getVoiceNetworkType()));
            kv(t, "Data network", Rf.netName(tm.getDataNetworkType()));
        } catch (Throwable ignored) {}

        try {
            ServiceState ss = tm.getServiceState();
            if (ss != null) {
                kv(t, "Service state", stateName(ss.getState()));
                kv(t, "Operator", ss.getOperatorAlphaLong() + "   "
                        + ss.getOperatorNumeric());
                kv(t, "Roaming", yn(ss.getRoaming()));
                for (NetworkRegistrationInfo r : ss.getNetworkRegistrationInfoList()) {
                    String domain = r.getDomain() == NetworkRegistrationInfo.DOMAIN_CS ? "CS (voice)"
                            : r.getDomain() == NetworkRegistrationInfo.DOMAIN_PS ? "PS (data)"
                            : "domain " + r.getDomain();
                    StringBuilder v = new StringBuilder();
                    v.append(r.isRegistered() ? "registered" : "not registered");
                    List<Integer> svc = r.getAvailableServices();
                    if (svc != null && !svc.isEmpty()) {
                        v.append("   serves ");
                        for (int i = 0; i < svc.size(); i++) {
                            if (i > 0) v.append(',');
                            v.append(serviceName(svc.get(i)));
                        }
                    }
                    kv(t, domain, v.toString());
                }
            }
        } catch (SecurityException e) {
            kv(t, "Service state", "needs phone state permission");
        } catch (Throwable ignored) {}

        voiceBox.addView(t, Theme.wrap());
        hint(voiceBox, "\"Serves\" is what the network says this registration will carry. "
                   + "A registration offering data and SMS but not voice is exactly what "
                   + "satellite looks like, and it is why the dialer refuses a normal "
                   + "call there while a VoIP app still works.");
    }

    private static String serviceName(int s) {
        switch (s) {
            case NetworkRegistrationInfo.SERVICE_TYPE_VOICE:     return "voice";
            case NetworkRegistrationInfo.SERVICE_TYPE_DATA:      return "data";
            case NetworkRegistrationInfo.SERVICE_TYPE_SMS:       return "sms";
            case NetworkRegistrationInfo.SERVICE_TYPE_VIDEO:     return "video";
            case NetworkRegistrationInfo.SERVICE_TYPE_EMERGENCY: return "emergency";
            default: return "svc" + s;
        }
    }

    private static String stateName(int s) {
        switch (s) {
            case ServiceState.STATE_IN_SERVICE:     return "in service";
            case ServiceState.STATE_OUT_OF_SERVICE: return "out of service";
            case ServiceState.STATE_EMERGENCY_ONLY: return "emergency only";
            case ServiceState.STATE_POWER_OFF:      return "radio off";
            default: return "state " + s;
        }
    }

    // ------------------------------------------------------------------ all networks

    private void paintAll() {
        allBox.removeAllViews();
        allBox.addView(caption("Every network"));
        Network[] nets = cm.getAllNetworks();
        if (nets == null || nets.length == 0) {
            allBox.addView(note("None reported."));
            return;
        }
        Network active = cm.getActiveNetwork();
        for (Network n : nets) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            LinkProperties lp = cm.getLinkProperties(n);
            if (nc == null) continue;
            StringBuilder b = new StringBuilder();
            b.append(transports(nc));
            if (lp != null && lp.getInterfaceName() != null) {
                b.append("   ").append(lp.getInterfaceName());
            }
            b.append(nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    ? "   validated" : "   unvalidated");
            if (!nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                b.append("   metered");
            }
            boolean isActive = active != null && active.equals(n);
            TextView t = th.num(this, (isActive ? "> " : "  ") + b, 11,
                    isActive ? skin.accent(Skin.CH_RSRP) : skin.inkSecondary);
            t.setPadding(0, th.dp(3), 0, th.dp(3));
            allBox.addView(t);
        }
        hint(allBox, "A VPN shows as its own network stacked over the real one. That is "
                   + "how a tunnel can present different capabilities from the link "
                   + "actually carrying it.");
    }

    // ------------------------------------------------------------------ helpers

    private TextView caption(String s) {
        TextView t = th.caption(this, s);
        t.setTextColor(skin.accent(Skin.CH_RSRP));
        t.setPadding(0, 0, 0, th.dp(5));
        return t;
    }

    private TextView note(String s) {
        TextView t = th.text(this, s, 12, skin.inkSecondary);
        t.setPadding(0, th.dp(4), 0, th.dp(4));
        return t;
    }

    private static String rateOrDash(int kbps) {
        if (kbps <= 0) return "-";
        if (kbps >= 1000) return String.format(java.util.Locale.US, "%.1f Mbit/s", kbps / 1000.0);
        return kbps + " kbit/s";
    }

    /**
     * What each capability means, and — more usefully — what its absence means. The
     * NOT_ flags read backwards: it is the missing ones that change behaviour.
     */
    private static final java.util.Map<String, String> CAP_HELP = new java.util.HashMap<>();
    static {
        CAP_HELP.put("INTERNET",
                "The network is intended to reach the general internet, as opposed to a "
              + "special-purpose carrier network like MMS or IMS.\n\n"
              + "This says what the link is for, not that it works. VALIDATED is the one "
              + "that says it actually carries traffic.");
        CAP_HELP.put("VALIDATED",
                "Android probed a known endpoint through this network and got a reply, so "
              + "it is genuinely carrying traffic.\n\n"
              + "INTERNET present but VALIDATED absent is the classic signature of a dead "
              + "link, or a captive portal you have not signed into.");
        CAP_HELP.put("NOT_METERED",
                "The network is not billed by the byte.\n\n"
              + "When this is absent the system treats data as costly: Data Saver applies, "
              + "background sync is held back, and apps that check are expected to defer "
              + "large downloads. Cellular is normally metered; Wi-Fi normally is not, "
              + "unless you marked it metered yourself.");
        CAP_HELP.put("TEMP_NOT_METERED",
                "The carrier has temporarily lifted metering - a promotional free-data "
              + "window, for instance. Treated as unmetered while it lasts.");
        CAP_HELP.put("NOT_RESTRICTED",
                "Any app may use this network.\n\n"
              + "A restricted network is reserved for apps holding a signature-level "
              + "permission. The carrier's IMS, CBS and similar special APNs are "
              + "restricted, which is why they appear in the network list but ordinary "
              + "apps cannot route over them.");
        CAP_HELP.put("NOT_ROAMING",
                "You are on your home network. Absent means roaming, which usually also "
              + "means different billing.");
        CAP_HELP.put("NOT_CONGESTED",
                "The network is not currently congested.\n\n"
              + "When absent, apps are expected to back off heavy or deferrable traffic. "
              + "Carriers can signal this, and Wi-Fi can infer it.");
        CAP_HELP.put("NOT_SUSPENDED",
                "The network can currently pass packets.\n\n"
              + "A suspended network keeps its IP address and stays connected but moves no "
              + "data - classically during a voice call on an older radio, or a brief "
              + "outage. Sockets stay open rather than failing, which is why an app can "
              + "look connected and simply hang.");
        CAP_HELP.put("NOT_VPN",
                "This is the real network rather than a tunnel. A VPN appears as its own "
              + "network stacked on top of the one actually carrying it, and can present "
              + "different capabilities from the link underneath.");
        CAP_HELP.put("TRUSTED",
                "The user considers this network trusted. Untrusted networks are not used "
              + "by default and need an explicit request from an app.");
        CAP_HELP.put("CAPTIVE_PORTAL",
                "Android detected a sign-in page intercepting traffic - hotel or airport "
              + "Wi-Fi. Present means you still need to log in.");
        CAP_HELP.put("FOREGROUND",
                "This network is in the foreground for this app: the one your traffic is "
              + "actually using, rather than a background candidate the system is keeping "
              + "warm.");
        CAP_HELP.put("IMS",
                "A carrier APN dedicated to IMS signalling - the control channel behind "
              + "VoLTE, VoWiFi and RCS. Normally restricted, so only the phone stack uses "
              + "it. Voice calls ride this, not your data APN.");
        CAP_HELP.put("MMTEL",
                "The network supports multimedia telephony: voice and video carried over "
              + "IMS rather than a circuit-switched channel.");
        CAP_HELP.put("RCS",
                "A carrier APN for Rich Communication Services - the successor to SMS that "
              + "carries read receipts, typing indicators and better media.");
        CAP_HELP.put("MMS",
                "A carrier APN for multimedia messaging. MMS often uses a separate APN "
              + "from your data, which is why picture messages can work when data is off, "
              + "or fail when data works.");
        CAP_HELP.put("SUPL",
                "Secure User Plane Location - the channel that delivers assisted GPS data: "
              + "the almanac and ephemeris that let the receiver find satellites quickly. "
              + "Related to the assistance controls on the Satellites screen.");
        CAP_HELP.put("DUN",
                "Dial-Up Networking APN, used for tethering. Some carriers require "
              + "tethered traffic to use this separate APN so they can meter or block it "
              + "independently of your normal data.");
        CAP_HELP.put("XCAP",
                "The APN used to provision supplementary telephony settings - call "
              + "forwarding, call barring, caller ID preferences - over HTTP.");
        CAP_HELP.put("EIMS",
                "Emergency IMS: emergency calling carried over IMS. Available even with no "
              + "subscription or a locked SIM, which is why a phone with no service can "
              + "still reach emergency services.");
        CAP_HELP.put("FOTA",
                "A carrier APN reserved for firmware-over-the-air updates.");
        CAP_HELP.put("CBS",
                "Carrier Branded Services - an operator-specific APN for the carrier's own "
              + "applications.");
        CAP_HELP.put("IA",
                "Initial Attach: the APN the device uses to attach to the network before "
              + "any other bearer is established.");
        CAP_HELP.put("MCX",
                "Mission Critical Services - the public-safety profile covering mission "
              + "critical push-to-talk, video and data.");
        CAP_HELP.put("ENTERPRISE",
                "A network slice dedicated to enterprise use, typically bound to a work "
              + "profile and kept separate from personal traffic.");
        CAP_HELP.put("PRIORITIZE_LATENCY",
                "The network is configured to favour low latency over raw throughput - a "
              + "5G slice tuned for responsiveness rather than bulk transfer.");
        CAP_HELP.put("PRIORITIZE_BANDWIDTH",
                "The opposite trade: the network favours throughput over latency.");
        CAP_HELP.put("WIFI_P2P",
                "A Wi-Fi Direct peer-to-peer link rather than a connection to an access "
              + "point.");
        CAP_HELP.put("NOT_BW_CONSTRAINED",
                "The network is not bandwidth constrained, so ordinary traffic may use it "
              + "freely.\n\n"
              + "This is the flag that governs satellite data. When it is absent the link "
              + "is treated as precious: only apps that have declared they can cope with a "
              + "constrained network may use it, and everything else is held back. That is "
              + "the exact mechanism that limited T-Satellite to approved apps.\n\n"
              + "It is a system-only flag with no public constant, so this app reads it "
              + "from the platform's own description of the network rather than querying "
              + "it directly.");
    }
    private static String yn(boolean b) { return b ? "yes" : "no"; }
}
