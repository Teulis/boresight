package com.boresight;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Getting the survey off this phone and back onto another one.
 *
 * The shape of this screen follows from one fact: a destination that can be written is
 * not guaranteed. Folder grants come from the provider, and a cloud provider may hand
 * over a folder it will not accept writes into, or decline to offer a folder at all.
 * So the screen always shows the fallback — a one-shot save through the system picker,
 * which lists Drive and works everywhere — rather than leaving the user stuck when the
 * tidier route is refused.
 *
 * See {@link Sync} for why this is SAF rather than a Drive SDK.
 */
public class SyncActivity extends Screen {

    private static final int REQ_FOLDER = 71;
    private static final int REQ_SAVE   = 72;
    private static final int REQ_OPEN   = 73;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService pool;

    private TextView destLine, lastLine, countLine, remoteNote, feedLine, heldLine;
    private LinearLayout remoteList;

    @Override protected String title() { return "SYNC"; }

    /**
     * Tick what goes into a bundle, leave the rest in this phone's own survey.
     *
     * Ordered by row count rather than alphabetically, because the ordering is the
     * feature: whatever rode in the car was in range at every point of the drive, so it
     * dominates the count and lands at the top without the person having to know what
     * they are looking for. A list sorted by name would bury it.
     */
    private void chooseShared() {
        java.util.List<Store.Rider> seen = Store.of(this).mostSeen(40);
        final Sheet s = Sheet.of(this, "What gets shared");
        if (seen.isEmpty()) {
            s.text("Nothing named has been recorded yet. Record a drive and the radios "
                 + "will be listed here, busiest first.");
            s.close("Close").show();
            return;
        }
        s.text("Ticked goes into bundles. Unticked stays on this phone. Busiest first — "
             + "whatever travelled with you is at the top, because it was in range at "
             + "every point.");

        java.util.List<String> held = Sync.heldBack(this);
        for (Store.Rider r : seen) {
            boolean on = true;
            for (String h : held) if (h.equalsIgnoreCase(r.name)) { on = false; break; }
            String note = r.rows + (r.rows == 1 ? " row" : " rows")
                    + (r.spanM > 1000
                        ? String.format(java.util.Locale.US, "   ·   %.0f km of road",
                                        r.spanM / 1000)
                        : "");
            final String name = r.name;
            s.pick(r.name, note, on, new Sheet.OnPick() {
                @Override public void picked(boolean on) {
                    Sync.hold(SyncActivity.this, name, !on);
                    paintHeld();
                }
            });
        }
        s.close("Done").show();
    }

    private void paintHeld() {
        if (heldLine == null) return;
        int n = Sync.heldBack(this).size();
        heldLine.setText(n == 0 ? "nothing"
                : n + (n == 1 ? " radio kept local" : " radios kept local"));
    }

