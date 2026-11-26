package com.boresight;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Everything the platform will tell an ordinary app, as plain text.
 *
 * Deliberately unformatted: this is the screen to read when a value elsewhere looks wrong,
 * and the raw dump is the thing to copy into a message about it. Three seconds is slow
 * enough to read a column without it sliding away.
 */
public class DetailsActivity extends Screen {

    private static final long PERIOD_MS = 3000;

    private TelephonyManager tm;
    private TextView dump;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;

    @Override protected String title() { return "FULL DETAIL"; }

    @Override protected void content() {
        tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

        LinearLayout box = pane("Platform report");

        // Long identity lines should not wrap into unreadable porridge, so let the block
        // scroll sideways instead.
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);

        dump = th.num(this, "reading…", 11, skin.inkPrimary);
        dump.setTextIsSelectable(true);
        dump.setLineSpacing(th.dpf(1.5f), 1f);
        dump.setPadding(0, th.dp(4), 0, th.dp(4));
        hs.addView(dump);
        box.addView(hs, Theme.wrap());

        action(box, "Copy all", new Runnable() {
            @Override public void run() {
                ClipboardManager cb =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cb.setPrimaryClip(ClipData.newPlainText("cell detail",
                        dump.getText().toString()));
                toast("Copied");
            }
        });

        hint(box, "Fields the modem reports as unavailable are left out rather than shown "
                + "as the platform's sentinel value, so a missing line means the radio "
                + "did not supply it.");
    }

    @Override protected void onResume() {
        super.onResume();
        running = true;
        tick();
    }

    @Override protected void onPause() {
        super.onPause();
        running = false;
        handler.removeCallbacksAndMessages(null);
    }

    private void tick() {
        if (!running) return;
        try {
            dump.setText(Rf.dump(this, tm));
            status("refreshes every 3 s", "selectable");
        } catch (Throwable t) {
            dump.setText("Could not read:\n\n" + t);
            status("read failed", "");
        }
        handler.postDelayed(new Runnable() {
            @Override public void run() { tick(); }
        }, PERIOD_MS);
    }
}
