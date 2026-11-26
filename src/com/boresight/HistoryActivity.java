package com.boresight;

import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * What the survey log adds up to: totals, the sessions it was gathered in, and the cells
 * that turn up most and loudest.
 *
 * The lists are rebuilt on resume rather than on a timer, because this is a ledger rather
 * than a live readout, and because a list that reorders itself under your thumb is
 * useless for picking a row out of it.
 */
public class HistoryActivity extends Screen {

    private Store store;

    private TextView cellTotal, wifiTotal, bleTotal, hitTotal, sessionTotal, rowTotal;
    private LinearLayout sessionList, mostList, strongList;

    @Override protected String title() { return "HISTORY"; }

    @Override protected void content() {
        store = Store.of(this);

        LinearLayout totals = pane("Totals");
        cellTotal    = kv(totals, "Cell sightings", "-");
        wifiTotal    = kv(totals, "Wi-Fi sightings", "-");
        bleTotal     = kv(totals, "BLE sightings", "-");
        hitTotal     = kv(totals, "Detections", "-");
        sessionTotal = kv(totals, "Sessions", "-");
        rowTotal     = kv(totals, "Rows in the log", "-");

        LinearLayout sessions = pane("Sessions");
        hint(sessions, "One session per run of the recorder. Tap to rename or delete; "
                     + "deleting a session takes its sightings with it.");
        sessionList = th.column(this);
        sessions.addView(sessionList, Theme.wrap());

        LinearLayout most = pane("Most seen cells");
        mostList = th.column(this);
        most.addView(mostList, Theme.wrap());

        LinearLayout strong = pane("Strongest cells");
        hint(strong, "Ranked by the single best reading ever logged, which is usually the "
                   + "closest approach rather than the best sector.");
        strongList = th.column(this);
        strong.addView(strongList, Theme.wrap());

        LinearLayout out = pane("Export");
        hint(out, "Written to Downloads. Nothing leaves the phone unless you press one "
                + "of these.");
        LinearLayout row = actionRow(out);
        smallAction(row, "CSV", new Runnable() {
            @Override public void run() {
                report(Export.csv(HistoryActivity.this, store.recent(200_000)));
            }
        });
        smallAction(row, "GeoJSON", new Runnable() {
            @Override public void run() {
                report(Export.geojson(HistoryActivity.this,
                        store.recent(200_000), store.hits(5000)));
            }
        });
        smallAction(row, "WiGLE", new Runnable() {
            @Override public void run() {
                report(Export.wigle(HistoryActivity.this, store.recent(200_000)));
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void report(Export.Result r) {
        if (r.ok) info("Exported", r.rows + " rows written to\n\n" + r.where);
        else info("Export failed", r.error == null ? "Unknown error" : r.error);
    }

    // ------------------------------------------------------------------ refresh

    private void refresh() {
        int cells = store.count(Store.KIND_CELL);
        int wifi = store.count(Store.KIND_WIFI);
        int ble = store.count(Store.KIND_BLE);
        int rows = store.count(null);

        cellTotal.setText(cells + "   ·   " + store.distinctCount(Store.KIND_CELL)
                + " distinct");
        wifiTotal.setText(wifi + "   ·   " + store.distinctCount(Store.KIND_WIFI)
                + " distinct");
        bleTotal.setText(String.valueOf(ble));
        hitTotal.setText(String.valueOf(store.hitCount()));
        rowTotal.setText(String.valueOf(rows));

        List<Store.Session> sessions = store.sessions();
        sessionTotal.setText(String.valueOf(sessions.size()));

        sessionList.removeAllViews();
        if (sessions.isEmpty()) {
            hint(sessionList, "Nothing recorded yet.");
        } else {
            for (Store.Session s : sessions) sessionList.addView(sessionRow(s));
        }

        mostList.removeAllViews();
        List<Store.Tally> most = store.tallies(Store.KIND_CELL, "COUNT(*) DESC", 40);
        if (most.isEmpty()) hint(mostList, "No cells logged yet.");
        else for (Store.Tally t : most) mostList.addView(tallyRow(t, false));

        strongList.removeAllViews();
        List<Store.Tally> strong = store.tallies(Store.KIND_CELL, "MAX(dbm) DESC", 20);
        if (strong.isEmpty()) hint(strongList, "No cells logged yet.");
        else for (Store.Tally t : strong) strongList.addView(tallyRow(t, true));

        status(Theme.count(rows, "row"), Theme.count(sessions.size(), "session"));
    }

    // ------------------------------------------------------------------ rows

    private View sessionRow(final Store.Session s) {
        LinearLayout row = th.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, th.dp(8), 0, th.dp(8));
        row.setBackground(th.ripple(th.solid(0x00000000, 0)));
        row.setClickable(true);

        LinearLayout left = th.column(this);
        TextView label = th.text(this,
                s.label == null || s.label.isEmpty() ? "session " + s.id : s.label,
                12, skin.inkPrimary);
        label.setTypeface(skin.face(false, Typeface.BOLD));
        left.addView(label);

        // The span of the sightings, not of the session row. An imported session's own
        // timestamps are the moment of the import, so a ten-hour drive read "0s".
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        long[] span = store.sessionSpan(s.id);
        StringBuilder sub = new StringBuilder();
        if (span == null) {
            sub.append(f.format(new Date(s.started))).append("   no sightings");
        } else {
            sub.append(f.format(new Date(span[0])));
            sub.append("   ");
            sub.append(span[1] > span[0]
                    ? MainActivity.fmtAge(span[1] - span[0])
                    : "one moment");
            if (s.ended <= 0) sub.append("   still open");
        }
        left.addView(th.num(this, sub.toString(), 9, skin.inkSecondary));
        row.addView(left, Theme.weight(1f));

        TextView pts = th.num(this, String.valueOf(s.points), 13,
                s.points > 0 ? skin.accent(Skin.CH_RSRP) : skin.inkSecondary);
        pts.setTypeface(skin.face(true, Typeface.BOLD));
        pts.setGravity(Gravity.END);
        row.addView(pts);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sessionMenu(s); }
        });
        return row;
    }