    @Override protected void content() {
        pool = Executors.newSingleThreadExecutor();

        // Cloud first, because that is the route that actually reaches Google Drive —
        // see the note below, and Sync's header, for why the folder route does not.
        LinearLayout cloud = pane("Send and retrieve");
        countLine = kv(cloud, "On this phone", "counting…");
        kv(cloud, "This device", Sync.deviceName(this) + "  ·  " + Sync.deviceId(this));

        LinearLayout crow = actionRow(cloud);
        smallAction(crow, "Save to Drive…", new Runnable() {
            @Override public void run() { exportOnce(); }
        });
        smallAction(crow, "Open a bundle…", new Runnable() {
            @Override public void run() { openBundle(); }
        });
        hint(cloud, "Save opens the system save sheet, where Google Drive appears as a "
                 + "destination for each signed-in account — no Google sign-in inside "
                 + "this app and no API key. On the other phone, Open a bundle reads it "
                 + "back from the same place. That is the round trip: the survey leaves "
                 + "this device and arrives on another one.");
        remoteNote = hint(cloud, "Bundles merge rather than replace and are "
                 + "de-duplicated on the way in, so pulling the same file twice changes "
                 + "nothing. Sessions from another phone stay separate from yours and "
                 + "are labelled with the device they came from.");

        // --- incremental ----------------------------------------------------
        //
        // The whole-bundle route above re-sends the entire survey every time, which is
        // fine at thousands of rows and absurd at millions. This one sends only what
        // has happened since last time.
        LinearLayout inc = pane("Incremental sync");
        feedLine = th.text(this, "", 12, skin.inkSecondary);
        inc.addView(feedLine);

        LinearLayout irow = actionRow(inc);
        smallAction(irow, "Send new rows", new Runnable() {
            @Override public void run() { pushFeed(); }
        });
        smallAction(irow, "Prepare old rows", new Runnable() {
            @Override public void run() { adoptOld(); }
        });
        smallAction(irow, "Start over", new Runnable() {
            @Override public void run() { resetFeed(); }
        });
        hint(inc, "Each row carries the device that measured it and that device's own "
                + "counter, so a destination only needs to remember how far it has got. "
                + "Chunks are gzipped newline-delimited JSON: both ends can stream "
                + "them, a failed chunk is simply sent again, and nothing is ever "
                + "rewritten. This goes to the granted folder below.");
        hint(inc, "Rows collected before this phone started stamping identity cannot be "
                + "sent incrementally — there is nothing to count from. Prepare old "
                + "rows gives them one. Do it on one phone only: run it on both and the "
                + "same measurements get published twice under two different names.");

        // --- what goes in the bundle ---------------------------------------
        LinearLayout share = pane("What gets shared");
        heldLine = kv(share, "Held back", "nothing");
        action(share, "Choose what to share", new Runnable() {
            @Override public void run() { chooseShared(); }
        });
        hint(share, "Ranked by how many rows each one has, because whatever rode in the "
                 + "car is at every point of the drive and so is always at the top. "
                 + "Anything unticked stays in this phone's survey and is left out of "
                 + "bundles — your car belongs in your log and in nobody else's. This "
                 + "is remembered, so the next drive starts where you left it.");

        // --- the automatic route, which not every provider offers ----------
        LinearLayout dest = pane("Automatic folder");
        destLine = kv(dest, "Folder", "not set");
        lastLine = kv(dest, "Last push", "never");

        LinearLayout drow = actionRow(dest);
        smallAction(drow, "Choose folder", new Runnable() {
            @Override public void run() { chooseFolder(); }
        });
        smallAction(drow, "Push now", new Runnable() {
            @Override public void run() { push(); }
        });
        smallAction(drow, "List folder", new Runnable() {
            @Override public void run() { listRemote(); }
        });
        smallAction(drow, "Forget", new Runnable() {
            @Override public void run() {
                Sync.forgetFolder(SyncActivity.this);
                refresh();
                toast("Folder forgotten");
            }
        });
        remoteList = th.column(this);
        dest.addView(remoteList, Theme.wrap());

        hint(dest, "A granted folder lets a push write without a save dialog, and lets "
                 + "this screen list what is already there. Which providers offer a "
                 + "folder is up to them, and it is a shorter list than the save sheet: "
                 + "measured on a Samsung device with Drive signed in on four accounts, "
                 + "Drive offers Save and Open but no folder at all. So this is for "
                 + "local or SD storage — including a folder that a desktop sync client "
                 + "watches — while Drive goes through Save above.");

        hint(dest, "Each push writes a new timestamped bundle rather than replacing the "
                 + "last, so a bad export can never destroy a good one. Pruning is left "
                 + "to you.");

        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        if (destLine != null) refresh();
        // The pending count was blank until something else happened to refresh it,
        // which made the pane look broken on arrival.
        refreshFeed();
    }

    private void refresh() {
        paintHeld();
        destLine.setText(Sync.destinationLabel(this));
        lastLine.setText(Sync.fmt(Prefs.get(this).getLong(Prefs.SYNC_LAST, 0)));
        pool.execute(new Runnable() {
            @Override public void run() {
                Store s = Store.of(SyncActivity.this);
                final String txt = Theme.count(s.sessions().size(), "session") + "  ·  "
                        + s.count(Store.KIND_CELL) + " cell  ·  "
                        + s.count(Store.KIND_WIFI) + " wifi  ·  "
                        + Theme.count(s.hitCount(), "detection");
                ui.post(new Runnable() {
                    @Override public void run() { countLine.setText(txt); }
                });
            }
        });
    }

    // ------------------------------------------------------------------ actions

    /**
     * The URI a picker returned, from either place it may put one.
     *
     * getData() is the documented single-select answer, but a provider that has gone
     * into a multi-select mode returns the selection in ClipData even when only one
     * item is chosen and even when EXTRA_ALLOW_MULTIPLE was never asked for.
     */
    private static Uri uriFrom(Intent data) {
        if (data.getData() != null) return data.getData();
        android.content.ClipData clip = data.getClipData();
        if (clip != null && clip.getItemCount() > 0) return clip.getItemAt(0).getUri();
        return null;
    }

    // ------------------------------------------------------------- incremental

    private final Feed.Target folderFeed = new Feed.FolderTarget();

    /** Confirm before doing something that is awkward to undo. */
    private void ask(String title, String body, String go, final Runnable run) {
        Sheet.of(this, title)
                .text(body)
                .action(go, true, run)
                .close("Cancel")
                .show();
    }

    private void refreshFeed() {
        if (feedLine == null) return;
        try {
            feedLine.setText(Feed.describe(this, folderFeed));
        } catch (Throwable t) {
            feedLine.setText("unavailable: " + t.getMessage());
        }
    }

