package com.boresight;

import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * Every Wi-Fi network and Bluetooth device the survey has heard, and where.
 *
 * The data was always being recorded — the map has had a Wi-Fi layer for a while — but
 * a layer of dots answers "what is around here" and not "have I seen this before", "how
 * many times", or "where was it strongest". Those are list questions, and there was no
 * list. This is it.
 *
 * Strongest first rather than most recent. The strongest sighting is the one that was
 * closest to the transmitter, which makes it both the most useful ordering for finding
 * something again and the position worth plotting.
 */
public class RadiosActivity extends Screen {

    private static final int LIMIT = 400;

    private LinearLayout list;
    private EditText search;
    private TextView summary;
    private String kind = Store.KIND_WIFI;
    private String query = "";
    private TextView wifiChip, bleChip;

    @Override protected String title() { return "RADIOS"; }

    @Override protected void content() {
        LinearLayout head = pane(null);

        LinearLayout chips = th.row(this);
        wifiChip = chip(chips, "Wi-Fi", Store.KIND_WIFI);
        bleChip = chip(chips, "Bluetooth", Store.KIND_BLE);
        head.addView(chips, Theme.wrap());

        search = new EditText(this);
        search.setHint("Filter by name or MAC");
        search.setSingleLine(true);
        search.setTextColor(skin.inkPrimary);
        search.setHintTextColor(skin.inkSecondary);
        search.setBackground(skin.tileBg(th));
        search.setPadding(th.dp(12), th.dp(10), th.dp(12), th.dp(10));
        search.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = s.toString();
                rebuild();
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMargins(0, th.dp(8), 0, 0);
        head.addView(search, sp);

        summary = th.text(this, "", 11, skin.inkSecondary);
        head.addView(summary);

        LinearLayout pane = pane("Heard");
        list = th.column(this);
        pane.addView(list, Theme.wrap());

        rebuild();
    }

    @Override protected void onResume() {
        super.onResume();
        if (list != null) rebuild();
    }

    private TextView chip(LinearLayout into, final String label, final String k) {
        final TextView t = th.num(this, label, 12, skin.inkSecondary);
        t.setPadding(th.dp(14), th.dp(8), th.dp(14), th.dp(8));
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                kind = k;
                rebuild();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(th.dp(2), 0, th.dp(2), 0);
        into.addView(t, lp);
        return t;
    }

    private void paintChips() {
        boolean wifi = Store.KIND_WIFI.equals(kind);
        wifiChip.setBackground(wifi ? skin.badgeBg(th, skin.accent(Skin.CH_FREQ)) : null);
        wifiChip.setTextColor(wifi ? skin.windowFlat() : skin.inkSecondary);
        bleChip.setBackground(!wifi ? skin.badgeBg(th, skin.accent(Skin.CH_SINR)) : null);
        bleChip.setTextColor(!wifi ? skin.windowFlat() : skin.inkSecondary);
    }

    private void rebuild() {
        paintChips();
        list.removeAllViews();

        // This screen ranks by signal, so a sentinel stored as +127 dBm would sit at
        // the top of the list looking like the strongest thing ever heard.
        Store.of(this).ensureDedupIndexAsync();

        List<Store.Radio> rows;
        try {
            rows = Store.of(this).radios(kind, query, LIMIT);
        } catch (Throwable t) {
            hint(list, "Could not read the survey: " + t.getMessage());
            return;
        }

        int total = Store.of(this).radioCount(kind);
        String what = Store.KIND_WIFI.equals(kind) ? "networks" : "devices";
        if (query.trim().isEmpty()) {
            summary.setText(total + " " + what + " in the survey"
                    + (rows.size() < total
                        ? "  ·  showing the strongest " + rows.size() : ""));
        } else {
            summary.setText(rows.size() + " of " + total + " " + what + " match");
        }

        if (rows.isEmpty()) {
            hint(list, total == 0
                    ? "Nothing logged yet. " + (Store.KIND_WIFI.equals(kind)
                        ? "Wi-Fi" : "Bluetooth")
                      + " logging is a layer in Recording, and is off by default."
                    : "Nothing matches \"" + query.trim() + "\".");
            return;
        }

        boolean first = true;
        for (Store.Radio r : rows) {
            if (!first) list.addView(th.rule(this));
            first = false;
            list.addView(row(r));
        }
    }