    private void sessionMenu(final Store.Session s) {
        String name = s.label == null || s.label.isEmpty() ? "session " + s.id : s.label;
        Sheet.of(this, name)
                .choice("Rename", null, false, new Runnable() {
                    @Override public void run() { renameSession(s); }
                })
                .choice("Delete", null, false, new Runnable() {
                    @Override public void run() { confirmDelete(s); }
                })
                .close("Cancel")
                .show();
    }

    private void renameSession(final Store.Session s) {
        ask("Rename session", s.label, new Consumer<String>() {
            @Override public void accept(String value) {
                store.renameSession(s.id, value);
                refresh();
            }
        });
    }

    private void confirmDelete(final Store.Session s) {
        Sheet.of(this, "Delete this session?")
                .text("It takes its " + s.points + " sightings with it, and there is "
                    + "no undo.")
                .action("Delete", true, new Runnable() {
                    @Override public void run() {
                        store.deleteSession(s.id);
                        toast("Session deleted");
                        refresh();
                    }
                })
                .close("Cancel")
                .show();
    }

    /** @param byBest rank label reads as the best reading rather than a count */
    private View tallyRow(final Store.Tally t, boolean byBest) {
        LinearLayout row = th.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, th.dp(7), 0, th.dp(7));
        row.setBackground(th.ripple(th.solid(0x00000000, 0)));
        row.setClickable(true);

        TextView badge = th.num(this, t.tech == null ? "?" : t.tech, 9f,
                skin.windowFlat());
        badge.setTypeface(skin.face(true, Typeface.BOLD));
        badge.setPadding(th.dp(6), th.dp(2), th.dp(6), th.dp(2));
        badge.setBackground(th.solid(th.techColour(t.tech), skin.badgeCornerRadius));
        row.addView(badge);

        LinearLayout left = th.column(this);
        left.setPadding(th.dp(8), 0, th.dp(6), 0);
        TextView who = th.text(this,
                t.carrier == null ? "unattributed" : t.carrier, 12, skin.inkPrimary);
        left.addView(who);

        StringBuilder sub = new StringBuilder();
        if (Sampler.has(t.band)) {
            sub.append("NR".equals(t.tech) ? "n" : "B").append(t.band).append("   ");
        }
        if (Sampler.has(t.arfcn)) sub.append('#').append(t.arfcn).append("   ");
        sub.append(t.ident == null ? "" : t.ident);
        TextView detail = th.num(this, sub.toString().trim(), 9, skin.inkSecondary);
        detail.setSingleLine(true);
        left.addView(detail);
        row.addView(left, Theme.weight(1f));

        LinearLayout right = th.column(this);
        TextView best = th.num(this,
                Sampler.has(t.best) ? String.valueOf(t.best) : "--", 14, th.grade(t.best));
        best.setTypeface(skin.face(true, Typeface.BOLD));
        best.setGravity(Gravity.END);
        right.addView(best);

        TextView count = th.num(this,
                byBest ? "best dBm" : t.count + "x", 9, skin.inkSecondary);
        count.setGravity(Gravity.END);
        right.addView(count);
        row.addView(right);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(HistoryActivity.this, CellActivity.class);
                i.putExtra("ident", t.ident);
                startActivity(i);
            }
        });
        return row;
    }
}
