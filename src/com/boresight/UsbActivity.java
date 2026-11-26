package com.boresight;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Everything the phone will say about its USB port and whatever is on the end of it.
 *
 * Three unrelated sources, which is why a single API call does not cover it:
 *
 *  - {@link UsbManager} enumerates devices attached in host mode, with real USB
 *    descriptors. Vendor and product strings and the serial number need per-device
 *    permission, so those stay blank until granted — the rest of the descriptor does
 *    not, and is shown regardless.
 *  - sysfs carries what the port itself negotiated: USB-C roles, Power Delivery, the
 *    charger type and the current it agreed to. None of that is in the SDK.
 *  - The gadget configuration — whether the phone is presenting MTP, adb, RNDIS or
 *    nothing — lives in system properties.
 *
 * Nothing here is privileged, with one exception noted on screen: `dumpsys usb` is the
 * only place the port's full HAL state appears in one piece, and that needs the
 * backend.
 */
public class UsbActivity extends Screen {

    private static final String ACTION_PERM = "com.boresight.USB_PERMISSION";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService pool;
    private UsbManager usb;

    private LinearLayout portBox, gadgetBox, typecBox, deviceBox, rawBox;
    private TextView rawText;

    @Override protected String title() { return "USB"; }

    @Override protected void content() {
        pool = Executors.newSingleThreadExecutor();
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);

        LinearLayout head = pane("Port");
        portBox = th.column(this);
        head.addView(portBox, Theme.wrap());
        LinearLayout row = actionRow(head);
        smallAction(row, "Refresh", new Runnable() {
            @Override public void run() { refresh(); }
        });
        smallAction(row, "Raw dumpsys", new Runnable() {
            @Override public void run() { loadRaw(); }
        });

        LinearLayout gadget = pane("What this phone is presenting");
        gadgetBox = th.column(this);
        gadget.addView(gadgetBox, Theme.wrap());
        hint(gadget, "The gadget configuration — the set of functions the phone exposes "
                   + "to whatever it is plugged into. mtp is file transfer, ptp is "
                   + "camera, rndis and ncm are USB tethering, adb is debugging.");

        LinearLayout typec = pane("USB-C and Power Delivery");
        typecBox = th.column(this);
        typec.addView(typecBox, Theme.wrap());
        hint(typec, "Roles can be swapped independently: the end supplying power is not "
                  + "necessarily the end acting as host. A phone charging from a laptop "
                  + "is a power sink and a data device; the same phone running a USB "
                  + "stick is a power source and a host.");

        LinearLayout devs = pane("Attached devices");
        deviceBox = th.column(this);
        devs.addView(deviceBox, Theme.wrap());
        hint(devs, "Devices attached in host mode, through an OTG adapter or a C-to-C "
                 + "cable. Descriptors are readable without permission; the maker, "
                 + "model and serial strings are not, so tap a device to ask for it.");

        rawBox = pane("dumpsys usb");
        rawText = th.num(this, "press Raw dumpsys", 9, skin.inkSecondary);
        rawText.setTypeface(Typeface.MONOSPACE);
        rawText.setTextIsSelectable(true);
        rawBox.addView(rawText);

        registerReceiver(permReceiver, new IntentFilter(ACTION_PERM),
                Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0);
        IntentFilter plug = new IntentFilter();
        plug.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        plug.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        plug.addAction(Intent.ACTION_POWER_CONNECTED);
        plug.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(plugReceiver, plug);