    private View row(final Store.Radio r) {
        LinearLayout col = th.column(this);
        col.setPadding(0, th.dp(9), 0, th.dp(9));
        col.setClickable(true);
        col.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { detail(r); }
        });

        LinearLayout top = th.row(this);
        TextView name = th.text(this, r.label(), 15, skin.inkPrimary);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView dbm = th.num(this,
                Sampler.has(r.best) ? r.best + " dBm" : "--", 14, th.grade(r.best));
        top.addView(dbm);
        col.addView(top, Theme.wrap());

        StringBuilder b = new StringBuilder();
        if (r.mac != null && !r.mac.isEmpty()) b.append(r.mac);
        if (Sampler.has(r.arfcn) && Store.KIND_WIFI.equals(r.kind)) {
            b.append("   ").append(r.arfcn).append(" MHz");
        }
        if (r.extra != null && !r.extra.isEmpty()) b.append("   ").append(r.extra);
        b.append("   ").append(r.count).append(r.count == 1 ? " sighting" : " sightings");
        if (!r.located()) b.append("   ·  no position");
        col.addView(th.text(this, b.toString(), 11, skin.inkSecondary));

        return col;
    }

    private void detail(final Store.Radio r) {
        StringBuilder b = new StringBuilder();
        b.append("identity   ").append(r.ident).append('\n');
        if (r.mac != null && !r.mac.isEmpty()) b.append("address    ").append(r.mac).append('\n');
        if (r.name == null || r.name.trim().isEmpty()) {
            b.append("name       none broadcast\n");
        }
        if (r.extra != null && !r.extra.isEmpty()) {
            b.append("security   ").append(r.extra).append('\n');
        }
        if (Sampler.has(r.arfcn) && Store.KIND_WIFI.equals(r.kind)) {
            b.append("channel    ").append(r.arfcn).append(" MHz\n");
        }
        if (Sampler.has(r.best)) b.append("strongest  ").append(r.best).append(" dBm\n");
        b.append("sightings  ").append(r.count).append('\n');
        b.append("first      ").append(Sync.fmt(r.first)).append('\n');
        b.append("last       ").append(Sync.fmt(r.last)).append('\n');

        if (r.located()) {
            b.append('\n');
            b.append(String.format(Locale.US, "strongest at  %.6f, %.6f\n", r.lat, r.lon));
            android.location.Location me = MonitorService.lastFix();
            if (me != null) {
                double d = Locate.haversineM(me.getLatitude(), me.getLongitude(),
                                             r.lat, r.lon);
                b.append("from you      ")
                 .append(Alpr.distance(d, Prefs.bool(this, Prefs.HUD_IMPERIAL, true)))
                 .append('\n');
            }
            b.append("\nThat is where this phone heard it loudest, which is the closest "
                   + "the survey ever got to it — not the transmitter's own position.");
        } else {
            b.append("\nNo position was recorded for this one. It was heard while the "
                   + "survey had no fix, or inside a privacy zone.");
        }

        Sheet s = Sheet.of(this, r.label()).mono(b.toString());
        final boolean byMac = r.mac != null && !r.mac.trim().isEmpty();
        if (byMac || (r.name != null && !r.name.trim().isEmpty())) {
            // The list is where a rider is actually spotted — something with hundreds of
            // sightings spread over a whole drive. Recognising it here and having to go
            // and type its name into a settings screen is the kind of friction that means
            // it never gets done.
            s.action("Ignore this one, and delete its " + r.count
                     + (r.count == 1 ? " row" : " rows"), true, new Runnable() {
                @Override public void run() {
                    // By address where there is one. A name is what somebody typed; an
                    // address is what the radio is, so it cannot take a bystander with it.
                    String line = Noise.ignore(RadiosActivity.this, r.name, r.mac);
                    int gone = Store.of(RadiosActivity.this).deleteIdent(r.ident);
                    toast((line == null ? "already ignored" : "ignoring " + line)
                            + ", " + gone + " rows removed");
                    rebuild();
                }
            });
        }
        if (r.located()) {
            s.action("Show on map", true, new Runnable() {
                @Override public void run() {
                    android.content.Intent i =
                            new android.content.Intent(RadiosActivity.this, MapActivity.class);
                    i.putExtra("lat", r.lat);
                    i.putExtra("lon", r.lon);
                    startActivity(i);
                }
            });
        }
        s.show();
    }
}
