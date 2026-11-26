package com.boresight;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The session log: one row per distinct cell seen on this walk.
 *
 * Built for the DAS job rather than for completeness. Filtered to LTE on the band
 * families that actually appear on a distributed antenna system — 700, FirstNet,
 * cellular 850, PCS and AWS — because the macro network's NR and TDD carriers are
 * noise on a commissioning sheet. The filter is a button, not a decision made for you.
 *
 * Reads from {@link SessionLog}, which is fed by whatever is already sampling, so the
 * list keeps filling while this screen is closed.
 */
public class SessionActivity extends Screen {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Sampler sampler;
    private boolean live;

    private TextView header, summary, carrierLine;
    private LinearLayout list;
    private TextView dasButton, sortButton;

    private int sortBy = SessionLog.SORT_BAND;

    @Override protected String title() { return "SESSION"; }

    @Override protected void content() {
        sampler = new Sampler(this);

        LinearLayout head = pane("This session");
        header = kv(head, "Since", "—");
        summary = kv(head, "DAS bands", "—");
        carrierLine = kv(head, "Carriers", "—");

        LinearLayout row = actionRow(head);
        dasButton = smallAction(row, "DAS only", new Runnable() {
            @Override public void run() {
                Prefs.put(SessionActivity.this, Prefs.LOG_DAS_ONLY,
                        !Prefs.bool(SessionActivity.this, Prefs.LOG_DAS_ONLY, true));
                refresh();
            }
        });
        sortButton = smallAction(row, "By band", new Runnable() {
            @Override public void run() {
                sortBy = (sortBy + 1) % 4;
                refresh();
            }
        });
        smallAction(row, "Copy", new Runnable() {
            @Override public void run() { copy(false); }
        });
        smallAction(row, "CSV", new Runnable() {
            @Override public void run() { copy(true); }
        });
        smallAction(row, "Clear", new Runnable() {
            @Override public void run() { confirmClear(); }
        });

        hint(head, "Everything seen since the log was last cleared, one row per cell "
                 + "rather than one per sighting. It keeps filling whether or not this "
                 + "screen is open.");

        LinearLayout box = pane("Cells");
        list = th.column(this);
        box.addView(list, Theme.wrap());
        hint(box, "DAS only keeps LTE on 700, FirstNet, cellular 850, PCS and AWS. "
                + "Turn it off to see everything the modem reports, including NR.");
    }

    @Override protected void onResume() {
        super.onResume();
        live = true;
        tick();
    }

    @Override protected void onPause() {
        super.onPause();
        live = false;
    }

    /** Sample on the same cadence as the main screen so the log keeps growing here. */
    private void tick() {
        if (!live) return;
        try { SessionLog.shared().add(sampler.sample()); } catch (Throwable ignored) { }
        refresh();
        ui.postDelayed(new Runnable() { @Override public void run() { tick(); } }, 2000);
    }

    private boolean dasOnly() { return Prefs.bool(this, Prefs.LOG_DAS_ONLY, true); }

    private void refresh() {
        SessionLog log = SessionLog.shared();
        boolean das = dasOnly();

        header.setText(new SimpleDateFormat("HH:mm", Locale.US)
                .format(new Date(log.startedAt()))
                + "   ·   " + log.dasSize() + " DAS of " + log.size() + " cells");
        summary.setText(log.familySummary());
        List<String> cs = log.carriers(das);
        carrierLine.setText(cs.isEmpty() ? "—" : join(cs));

        dasButton.setText(das ? "DAS only ✓" : "DAS only");
        sortButton.setText(sortName());

        List<SessionLog.Row> rows = log.rows(sortBy, das);
        list.removeAllViews();
        if (rows.isEmpty()) {
            list.addView(th.text(this, das
                    ? "Nothing on a DAS band yet. Turn DAS only off to see what the "
                    + "modem is reporting."
                    : "Nothing logged yet.", 11, skin.inkSecondary));
            status("0 cells", "");
            return;
        }

        int lastFamily = -1;
        for (SessionLog.Row r : rows) {
            if (sortBy == SessionLog.SORT_BAND && r.family() != lastFamily) {
                lastFamily = r.family();
                TextView cap = th.caption(this, Das.name(lastFamily));
                cap.setTextColor(skin.accent(Skin.CH_RSRP));
                cap.setPadding(0, th.dp(10), 0, th.dp(3));
                list.addView(cap);
            }
            list.addView(rowView(r));
        }
        status(rows.size() + (das ? " DAS cells" : " cells"), sortName());
    }

    private static String join(List<String> s) {
        StringBuilder b = new StringBuilder();
        for (String x : s) { if (b.length() > 0) b.append("   ·   "); b.append(x); }
        return b.toString();
    }

    private String sortName() {
        switch (sortBy) {
            case SessionLog.SORT_SIGNAL:  return "By signal";
            case SessionLog.SORT_CARRIER: return "By carrier";
            case SessionLog.SORT_SEEN:    return "By first seen";
            default:                      return "By band";
        }
    }