    private void pushFeed() {
        if (!folderFeed.available(this)) {
            info("No folder granted",
                    "Incremental sync writes chunks into the granted folder below. "
                  + "Choose one first.\n\nMeasured on this device, Google Drive offers "
                  + "no folder through the picker, so this wants local storage, an SD "
                  + "card, or a folder a desktop client keeps in step.");
            return;
        }
        status("sending…", "");
        pool.execute(new Runnable() {
            @Override public void run() {
                final Feed.Result r = Feed.push(SyncActivity.this, folderFeed, 0);
                ui.post(new Runnable() {
                    @Override public void run() {
                        refresh();
                        refreshFeed();
                        if (!r.ok) {
                            info("Stopped partway",
                                    (r.chunks > 0
                                        ? r.chunks + " chunk(s) and " + r.rows
                                          + " rows went through before this.\n\n"
                                        : "")
                                  + r.error
                                  + "\n\nNothing was lost: the mark only moves after a "
                                  + "chunk is safely written, so sending again resumes "
                                  + "from exactly where it stopped.");
                            return;
                        }
                        if (r.rows == 0) {
                            info("Nothing to send", "Everything this phone has measured "
                                    + "is already at " + r.where + ".");
                            return;
                        }
                        info("Sent", r.rows + " rows in " + r.chunks + " chunk"
                                + (r.chunks == 1 ? "" : "s") + ", "
                                + (r.bytes / 1024) + " KB gzipped.\n\n"
                                + "Sequence " + r.fromSeq + " to " + r.toSeq + ".");
                    }
                });
            }
        });
    }

    /** Give pre-identity rows an identity so they can take part in incremental sync. */
    private void adoptOld() {
        final long n = Store.of(this).unidentifiedCount();
        if (n == 0) {
            info("Nothing to prepare", "Every row already carries an identity.");
            return;
        }
        ask("Prepare " + n + " old rows?",
                "These were collected before this phone stamped row identity, so they "
              + "cannot be sent incrementally. This gives them one, using this device's "
              + "name.\n\nRun it on one phone only. If both phones adopt rows that came "
              + "from the same original survey, those measurements get published twice "
              + "under two different identities and the merged total will be wrong.",
                "Prepare", new Runnable() {
            @Override public void run() {
                status("preparing…", "");
                pool.execute(new Runnable() {
                    @Override public void run() {
                        int done = 0;
                        // In batches, so a very large backlog does not sit in one
                        // transaction for minutes.
                        while (true) {
                            int n = Store.of(SyncActivity.this).adoptUnidentified(20_000);
                            if (n == 0) break;
                            done += n;
                        }
                        final int total = done;
                        ui.post(new Runnable() {
                            @Override public void run() {
                                refresh();
                                refreshFeed();
                                info("Prepared", total + " rows can now be synced "
                                        + "incrementally.");
                            }
                        });
                    }
                });
            }
        });
    }

    private void resetFeed() {
        ask("Send everything again?",
                "Forgets how far the folder has been brought up to date, so the next "
              + "send starts from the beginning. The destination will receive chunks "
              + "it already has.\n\nUseful when the folder has been emptied or moved.",
                "Forget the mark", new Runnable() {
            @Override public void run() {
                Store.of(SyncActivity.this).clearMark(folderFeed.key());
                refreshFeed();
                toast("Mark cleared");
            }
        });
    }

    private void chooseFolder() {
        try {
            startActivityForResult(Sync.pickFolderIntent(), REQ_FOLDER);
        } catch (Throwable t) {
            info("No picker", "This device has no document picker that offers folders.");
        }
    }

    private void exportOnce() {
        try {
            startActivityForResult(Sync.saveFileIntent(Sync.bundleName(this)), REQ_SAVE);
        } catch (Throwable t) {
            info("No picker", String.valueOf(t.getMessage()));
        }
    }

    private void openBundle() {
        try {
            startActivityForResult(Sync.openFileIntent(), REQ_OPEN);
        } catch (Throwable t) {
            info("No picker", String.valueOf(t.getMessage()));
        }
    }

    private void push() {
        if (Sync.destination(this) == null) {
            info("No folder", "Choose a folder first, or use Export once to save a "
                    + "single bundle wherever you like.");
            return;
        }
        status("pushing…", "");
        pool.execute(new Runnable() {
            @Override public void run() {
                final Sync.Result r = Sync.push(SyncActivity.this, 0);
                ui.post(new Runnable() {
                    @Override public void run() {
                        status("", "");
                        report(r);
                        refresh();
                    }
                });
            }
        });
    }

    private void report(Sync.Result r) {
        if (r.ok) {
            info("Pushed", r.sessions + " sessions, " + r.points + " points and "
                    + r.hits + " detections written to:\n" + r.where
                    + "\n\nOpen that folder on another device and use Retrieve to "
                    + "merge it in.");
        } else {
            info("Not pushed", String.valueOf(r.error));
        }
    }