        refresh();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(permReceiver); } catch (Throwable ignored) { }
        try { unregisterReceiver(plugReceiver); } catch (Throwable ignored) { }
    }

    @Override protected void onResume() {
        super.onResume();
        if (portBox != null) refresh();
    }

    private final BroadcastReceiver plugReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refresh(); }
    };

    private final BroadcastReceiver permReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (ACTION_PERM.equals(i.getAction())) refresh();
        }
    };

    // ------------------------------------------------------------------ refresh

    private void refresh() {
        pool.execute(new Runnable() {
            @Override public void run() {
                final Facts f = gather();
                ui.post(new Runnable() {
                    @Override public void run() { paint(f); }
                });
            }
        });
    }

    /** Everything read off the device, gathered off the main thread. */
    private static final class Facts {
        String usbOnline = "", usbType = "", usbVoltage = "", usbCurrentMax = "";
        String dataRole = "", powerRole = "", portType = "", vconn = "";
        String partnerPd = "", partnerIdentity = "", speed = "";
        String config = "", state = "", adb = "";
        String mode = "", modes = "", contaminant = "", wet = "", dualRole = "";
        boolean fromDumpsys;
        int batteryStatus, plugged, chargeMa, chargeMv;
    }

    /** Pull "key=value" out of dumpsys output; first match wins. */
    private static String field(String body, String key) {
        if (body == null) return "";
        for (String line : body.split("\n")) {
            String s = line.trim();
            if (s.startsWith(key + "=")) return s.substring(key.length() + 1).trim();
        }
        return "";
    }

    private Facts gather() {
        Facts f = new Facts();

        f.usbOnline     = sys("/sys/class/power_supply/usb/online");
        f.usbType       = firstOf(sys("/sys/class/power_supply/usb/usb_type"),
                                  sys("/sys/class/power_supply/usb/type"));
        f.usbVoltage    = sys("/sys/class/power_supply/usb/voltage_now");
        f.usbCurrentMax = firstOf(sys("/sys/class/power_supply/usb/current_max"),
                                  sys("/sys/class/power_supply/usb/input_current_limit"));

        // USB-C port state. The port is normally port0, but the index is not
        // guaranteed, so the first one that exists wins.
        String port = null;
        for (int i = 0; i < 4; i++) {
            if (new File("/sys/class/typec/port" + i).exists()
                    || !sys("/sys/class/typec/port" + i + "/data_role").isEmpty()) {
                port = "/sys/class/typec/port" + i;
                break;
            }
        }
        if (port != null) {
            f.dataRole  = sys(port + "/data_role");
            f.powerRole = sys(port + "/power_role");
            f.portType  = sys(port + "/port_type");
            f.vconn     = sys(port + "/vconn_source");
            f.partnerPd = sys(port + "-partner/supports_usb_power_delivery");
            f.partnerIdentity = firstOf(
                    sys(port + "-partner/identity/id_header"),
                    sys(port + "-partner/usb_power_delivery_revision"));
        }
        f.speed = firstOf(sys("/sys/class/udc/a600000.dwc3/current_speed"),
                          sys("/sys/class/android_usb/android0/state"));

        // Measured on this phone: /sys/class/power_supply/usb and /sys/class/typec are
        // root-only — refused to the shell uid as well, so Shizuku does not help. The
        // same facts are in dumpsys usb, which the shell *can* run, so that is the
        // real source and sysfs is just the cheaper first try.
        if (f.dataRole.isEmpty() || f.usbType.isEmpty()) {
            Shell.Result du = Shell.get(this).exec("dumpsys usb 2>/dev/null", 15);
            if (du.ran && du.out != null && du.out.contains("port_manager")) {
                f.fromDumpsys = true;
                if (f.dataRole.isEmpty())  f.dataRole  = field(du.out, "data_role");
                if (f.powerRole.isEmpty()) f.powerRole = field(du.out, "power_role");
                f.mode        = field(du.out, "current_mode");
                f.modes       = field(du.out, "supported_modes");
                f.contaminant = field(du.out, "contaminant_presence_status");
                f.wet         = field(du.out, "mIsUsbPortWet");
                f.dualRole    = field(du.out, "mSupportDualRole");
            }
        }

        Shell.Result p = Shell.get(this).exec(
                "getprop sys.usb.config; getprop sys.usb.state; "
              + "getprop persist.sys.usb.config; getprop init.svc.adbd", 8);
        if (p.ran && p.out != null) {
            String[] lines = p.out.split("\n");
            if (lines.length > 0) f.config = lines[0].trim();
            if (lines.length > 1) f.state  = lines[1].trim();
            if (lines.length > 3) f.adb    = lines[3].trim();
        }

        try {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                f.batteryStatus = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                f.plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                f.chargeMv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            }
            BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            int raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            // Samsung reports mA where the API documents µA — same trap as the task
            // manager, same magnitude test.
            f.chargeMa = Math.abs(raw) >= 20000 ? raw / 1000 : raw;
        } catch (Throwable ignored) { }

        return f;
    }

    private String sys(String path) { return Vfs.line(this, path); }

    private static String firstOf(String a, String b) {
        return a != null && !a.isEmpty() ? a : (b == null ? "" : b);
    }

    // ------------------------------------------------------------------ painting

    private void paint(Facts f) {
        portBox.removeAllViews();
        boolean attached = "1".equals(f.usbOnline) || f.plugged != 0;
        kvp(portBox, "Cable", attached ? "connected" : "nothing attached");
        kvp(portBox, "Charger type", f.usbType.isEmpty()
                ? "root-only on this build" : pretty(f.usbType));
        kvp(portBox, "Plugged as", pluggedName(f.plugged));
        if (!f.usbVoltage.isEmpty())    kvp(portBox, "Bus voltage", micro(f.usbVoltage, "V"));
        if (!f.usbCurrentMax.isEmpty()) kvp(portBox, "Negotiated current", micro(f.usbCurrentMax, "A"));
        kvp(portBox, "Battery now", f.chargeMa + " mA at " + f.chargeMv / 1000f + " V");
        kvp(portBox, "Charging", f.batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING
                ? "yes" : f.batteryStatus == BatteryManager.BATTERY_STATUS_FULL
                ? "full" : "no");
        if (!f.speed.isEmpty()) kvp(portBox, "Link speed", f.speed);
        if (!f.mode.isEmpty())  kvp(portBox, "Acting as", mode(f.mode));

        gadgetBox.removeAllViews();
        kvp(gadgetBox, "Functions now", f.config.isEmpty() ? "not reported" : f.config);
        kvp(gadgetBox, "State", f.state.isEmpty() ? "not reported" : f.state);
        kvp(gadgetBox, "adbd", f.adb.isEmpty() ? "not reported" : f.adb);

        typecBox.removeAllViews();
        if (f.dataRole.isEmpty() && f.powerRole.isEmpty()) {
            typecBox.addView(th.text(this, "Nothing readable. On this build "
                    + "/sys/class/typec is root-only — refused to the shell uid too, "
                    + "so the privileged backend does not help — and dumpsys usb did "
                    + "not answer either. Start Shizuku and press Refresh.",
                    10, skin.inkSecondary));
        } else {
            kvp(typecBox, "Data role", bracketed(f.dataRole));
            kvp(typecBox, "Power role", bracketed(f.powerRole));
            if (!f.mode.isEmpty())        kvp(typecBox, "Current mode", mode(f.mode));
            if (!f.modes.isEmpty())       kvp(typecBox, "Supported modes", f.modes);
            if (!f.dualRole.isEmpty())    kvp(typecBox, "Dual role capable", f.dualRole);
            if (!f.contaminant.isEmpty()) kvp(typecBox, "Contaminant detection", f.contaminant);
            if (!f.wet.isEmpty())         kvp(typecBox, "Moisture in port", f.wet);
            if (!f.portType.isEmpty())    kvp(typecBox, "Port type", bracketed(f.portType));
            if (!f.vconn.isEmpty())       kvp(typecBox, "VCONN source", f.vconn);
            if (!f.partnerPd.isEmpty())   kvp(typecBox, "Partner does PD", f.partnerPd);
            if (!f.partnerIdentity.isEmpty()) kvp(typecBox, "Partner identity", f.partnerIdentity);
            if (f.fromDumpsys) {
                typecBox.addView(th.text(this, "via dumpsys usb — the sysfs nodes are "
                        + "root-only on this build", 9,
                        Skin.alpha(skin.inkSecondary, 0.75f)));
            }
        }

        paintDevices();
    }

    /** sysfs role files look like "[host] device"; the brackets mark what is active. */
    private static String bracketed(String s) {
        if (s == null || s.isEmpty()) return "—";
        int a = s.indexOf('['), b = s.indexOf(']');
        return a >= 0 && b > a ? s.substring(a + 1, b) + "   (of: "
                + s.replace("[", "").replace("]", "").trim() + ")" : s;
    }

    private static String micro(String raw, String unit) {
        try {
            long v = Long.parseLong(raw.trim());
            // These nodes are microvolts or microamps on every build seen so far, but
            // a value under a thousand is already in base units somewhere.
            return v >= 1000 ? String.format(Locale.US, "%.2f %s", v / 1e6, unit)
                             : v + " (raw)";
        } catch (Exception e) { return raw; }
    }

    /** ufp/dfp are the spec terms; nobody reads a port status to be quizzed on them. */
    private static String mode(String m) {
        if (m == null) return "";
        if (m.startsWith("ufp")) return "ufp — this phone is the device end";
        if (m.startsWith("dfp")) return "dfp — this phone is the host";
        if (m.startsWith("dual")) return "dual — either, not yet settled";
        return m;
    }

    private static String pretty(String t) {
        if (t == null || t.isEmpty()) return "not reported";
        String s = t.replace("[", "").replace("]", "").trim();
        if (s.contains("PD_PPS")) return "USB-PD with programmable supply";
        if (s.contains("PD"))     return "USB Power Delivery";
        if (s.contains("DCP"))    return "dedicated charger (DCP)";
        if (s.contains("CDP"))    return "charging downstream port (CDP)";
        if (s.contains("SDP"))    return "standard downstream port (SDP) — a computer";
        return s;
    }

    private static String pluggedName(int p) {
        switch (p) {
            case BatteryManager.BATTERY_PLUGGED_AC:       return "AC";
            case BatteryManager.BATTERY_PLUGGED_USB:      return "USB";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS: return "wireless";
            case 8:                                       return "dock";
            case 0:                                       return "not plugged in";
            default:                                      return String.valueOf(p);
        }
    }

    private void paintDevices() {
        deviceBox.removeAllViews();
        Map<String, UsbDevice> list;
        try { list = usb.getDeviceList(); } catch (Throwable t) { list = new HashMap<>(); }

        UsbAccessory[] acc = null;
        try { acc = usb.getAccessoryList(); } catch (Throwable ignored) { }

        if ((list == null || list.isEmpty()) && (acc == null || acc.length == 0)) {
            deviceBox.addView(th.text(this, "Nothing attached in host mode. A phone "
                    + "plugged into a computer is the device end of the link, not the "
                    + "host, so it sees nothing here — that is the normal state while "
                    + "charging or on adb.", 10, skin.inkSecondary));
            return;
        }

        if (list != null) {
            for (final UsbDevice d : list.values()) {
                LinearLayout row = th.column(this);
                row.setPadding(0, th.dp(8), 0, th.dp(8));
                row.setBackground(th.ripple(th.solid(0x00000000, 0)));
                row.setClickable(true);

                boolean granted = false;
                try { granted = usb.hasPermission(d); } catch (Throwable ignored) { }

                String title = granted && d.getProductName() != null
                        ? d.getProductName()
                        : String.format(Locale.US, "%04X:%04X",
                                d.getVendorId(), d.getProductId());
                TextView a = th.num(this, title, 13, skin.accent(Skin.CH_RSRP));
                a.setTypeface(skin.face(true, Typeface.BOLD));
                row.addView(a);

                StringBuilder sub = new StringBuilder();
                sub.append(String.format(Locale.US, "VID %04X   PID %04X",
                        d.getVendorId(), d.getProductId()));
                sub.append("   class ").append(usbClass(d.getDeviceClass()));
                sub.append("   ").append(d.getInterfaceCount()).append(" interface(s)");
                row.addView(th.text(this, sub.toString(), 10, skin.inkSecondary));
                row.addView(th.text(this, granted ? "permission granted"
                        : "tap for maker, model and serial", 9,
                        Skin.alpha(skin.inkSecondary, 0.8f)));

                row.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { deviceDetail(d); }
                });
                deviceBox.addView(row);
            }
        }
        if (acc != null) {
            for (UsbAccessory x : acc) {
                deviceBox.addView(th.text(this, "accessory: " + x.getManufacturer()
                        + " " + x.getModel(), 11, skin.inkPrimary));
            }
        }
    }

    private void deviceDetail(final UsbDevice d) {
        boolean granted = false;
        try { granted = usb.hasPermission(d); } catch (Throwable ignored) { }
        if (!granted) {
            try {
                PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                        new Intent(ACTION_PERM).setPackage(getPackageName()),
                        PendingIntent.FLAG_IMMUTABLE);
                usb.requestPermission(d, pi);
                return;
            } catch (Throwable t) {
                toast("Could not ask: " + t.getMessage());
            }
        }

        StringBuilder b = new StringBuilder();
        b.append("path          ").append(d.getDeviceName()).append('\n');
        b.append(String.format(Locale.US, "vendor        %04X%n", d.getVendorId()));
        b.append(String.format(Locale.US, "product       %04X%n", d.getProductId()));
        b.append("class         ").append(usbClass(d.getDeviceClass()))
         .append(" / ").append(d.getDeviceSubclass())
         .append(" / ").append(d.getDeviceProtocol()).append('\n');
        try {
            if (d.getManufacturerName() != null) b.append("maker         ").append(d.getManufacturerName()).append('\n');
            if (d.getProductName() != null)      b.append("model         ").append(d.getProductName()).append('\n');
            if (d.getVersion() != null)          b.append("version       ").append(d.getVersion()).append('\n');
            if (d.getSerialNumber() != null)     b.append("serial        ").append(d.getSerialNumber()).append('\n');
        } catch (SecurityException e) {
            b.append("strings       refused without permission\n");
        }

        for (int ci = 0; ci < d.getConfigurationCount(); ci++) {
            UsbConfiguration cfg = d.getConfiguration(ci);
            b.append("\nconfiguration ").append(cfg.getId())
             .append(cfg.isSelfPowered() ? "  self powered" : "  bus powered")
             .append("  max ").append(cfg.getMaxPower() * 2).append(" mA\n");
        }
        for (int ii = 0; ii < d.getInterfaceCount(); ii++) {
            UsbInterface in = d.getInterface(ii);
            b.append("\ninterface ").append(in.getId())
             .append("  ").append(usbClass(in.getInterfaceClass()))
             .append(" / ").append(in.getInterfaceSubclass())
             .append(" / ").append(in.getInterfaceProtocol()).append('\n');
            for (int ei = 0; ei < in.getEndpointCount(); ei++) {
                UsbEndpoint ep = in.getEndpoint(ei);
                b.append("  ep ").append(String.format(Locale.US, "0x%02X", ep.getAddress()))
                 .append("  ").append(epType(ep.getType()))
                 .append("  ").append(ep.getDirection() == UsbConstantsIn ? "in" : "out")
                 .append("  max ").append(ep.getMaxPacketSize()).append('\n');
            }
        }
        info(d.getDeviceName(), b.toString());
    }

    private static final int UsbConstantsIn = android.hardware.usb.UsbConstants.USB_DIR_IN;

    private static String epType(int t) {
        switch (t) {
            case android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_CONTROL: return "control";
            case android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_ISOC:    return "isochronous";
            case android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK:    return "bulk";
            case android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_INT:     return "interrupt";
            default: return String.valueOf(t);
        }
    }

    private static String usbClass(int c) {
        switch (c) {
            case 0:   return "per interface";
            case 1:   return "audio";
            case 2:   return "comms";
            case 3:   return "HID";
            case 5:   return "physical";
            case 6:   return "still image";
            case 7:   return "printer";
            case 8:   return "mass storage";
            case 9:   return "hub";
            case 10:  return "CDC data";
            case 11:  return "smart card";
            case 13:  return "content security";
            case 14:  return "video";
            case 224: return "wireless";
            case 239: return "misc";
            case 255: return "vendor specific";
            default:  return "class " + c;
        }
    }

    private TextView kvp(LinearLayout into, String k, String v) {
        LinearLayout row = th.row(this);
        row.setPadding(0, th.dp(3), 0, th.dp(3));
        TextView a = th.text(this, k, 11, skin.inkSecondary);
        row.addView(a, new LinearLayout.LayoutParams(th.dp(150),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView b = th.num(this, v == null || v.isEmpty() ? "—" : v, 11, skin.inkPrimary);
        row.addView(b, new LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        into.addView(row, Theme.wrap());
        return b;
    }

    private void loadRaw() {
        rawText.setText("reading…");
        pool.execute(new Runnable() {
            @Override public void run() {
                Shell sh = Shell.get(UsbActivity.this);
                Shell.Result r = sh.exec("dumpsys usb", 20);
                final String body = !r.ran || r.out == null || r.out.trim().isEmpty()
                        ? (sh.privileged() ? "nothing came back"
                           : "dumpsys needs the shell uid. Start Shizuku and grant "
                           + "access, then press this again.")
                        : r.out.trim();
                ui.post(new Runnable() {
                    @Override public void run() { rawText.setText(body); }
                });
            }
        });
    }
}