    /**
     * One cell. Laid out as three lines because that is what fits at arm's length:
     * identity, the frequency pair, then the numbers.
     */
    private View rowView(final SessionLog.Row r) {
        LinearLayout box = th.column(this);
        box.setPadding(th.dp(2), th.dp(7), th.dp(2), th.dp(7));
        box.setBackground(th.ripple(th.solid(0x00000000, 0)));
        box.setClickable(true);

        LinearLayout top = th.row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView band = th.num(this, r.bandLabel(), 15, skin.accent(Skin.CH_RSRP));
        band.setTypeface(skin.face(true, Typeface.BOLD));
        band.setWidth(th.dp(58));
        top.addView(band);

        TextView who = th.num(this, r.carrier.isEmpty() ? "unidentified" : r.carrier,
                12, skin.inkPrimary);
        top.addView(who, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView dbm = th.num(this,
                Sampler.has(r.best) ? String.valueOf(r.best) : "—", 15, grade(r.best));
        dbm.setTypeface(skin.face(true, Typeface.BOLD));
        dbm.setGravity(Gravity.END);
        top.addView(dbm);
        box.addView(top, Theme.wrap());

        TextView freq = th.num(this, "DL/UL  " + r.duplex()
                + "    BW " + r.bandwidth(), 11, skin.accent(Skin.CH_FREQ));
        box.addView(freq);

        StringBuilder d = new StringBuilder();
        d.append("PCI ").append(Sampler.has(r.pci) ? r.pci : "—");
        if (Sampler.has(r.arfcn)) d.append("    EARFCN ").append(r.arfcn);
        if (Sampler.has(r.tac))   d.append("    TAC ").append(r.tac);
        if (r.everServing)        d.append("    served");
        d.append("    ").append(r.sightings).append(" sightings");
        box.addView(th.text(this, d.toString(), 9,
                Skin.alpha(skin.inkSecondary, 0.95f)));

        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { detail(r); }
        });
        return box;
    }

    private int grade(int dbm) {
        if (!Sampler.has(dbm)) return skin.inkSecondary;
        if (dbm >= -85)  return skin.accent(Skin.CH_FREQ);
        if (dbm >= -100) return skin.accent(Skin.CH_SAT);
        return skin.accent(Skin.CH_ALERT);
    }

    private void detail(SessionLog.Row r) {
        StringBuilder b = new StringBuilder();
        b.append("family    ").append(Das.name(r.family())).append('\n');
        b.append("tech      ").append(r.tech).append('\n');
        b.append("carrier   ").append(r.carrier.isEmpty() ? "—" : r.carrier);
        if (!r.mcc.isEmpty()) b.append("  (").append(r.mcc).append('-').append(r.mnc).append(')');
        b.append('\n');
        b.append("band      ").append(r.bandLabel()).append('\n');
        b.append("downlink  ").append(Double.isNaN(r.dlMhz) ? "—" : Rf.mhz(r.dlMhz)).append('\n');
        b.append("uplink    ").append(Double.isNaN(r.ulMhz) ? "—" : Rf.mhz(r.ulMhz)).append('\n');
        b.append("bandwidth ").append(r.bandwidth()).append('\n');
        b.append("EARFCN    ").append(Sampler.has(r.arfcn) ? r.arfcn : "—").append('\n');
        b.append("PCI       ").append(Sampler.has(r.pci) ? r.pci : "—").append('\n');
        b.append("TAC       ").append(Sampler.has(r.tac) ? r.tac : "—").append('\n');
        b.append("best      ").append(Sampler.has(r.best) ? r.best + " dBm" : "—").append('\n');
        b.append("worst     ").append(Sampler.has(r.worst) ? r.worst + " dBm" : "—").append('\n');
        b.append("last      ").append(Sampler.has(r.last) ? r.last + " dBm" : "—").append('\n');
        b.append("sightings ").append(r.sightings).append('\n');
        b.append("first     ").append(time(r.firstSeen)).append('\n');
        b.append("last seen ").append(time(r.lastSeen));
        info(r.bandLabel() + "  ·  PCI " + (Sampler.has(r.pci) ? r.pci : "—"), b.toString());
    }

    private static String time(long ms) {
        return ms <= 0 ? "—" : new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(ms));
    }

    private void copy(boolean csv) {
        SessionLog log = SessionLog.shared();
        String body = csv ? log.csv(dasOnly()) : log.text(dasOnly());
        if (body.trim().isEmpty()) { toast("Nothing to copy"); return; }
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText("session", body));
        toast(csv ? "CSV copied" : "List copied");
    }

    private void confirmClear() {
        Sheet.of(this, "Clear the session log?")
                .text("Starts a new list. The recorded survey in History is not "
                    + "touched — this is only the running list on this screen.")
                .action("Clear", true, new Runnable() {
                    @Override public void run() {
                        SessionLog.shared().clear();
                        refresh();
                    }
                })
                .close("Cancel")
                .show();
    }
}