    private void listRemote() {
        if (Sync.destination(this) == null) { toast("No folder set"); return; }
        remoteList.removeAllViews();
        remoteNote.setText("reading the folder…");
        pool.execute(new Runnable() {
            @Override public void run() {
                final List<String[]> found = Sync.listRemote(SyncActivity.this);
                ui.post(new Runnable() {
                    @Override public void run() {
                        remoteNote.setText(found.isEmpty()
                                ? "No Boresight bundles in that folder yet."
                                : found.size() + " bundle(s) — tap one to merge it in.");
                        for (final String[] f : found) {
                            TextView t = th.text(SyncActivity.this,
                                    f[0] + "\n" + f[2] + "  ·  " + human(f[1]), 11,
                                    skin.inkPrimary);
                            t.setPadding(0, th.dp(7), 0, th.dp(7));
                            t.setBackground(th.ripple(th.solid(0x00000000, 0)));
                            t.setClickable(true);
                            t.setOnClickListener(new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    confirmPull(Uri.parse(f[3]), f[0]);
                                }
                            });
                            remoteList.addView(t);
                        }
                    }
                });
            }
        });
    }

    private static String human(String bytes) {
        try {
            long b = Long.parseLong(bytes);
            if (b < 1024) return b + " B";
            if (b < 1024 * 1024) return (b / 1024) + " KB";
            return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
        } catch (Exception e) { return "?"; }
    }

    /**
     * Look before merging. An import writes to the same database the live survey uses,
     * so the user gets to see what is in the file and where it came from first.
     */
    private void confirmPull(final Uri doc, final String name) {
        status("reading…", "");
        pool.execute(new Runnable() {
            @Override public void run() {
                final Sync.Incoming peek = Sync.pull(SyncActivity.this, doc, true);
                ui.post(new Runnable() {
                    @Override public void run() {
                        status("", "");
                        if (!peek.ok) { info("Could not read", String.valueOf(peek.error)); return; }
                        Sheet.of(SyncActivity.this, "Merge this bundle?")
                                .mono(name + "\n\nfrom " + peek.deviceName
                                        + " (" + peek.device + ")\nwritten "
                                        + Sync.fmt(peek.written) + "\n\n"
                                        + Theme.count(peek.sessions, "session") + ", "
                                        + peek.points + " points, "
                                        + Theme.count(peek.hits, "detection"))
                                .text("Rows already here are skipped, so merging twice "
                                        + "changes nothing.")
                                .action("Merge", true, new Runnable() {
                                    @Override public void run() { doPull(doc); }
                                })
                                .close("Cancel")
                                .show();
                    }
                });
            }
        });
    }

    private void doPull(final Uri doc) {
        status("merging…", "");
        pool.execute(new Runnable() {
            @Override public void run() {
                final Sync.Incoming in = Sync.pull(SyncActivity.this, doc, false);
                ui.post(new Runnable() {
                    @Override public void run() {
                        status("", "");
                        if (!in.ok) { info("Merge failed", String.valueOf(in.error)); return; }
                        info("Merged", "Added " + in.addedSessions + " sessions, "
                                + in.addedPoints + " points and " + in.addedHits
                                + " detections.\n" + in.skipped
                                + " rows were already here and were skipped."
                                + "\n\nThey are on the Map and in History now.");
                        refresh();
                    }
                });
            }
        });
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;

        Uri uri = uriFrom(data);
        if (uri == null) {
            // Never fail silently here. The previous version returned without a word
            // when getData() was null, and a real picker does exactly that: Drive's
            // browser puts the file in ClipData once it has entered its "1 selected"
            // mode, so pressing Select returned a perfectly good result that this
            // screen dropped on the floor with no explanation at all.
            info("Nothing came back",
                    "The picker returned without a file this app could read. If you "
                  + "selected more than one, pick a single file instead.");
            return;
        }
        switch (req) {
            case REQ_FOLDER:
                if (Sync.rememberFolder(this, uri)) {
                    refresh();
                    toast("Folder set");
                } else {
                    info("Not granted", "That provider would not give a lasting grant "
                            + "for the folder. Use Save to Drive above instead — the "
                            + "save sheet accepts providers that decline to hand over "
                            + "a folder, Drive among them.");
                }
                break;
            case REQ_SAVE: {
                status("writing…", "");
                final Uri doc = uri;
                pool.execute(new Runnable() {
                    @Override public void run() {
                        final Sync.Result r = Sync.writeTo(SyncActivity.this, doc, 0);
                        ui.post(new Runnable() {
                            @Override public void run() { status("", ""); report(r); }
                        });
                    }
                });
                break;
            }
            case REQ_OPEN:
                confirmPull(uri, Sync.displayName(this, uri));
                break;
        }
    }
}
