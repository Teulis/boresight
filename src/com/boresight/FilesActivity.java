package com.boresight;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Environment;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A file browser that is honest about what it cannot reach.
 *
 * Android's storage rules changed twice and left a confusing picture: an app can read
 * shared storage, can reach other apps' media folders, cannot reach their external data
 * folders since Android 11, and can never reach their private data at all. Rather than
 * silently omitting what is blocked, locked directories are listed and greyed with the
 * reason, because "it is not there" and "you are not allowed" are very different answers
 * when you are hunting for a save file.
 *
 * Listing goes through {@link Shell} when the direct file API refuses, so the day a
 * privileged backend exists the blocked tier opens with no change here.
 */
public class FilesActivity extends Screen {

    private static final int MODE_LIST = 0, MODE_TEXT = 1, MODE_HEX = 2, MODE_DB = 3;
    private static final long VIEW_LIMIT = 512 * 1024;

    private int mode = MODE_LIST;
    private File cwd;
    private File viewing;

    private TextView pathLine, statusNote, tierNote;
    private TextView saveButton, patchButton, sqlButton, findButton;
    private LinearLayout listCol, viewerBox;
    private ScrollView listScroll, viewerScroll;
    private EditText viewerText;
    private EditText filter;
    private List<Entry> all = new ArrayList<>();
    private boolean loading;

    /** How many rows are built at once. See paint(). */
    private static final int ROW_CAP = 400;
    private File pendingDb;
    private File clipFile;
    private boolean clipCut;

    @Override protected String title() { return "FILES"; }

    @Override protected void content() {
        LinearLayout nav = pane("Location");
        pathLine = th.num(this, "/", 11, skin.accent(Skin.CH_RSRP));
        pathLine.setTextIsSelectable(true);
        nav.addView(pathLine);

        statusNote = th.text(this, "", 10, skin.accent(Skin.CH_ALERT));
        statusNote.setPadding(0, th.dp(4), 0, 0);
        nav.addView(statusNote);

        // Lives in the Location pane, not in the list. A filter field inside the view
        // being rebuilt on every keystroke loses focus and drops characters — the
        // drawer's search shipped that way once and typed "caea" for "camera".
        filter = new EditText(this);
        filter.setHint("Filter this folder");
        filter.setSingleLine(true);
        filter.setTextColor(skin.inkPrimary);
        filter.setHintTextColor(skin.inkSecondary);
        filter.setBackground(skin.tileBg(th));
        filter.setPadding(th.dp(12), th.dp(9), th.dp(12), th.dp(9));
        filter.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        filter.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                if (!loading && mode == MODE_LIST) refilter();
            }
        });
        LinearLayout.LayoutParams flp = Theme.wrap();
        flp.setMargins(0, th.dp(8), 0, 0);
        nav.addView(filter, flp);

        FlowLayout jumps = new FlowLayout(this).gap(th.dp(5));
        LinearLayout.LayoutParams jlp = Theme.wrap();
        jlp.setMargins(0, th.dp(8), 0, 0);
        jumps.setLayoutParams(jlp);
        jump(jumps, "up", null);
        jump(jumps, "sdcard", Environment.getExternalStorageDirectory().getAbsolutePath());
        jump(jumps, "Download", new File(Environment.getExternalStorageDirectory(),
                "Download").getAbsolutePath());
        jump(jumps, "Android/data", "/sdcard/Android/data");
        jump(jumps, "Android/media", "/sdcard/Android/media");
        jump(jumps, "my files", getFilesDir().getAbsolutePath());
        jump(jumps, "my external", String.valueOf(getExternalFilesDir(null)));
        jump(jumps, "/data/data", "/data/data");
        jump(jumps, "/system", "/system");
        jump(jumps, "/proc", "/proc");
        jump(jumps, "/sys", "/sys");
        nav.addView(jumps);

        LinearLayout perm = actionRow(nav);
        smallAction(perm, "Go to path", new Runnable() {
            @Override public void run() { goToPath(); }
        });
        smallAction(perm, "All files access", new Runnable() {
            @Override public void run() { requestAllFiles(); }
        });
        smallAction(perm, "Refresh", new Runnable() {
            @Override public void run() { open(cwd); }
        });
        smallAction(perm, "New", new Runnable() {
            @Override public void run() { create(); }
        });
        smallAction(perm, "Paste", new Runnable() {
            @Override public void run() { paste(); }
        });

        LinearLayout body2 = pane("Contents");
        // Wrap, not a fixed height.
        //
        // This was a 520dp ScrollView sitting inside the page's own ScrollView. Two
        // scrollers in the same direction do not share: the outer one intercepts every
        // vertical drag, so the inner never scrolled and everything past the first
        // screenful of a folder was simply unreachable — 84 entries listed, about
        // fourteen you could actually see. Wrapping makes the inner view a passthrough
        // and leaves one scrolling surface, which is the page.
        listScroll = new ScrollView(this);
        LinearLayout.LayoutParams slp = Theme.wrap();
        listCol = th.column(this);
        listScroll.addView(listCol);
        body2.addView(listScroll, slp);

        viewerBox = th.column(this);
        viewerBox.setVisibility(View.GONE);
        viewerScroll = new ScrollView(this);
        // Same reason as the listing above: a fixed-height box nested in the page's
        // scroller cannot be scrolled, so a long file stopped at 520dp.
        LinearLayout.LayoutParams vlp = Theme.wrap();
        // An EditText rather than a label: text mode is meant to be edited, and the
        // save action was previously writing back something that could never change.
        viewerText = new EditText(this);
        viewerText.setTextColor(skin.inkPrimary);
        viewerText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        viewerText.setTypeface(Typeface.MONOSPACE);
        viewerText.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        viewerText.setBackground(null);
        viewerText.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        viewerScroll.addView(viewerText);
        viewerBox.addView(viewerScroll, vlp);

        LinearLayout vrow = actionRow(viewerBox);
        smallAction(vrow, "Back", new Runnable() {
            @Override public void run() { mode = MODE_LIST; showList(); }
        });
        smallAction(vrow, "Copy", new Runnable() {
            @Override public void run() {
                android.content.ClipboardManager cb = (android.content.ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                cb.setPrimaryClip(android.content.ClipData.newPlainText(
                        "file", viewerText.getText().toString()));
                toast("Copied");
            }
        });
        // Each of these applies to exactly one view mode. They used to be shown in all
        // three, which offered Run SQL on a text file and Patch bytes on a hex dump of
        // a database — three buttons of which two were always wrong.
        saveButton = smallAction(vrow, "Save", new Runnable() {
            @Override public void run() { saveEdited(); }
        });
        findButton = smallAction(vrow, "Find", new Runnable() {
            @Override public void run() { find(); }
        });
        patchButton = smallAction(vrow, "Patch bytes", new Runnable() {
            @Override public void run() { patchBytes(0); }
        });
        sqlButton = smallAction(vrow, "Run SQL", new Runnable() {
            @Override public void run() { runSql(); }
        });
        body2.addView(viewerBox, Theme.wrap());

        tierNote = hint(body2, "");
        paintTierNote();

        open(Environment.getExternalStorageDirectory());
    }

    @Override protected void onResume() {
        super.onResume();
        // Permission can be granted while we are away, which changes what this screen
        // can reach and therefore what the footer should claim.
        if (tierNote != null) { paintTierNote(); if (cwd != null) open(cwd); }
    }

    private void paintTierNote() {
        boolean priv = Shell.get(this).privileged();
        tierNote.setText(priv
                ? "Running as the shell uid, so other apps' Android/data folders are "
                + "open — that is where most apps keep their saves. /data/data stays "
                + "closed: shell is refused there too, so it needs root, not Shizuku."
                : "Greyed entries exist but are closed to this app. Android/data "
                + "belonging to other apps is closed to apps but open to shell, so a "
                + "privileged backend opens exactly that tier. /data/data is closed to "
                + "everything short of root.");
    }

    private void jump(FlowLayout into, final String label, final String path) {
        TextView t = th.num(this, label, 10, skin.accent(Skin.CH_RSRP));
        t.setTypeface(skin.face(true, Typeface.BOLD));
        t.setPadding(th.dp(9), th.dp(5), th.dp(9), th.dp(5));
        t.setBackground(th.ripple(
                skin.badgeBg(th, Skin.alpha(skin.accent(Skin.CH_RSRP), 0.10f))));
        t.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, th.dp(4), 0);
        t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (path == null) {
                    File parent = cwd == null ? null : cwd.getParentFile();
                    if (parent != null) open(parent); else toast("Already at the root");
                } else {
                    open(new File(path));
                }
            }
        });
        into.addView(t);
    }

    @Override public void onBackPressed() {
        if (mode != MODE_LIST) { mode = MODE_LIST; showList(); return; }
        if (cwd != null && cwd.getParentFile() != null
                && !cwd.getAbsolutePath().equals("/")) {
            open(cwd.getParentFile());
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ listing

    private static final class Entry {
        String name;
        boolean dir, readable;
        long size, modified;
        String note;
    }

    private void open(File dir) {
        if (dir == null) return;
        cwd = dir;
        mode = MODE_LIST;
        pathLine.setText(dir.getAbsolutePath());
        showList();

        // A filter from the last folder would greet the new one with "nothing matches".
        // Suppressed while it is cleared, because the watcher would refilter against a
        // listing that has not been read yet.
        if (filter != null && filter.getText().length() > 0) {
            boolean was = loading;
            loading = true;
            filter.setText("");
            loading = was;
        }

        // Vfs owns the direct-then-backend dance now, including the empty-array trap
        // under Android/data. This screen just asks for the listing.
        List<Entry> entries = new ArrayList<>();
        for (Vfs.Item i : Vfs.list(this, dir)) {
            Entry e = new Entry();
            e.name = i.name;
            e.dir = i.dir;
            e.size = i.size;
            e.modified = i.modified;
            e.readable = i.readable || Shell.get(this).privileged();
            if (!e.readable) e.note = reasonFor(new File(dir, i.name).getAbsolutePath());
            entries.add(e);
        }
        all = entries;
        String blocked = entries.isEmpty() ? reasonFor(dir.getAbsolutePath()) : null;

        statusNote.setText(blocked == null ? "" : blocked);
        paint(shown(), blocked);
        status(Theme.count(entries.size(), "entry", "entries"), dir.getAbsolutePath());
    }

    /** The current directory filtered by the search box. */
    private List<Entry> shown() {
        String q = filter == null ? "" : filter.getText().toString().trim().toLowerCase(Locale.US);
        if (q.isEmpty()) return all;
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) {
            if (e.name.toLowerCase(Locale.US).contains(q)) { out.add(e); continue; }
            // Searching a folder of package names by the app's name is the whole point
            // of showing the app's name beside it.
            String label = appLabel(e);
            if (label != null && label.toLowerCase(Locale.US).contains(q)) out.add(e);
        }
        return out;
    }

    private void refilter() {
        List<Entry> list = shown();
        paint(list, list.isEmpty() && !all.isEmpty() ? null : null);
        if (list.isEmpty() && !all.isEmpty()) {
            listCol.removeAllViews();
            listCol.addView(th.text(this, "Nothing here matches \""
                    + filter.getText().toString().trim() + "\".", 11, skin.inkSecondary));
        }
        status(all.size() == list.size()
                    ? Theme.count(all.size(), "entry", "entries")
                    : list.size() + " of " + all.size(),
                cwd.getAbsolutePath());
    }

    /**
     * Why a path is closed. These are the rules rather than a guess, and they are
     * spelled out so a locked folder is not mistaken for an empty one.
     */
    private String reasonFor(String path) {
        if (path.startsWith("/data/data") || path.startsWith("/data/user")) {
            return "private app data — mode 0700 with per-app SELinux labels. Closed to "
                 + "every app, and to shell and Shizuku too. Root only.";
        }
        // Match the folder itself as well as anything inside it: landing on
        // /sdcard/Android/data is the common case and deserves the real explanation
        // rather than the generic one.
        if (path.matches("(?i)^/(sdcard|storage/emulated/0)/Android/(data|obb)(/.*)?$")) {
            return "Android/data is closed to apps from Android 11 onward, and All "
                 + "files access does not cover it — Google excluded it deliberately. "
                 + "Shell can read it, so this is the tier a privileged backend would "
                 + "open. Many games and apps keep their saves here.";
        }
        if (path.startsWith("/data")) {
            return "system data partition — not readable by apps.";
        }
        if (path.startsWith("/proc/")) {
            return "another process — only our own entries are visible.";
        }
        if (!hasAllFiles() && path.startsWith("/storage")) {
            return "shared storage without All files access. Grant it above for the "
                 + "whole volume.";
        }
        return "not readable by this app.";
    }

    /**
     * What the fallback can actually do on this phone, right now.
     *
     * The explanations above describe Android's rules and then say a privileged backend
     * would open this tier — without ever saying whether this phone has one running. So
     * the fallback looked arbitrary: it opens /system and /proc and refuses Android/data,
     * and the reason is not the directory at all. It is the uid. With no backend started
     * the fallback shells out as this app's own uid, which can read the first two and
     * not the third, and nothing on screen said so.
     */
    private String backendNote() {
        switch (Backend.state(this)) {
            case Backend.READY:
                return "The privileged backend is running, so this was refused to the "
                     + "shell uid as well — root would be needed, and this app has none.";
            case Backend.NOT_RUNNING:
                return "The privileged backend is installed but its service is not "
                     + "started, so the fallback is still running as this app's own uid. "
                     + "That is why it opens /system and /proc and not this. Start "
                     + "Shizuku, then Try again.";
            case Backend.NOT_GRANTED:
                return "The privileged backend is running but has not granted this app "
                     + "access yet.";
            default:
                return "There is no privileged backend installed, so the fallback runs "
                     + "as this app's own uid. It opens /system, /proc and /sys, and "
                     + "cannot open this.";
        }
    }

    private void paint(List<Entry> entries, String blocked) {
        listCol.removeAllViews();
        if (entries.isEmpty()) {
            TextView t = th.text(this, blocked == null
                    ? "Empty." : "Cannot list this directory.\n\n" + blocked,
                    11, blocked == null ? skin.inkSecondary : skin.accent(Skin.CH_ALERT));
            t.setPadding(0, th.dp(8), 0, th.dp(8));
            listCol.addView(t);
            if (blocked != null) {
                listCol.addView(th.text(this, backendNote(), 11, skin.inkSecondary));
                if (Backend.state(this) != Backend.READY) {
                    LinearLayout row = actionRow(listCol);
                    smallAction(row, "Privileged backend", new Runnable() {
                        @Override public void run() {
                            Backend.show(FilesActivity.this, 0, new Runnable() {
                                @Override public void run() { Vfs.reset(); open(cwd); }
                            });
                        }
                    });
                    smallAction(row, "Try again", new Runnable() {
                        @Override public void run() { Vfs.reset(); open(cwd); }
                    });
                }
            }
            return;
        }
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        int drawn = 0;
        for (final Entry e : entries) {
            // Every row is a real view in one page-long column, so a directory with
            // thousands of entries would build thousands of them before the screen drew
            // anything. The filter above reaches the rest.
            if (drawn++ >= ROW_CAP) {
                listCol.addView(th.text(this,
                        "Showing the first " + ROW_CAP + " of " + entries.size()
                      + ". Use the filter to reach the rest.",
                        11, skin.accent(Skin.CH_ALERT)));
                break;
            }
            // One horizontal row: icon, then name over details, then the size on the
            // right where it lines up down the column instead of hiding mid-sentence.
            LinearLayout row = th.row(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(th.dp(2), th.dp(7), th.dp(2), th.dp(7));
            row.setBackground(th.ripple(th.solid(0x00000000, 0)));
            row.setClickable(true);

            android.graphics.drawable.Drawable icon = appIcon(e);
            if (icon != null) {
                android.widget.ImageView iv = new android.widget.ImageView(this);
                iv.setImageDrawable(icon);
                LinearLayout.LayoutParams ilp =
                        new LinearLayout.LayoutParams(th.dp(26), th.dp(26));
                ilp.setMargins(0, 0, th.dp(9), 0);
                row.addView(iv, ilp);
            }

            LinearLayout text = th.column(this);
            String label = appLabel(e);
            TextView name = th.num(this,
                    label != null ? label : (e.dir ? "[ " + e.name + " ]" : e.name), 11,
                    e.readable ? (e.dir ? skin.accent(Skin.CH_RSRP) : skin.inkPrimary)
                               : th.inkFaint);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            name.setTypeface(skin.face(true, e.dir ? Typeface.BOLD : Typeface.NORMAL));
            text.addView(name);

            StringBuilder sub = new StringBuilder();
            // With the app's name on the first line, the package belongs on the second:
            // it is what the folder is actually called.
            if (label != null) sub.append(e.name).append("   ");
            if (e.modified > 0) sub.append(f.format(new Date(e.modified)));
            if (!e.readable && e.note != null) {
                sub.setLength(0);
                sub.append(e.note);
            }
            if (sub.length() > 0) {
                TextView d = th.text(this, sub.toString(), 9,
                        e.readable ? Skin.alpha(skin.inkSecondary, 0.85f)
                                   : skin.accent(Skin.CH_ALERT));
                d.setSingleLine(!e.readable ? false : true);
                d.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                text.addView(d);
            }
            row.addView(text, Theme.weight(1f));

            if (!e.dir) {
                TextView sz = th.num(this, human(e.size), 10,
                        Skin.alpha(skin.inkSecondary, 0.9f));
                sz.setGravity(android.view.Gravity.END);
                sz.setPadding(th.dp(8), 0, 0, 0);
                row.addView(sz);
            }

            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    File target = new File(cwd, e.name);
                    if (e.dir) open(target);
                    else actions(target, e);
                }
            });
            listCol.addView(row);
        }
    }

    // --------------------------------------------------------------- app folders

    /**
     * Under Android/data, Android/obb and /data/data a folder's name is a package name,
     * which tells you nothing at a glance — "com.bhvr.deadbydaylightmobile" is a folder
     * among three hundred. The installed app's own icon and name do tell you, and the
     * platform already has both.
     *
     * Cached including the misses: most of these directories hold a few hundred entries
     * and an uninstalled leftover would otherwise throw on every repaint.
     */
    private final java.util.Map<String, android.graphics.drawable.Drawable> iconCache =
            new java.util.HashMap<>();
    private final java.util.Map<String, String> labelCache = new java.util.HashMap<>();

    private boolean isPackageFolder(Entry e) {
        if (!e.dir) return false;
        String p = cwd.getAbsolutePath();
        boolean appDir = p.matches("(?i)^/(sdcard|storage/emulated/[0-9]+)/Android/(data|obb|media)$")
                || p.equals("/data/data") || p.matches("^/data/user/[0-9]+$");
        // A package name always has a dot and never a space.
        return appDir && e.name.indexOf('.') > 0 && e.name.indexOf(' ') < 0;
    }

    private android.graphics.drawable.Drawable appIcon(Entry e) {
        if (!isPackageFolder(e)) return null;
        if (iconCache.containsKey(e.name)) return iconCache.get(e.name);
        android.graphics.drawable.Drawable d = null;
        try {
            d = getPackageManager().getApplicationIcon(e.name);
        } catch (Throwable ignored) { }
        iconCache.put(e.name, d);
        return d;
    }

    private String appLabel(Entry e) {
        if (!isPackageFolder(e)) return null;
        if (labelCache.containsKey(e.name)) return labelCache.get(e.name);
        String s = null;
        try {
            CharSequence cs = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(e.name, 0));
            if (cs != null && cs.length() > 0) s = cs.toString();
        } catch (Throwable ignored) { }
        labelCache.put(e.name, s);
        return s;
    }

    // ------------------------------------------------------------------ actions

    /** Open a file we arrived at by path rather than by tapping a listing. */
    private void openFile(File f) {
        Entry e = new Entry();
        e.name = f.getName();
        e.readable = true;
        actions(f, e);
    }

    private void actions(final File f, final Entry e) {
        // A privileged backend may well read what the listing said we could not, so the
        // flag stops being a reason to refuse outright.
        if (!e.readable && !Shell.get(this).privileged()) {
            info("Closed", e.note == null ? reasonFor(f.getAbsolutePath()) : e.note);
            return;
        }
        final String[] items = {
                "Inspect", "View as text", "View as JSON", "View as hex",
                "Open as database", "Copy into my sandbox", "Details",
                "Rename", "Copy", "Cut", "Delete",
        };
        Sheet s = Sheet.of(this, f.getName());
        for (int i = 0; i < items.length; i++) {
            final int w = i;
            s.choice(items[i], null, false, new Runnable() {
                @Override public void run() {
                    switch (w) {
                        case 0: inspect(f); break;
                        case 1: viewText(f); break;
                        case 2: viewJson(f); break;
                        case 3: viewHex(f); break;
                        case 4: viewDb(f); break;
                        case 5: copyIn(f); break;
                        case 6: details(f); break;
                        case 7: rename(f); break;
                        case 8: clipFile = f; clipCut = false; toast("Copied: " + f.getName()); break;
                        case 9: clipFile = f; clipCut = true;  toast("Cut: " + f.getName()); break;
                        case 10: delete(f); break;
                    }
                }
            });
        }
        s.close("Cancel").show();
    }

    private void showList() {
        listScroll.setVisibility(View.VISIBLE);
        viewerBox.setVisibility(View.GONE);
        // The viewer leaves the file's name in the location line; coming back to the
        // listing without restoring it says we are somewhere we are not.
        if (cwd != null) pathLine.setText(cwd.getAbsolutePath());
    }

    private void showViewer(String title, String body) {
        pathLine.setText(title);
        viewerText.setText(body);
        boolean editable = mode == MODE_TEXT && viewing != null && writable(viewing);
        // setTextIsSelectable has a side effect that is easy to miss: it rewrites
        // focusable, focusableInTouchMode, clickable and longClickable. Called after
        // the flags below it silently undid them, and the editor could never take
        // focus — the field looked editable and refused every keystroke. It goes first.
        viewerText.setTextIsSelectable(!editable);
        viewerText.setFocusable(editable);
        viewerText.setFocusableInTouchMode(editable);
        viewerText.setClickable(editable);
        viewerText.setCursorVisible(editable);
        // Always a multi-line text input type, even when read only.
        // InputType.TYPE_NULL collapses an EditText to a single line, which rendered
        // the entire hex dump as one row running off the side of the screen — the
        // offsets were there, just unreadable. Read-only is expressed by the
        // focusable and cursor flags above, not by the input type.
        viewerText.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        viewerText.setSingleLine(false);
        viewerText.setHorizontallyScrolling(mode == MODE_HEX);
        viewerText.setMaxLines(Integer.MAX_VALUE);
        if (!editable) viewerText.setKeyListener(null);
        else viewerText.setKeyListener(android.text.method.TextKeyListener.getInstance());

        // Only the action that belongs to this mode.
        if (saveButton != null)  saveButton.setVisibility(editable ? View.VISIBLE : View.GONE);
        if (patchButton != null) patchButton.setVisibility(mode == MODE_HEX ? View.VISIBLE : View.GONE);
        if (findButton != null)  findButton.setVisibility(mode == MODE_HEX ? View.VISIBLE : View.GONE);
        if (sqlButton != null)   sqlButton.setVisibility(mode == MODE_DB ? View.VISIBLE : View.GONE);
        listScroll.setVisibility(View.GONE);
        viewerBox.setVisibility(View.VISIBLE);
        viewerScroll.scrollTo(0, 0);
    }

    private void viewText(File f) {
        mode = MODE_TEXT;
        try {
            byte[] buf = Vfs.bytes(this, f, (int) VIEW_LIMIT);
            String s = new String(buf, "UTF-8");
            if (sizeOf(f) > VIEW_LIMIT) {
                s += "\n\n-- truncated at " + (VIEW_LIMIT / 1024) + " KB --";
            }
            viewing = f;
            showViewer(f.getAbsolutePath()
                    + (writable(f) ? "   (editable)" : "   (read only)"), s);
        } catch (Exception ex) {
            info("Could not read", String.valueOf(ex.getMessage()));
        }
    }

    /**
     * Work out what the file is, unwrap it, and show whatever is underneath.
     *
     * A save is usually a stack rather than a format — JSON that was gzipped and then
     * base64'd, NBT inside gzip — and a text or hex view only ever shows the outermost
     * layer, which is why so much of a phone's data looks like noise when none of it
     * is. This peels the wrappers and renders the innermost thing in whatever way suits
     * it, with the chain printed at the top so you can see what the file actually is.
     *
     * When what is left is still unrecognisable, it says so and shows the hex. That is
     * the honest answer for an encrypted save: the wrappers came off, the contents did
     * not, and no amount of viewing will change it.
     */
    private void inspect(File f) {
        mode = MODE_TEXT;
        try {
            byte[] buf = Vfs.bytes(this, f, (int) VIEW_LIMIT);
            if (buf == null || buf.length == 0) {
                info("Nothing to inspect", "The file read back empty.");
                return;
            }
            java.util.List<Blob.Layer> layers = Blob.peel(buf, (int) VIEW_LIMIT);
            Blob.Layer last = layers.get(layers.size() - 1);

            StringBuilder b = new StringBuilder();
            b.append(Blob.chain(layers)).append('\n');
            b.append(human(sizeOf(f))).append(" on disk");
            if (layers.size() > 1) {
                b.append("   ->   ").append(human(last.bytes.length)).append(" unwrapped");
            }
            b.append("\n\n");

            String kind = last.kind;
            if (Blob.NBT.equals(kind)) {
                String tree = Nbt.tree(last.bytes);
                b.append(tree != null ? tree : hexOf(last.bytes));
            } else if (Blob.JSON.equals(kind)) {
                String raw = new String(last.bytes, "UTF-8");
                b.append(Json.describe(raw)).append("\n\n").append(Json.pretty(raw));
            } else if (Blob.TEXT.equals(kind) || Blob.XML.equals(kind)) {
                b.append(new String(last.bytes, "UTF-8"));
            } else if (Blob.SQLITE.equals(kind)) {
                b.append("A SQLite database. Open it with \"Open as database\" to run "
                       + "queries against it.");
            } else {
                b.append("Nothing recognisable under the wrappers — most likely "
                       + "encrypted, or a format with no header. The bytes:\n\n")
                 .append(hexOf(last.bytes));
            }

            viewing = null;          // a decoded view does not map back to the bytes
            showViewer(f.getAbsolutePath() + "   (" + kind + ", read only)", b.toString());
        } catch (Exception ex) {
            info("Could not read", String.valueOf(ex.getMessage()));
        }
    }

    /** Offset, hex, printable — shared by the hex view and by inspect. */
    private String hexOf(byte[] buf) {
        StringBuilder out = new StringBuilder();
        int limit = Math.min(buf.length, 64 * 1024);
        for (int i = 0; i < limit; i += 16) {
            out.append(String.format(Locale.US, "%08X  ", i));
            StringBuilder ascii = new StringBuilder();
            for (int j = 0; j < 16; j++) {
                if (i + j < limit) {
                    int v = buf[i + j] & 0xFF;
                    out.append(String.format(Locale.US, "%02X ", v));
                    ascii.append(v >= 32 && v < 127 ? (char) v : '.');
                } else {
                    out.append("   ");
                }
                if (j == 7) out.append(' ');
            }
            out.append(" |").append(ascii).append("|\n");
        }
        if (buf.length > limit) {
            out.append("\n-- ").append(human(buf.length - limit)).append(" more --");
        }
        return out.toString();
    }

    /**
     * A JSON file laid out, which is how most game saves and config blobs arrive.
     *
     * Read only, deliberately. The text viewer edits the bytes that are on disk; this
     * one shows a reformatted copy, and writing that back would rewrite every line of
     * the file to change one value. Edit from "View as text" when you mean to save.
     */
    private void viewJson(File f) {
        mode = MODE_TEXT;
        try {
            byte[] buf = Vfs.bytes(this, f, (int) VIEW_LIMIT);
            String raw = new String(buf, "UTF-8");
            if (!Json.looksLike(raw)) {
                info("Not JSON", "This does not start with { or [, so there is nothing "
                        + "to lay out. Open it as text or as hex instead.");
                return;
            }
            String body = Json.describe(raw) + "\n\n" + Json.pretty(raw);
            if (sizeOf(f) > VIEW_LIMIT) {
                body += "\n\n-- truncated at " + (VIEW_LIMIT / 1024) + " KB --";
            }
            viewing = null;          // read only: nothing here maps back to the bytes
            showViewer(f.getAbsolutePath() + "   (JSON, read only)", body);
        } catch (Exception ex) {
            info("Could not read", String.valueOf(ex.getMessage()));
        }
    }

    /** Classic offset, hex, printable layout. */
    private void viewHex(File f) {
        mode = MODE_HEX;
        try {
            byte[] buf = Vfs.bytes(this, f, 64 * 1024);

            StringBuilder out = new StringBuilder();
            for (int i = 0; i < buf.length; i += 16) {
                out.append(String.format(Locale.US, "%08X  ", i));
                StringBuilder ascii = new StringBuilder();
                for (int j = 0; j < 16; j++) {
                    if (i + j < buf.length) {
                        int b = buf[i + j] & 0xFF;
                        out.append(String.format(Locale.US, "%02X ", b));
                        ascii.append(b >= 32 && b < 127 ? (char) b : '.');
                    } else {
                        out.append("   ");
                    }
                    if (j == 7) out.append(' ');
                }
                out.append(' ').append(ascii).append('\n');
            }
            if (sizeOf(f) > 64 * 1024) out.append("-- truncated at 64 KB --\n");
            viewing = f;
            showViewer(f.getName() + "  (hex)", out.toString());
        } catch (Exception ex) {
            info("Could not read", String.valueOf(ex.getMessage()));
        }
    }

    /**
     * Open a SQLite file through the framework rather than a binary, since Android
     * ships no sqlite3. Copied into our own cache first: the original may be in use,
     * may have a sidecar write-ahead log, and must not be modified by accident.
     */
    private void viewDb(File f) {
        mode = MODE_DB;
        SQLiteDatabase db = null;
        try {
            File tmp = new File(getCacheDir(), "peek.db");
            pull(f, tmp);
            pendingDb = f;
            db = SQLiteDatabase.openDatabase(tmp.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);

            StringBuilder out = new StringBuilder();
            out.append(f.getName()).append('\n');
            out.append("sqlite ").append(db.getVersion()).append("\n\n");

            Cursor t = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null);
            List<String> tables = new ArrayList<>();
            try { while (t.moveToNext()) tables.add(t.getString(0)); } finally { t.close(); }

            if (tables.isEmpty()) out.append("no tables\n");
            for (String name : tables) {
                out.append("=== ").append(name).append(" ===\n");
                Cursor c = null;
                try {
                    c = db.rawQuery("SELECT * FROM " + quoteIdent(name) + " LIMIT 40", null);
                    int cols = c.getColumnCount();
                    for (int i = 0; i < cols; i++) {
                        out.append(c.getColumnName(i)).append(i < cols - 1 ? " | " : "\n");
                    }
                    int rows = 0;
                    while (c.moveToNext()) {
                        for (int i = 0; i < cols; i++) {
                            String v;
                            try { v = c.getString(i); } catch (Exception e) { v = "<blob>"; }
                            out.append(v == null ? "null" : v).append(i < cols - 1 ? " | " : "\n");
                        }
                        rows++;
                    }
                    out.append("(").append(rows).append(rows == 40 ? "+ rows)\n\n" : " rows)\n\n");
                } catch (Exception e) {
                    out.append("could not read: ").append(e.getMessage()).append("\n\n");
                } finally {
                    if (c != null) c.close();
                }
            }
            viewing = f;
            showViewer(f.getName() + "  (database)"
                    + (writable(f) ? "   writable" : "   read only"), out.toString());
        } catch (Exception ex) {
            info("Not a database",
                    "Could not open it as SQLite.\n\n" + ex.getMessage());
        } finally {
            if (db != null) try { db.close(); } catch (Exception ignored) {}
        }
    }

    private void copyIn(File f) {
        try {
            File dir = getExternalFilesDir("pulled");
            if (dir == null) dir = new File(getFilesDir(), "pulled");
            if (!dir.isDirectory()) dir.mkdirs();
            File dest = new File(dir, f.getName());
            pull(f, dest);
            info("Copied", dest.getAbsolutePath()
                    + "\n\nThis folder is reachable over adb, so the file can be pulled "
                    + "off the device from there.");
        } catch (Exception e) {
            info("Could not copy", String.valueOf(e.getMessage()));
        }
    }

    private void details(File f) {
        StringBuilder b = new StringBuilder();
        b.append("path      ").append(f.getAbsolutePath()).append('\n');
        long realSize = sizeOf(f);
        b.append("size      ").append(realSize).append(" bytes  (")
         .append(human(realSize)).append(")\n");
        b.append("modified  ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                Locale.US).format(new Date(f.lastModified()))).append('\n');
        b.append("readable  ").append(f.canRead()).append('\n');
        // Two different answers worth separating: what this process can write, and what
        // the app can arrange to write. They differ for exactly the files that matter.
        b.append("writable  ").append(f.canWrite() ? "yes"
                : Shell.get(this).privileged() ? "not directly, but yes via the backend"
                : "no").append('\n');
        b.append("hidden    ").append(f.isHidden()).append('\n');
        Shell.Result r = Shell.get(this).exec("ls -la " + quote(f.getAbsolutePath()), 5);
        if (r.ran && !r.out.trim().isEmpty()) b.append('\n').append(r.out.trim());
        info(f.getName(), b.toString());
    }

    /** Write the viewer's current text back, which only makes sense for text. */
    private void saveEdited() {
        if (viewing == null) { toast("Only a text view can be written back"); return; }
        final File target = viewing;
        Sheet.of(this, "Overwrite?")
             .mono(target.getAbsolutePath())
             .text("This replaces the file with what is on screen. A copy of the "
                 + "current contents is kept first, so it can be put back.")
             .action("Overwrite", true, new Runnable() {
                 @Override public void run() {
                     try {
                         File staged = stage(target.getName());
                         FileOutputStream o = new FileOutputStream(staged);
                         try { o.write(viewerText.getText().toString().getBytes("UTF-8")); }
                         finally { o.close(); }
                         String how = replace(target, staged);
                         toast("Written (" + how + ")");
                     } catch (Exception e) {
                         info("Could not write", String.valueOf(e.getMessage()));
                     }
                 }
             })
             .close("Cancel")
             .show();
    }

    /**
     * Type a path and go there. With 342 package folders under Android/data, scrolling
     * to the one you want is not a serious way to navigate. A path to a file opens it
     * rather than failing.
     */
    private void goToPath() {
        ask("Go to", cwd == null ? "/sdcard" : cwd.getAbsolutePath(), new Consumer<String>() {
            @Override public void accept(String p) {
                if (p == null || p.trim().isEmpty()) return;
                File f = new File(p.trim());
                if (Vfs.isDirectory(FilesActivity.this, f)) { open(f); return; }
                if (Vfs.exists(FilesActivity.this, f)) { openFile(f); return; }
                toast("No such path");
            }
        });
    }

    // ------------------------------------------------- reading past the sandbox

    /**
     * Paths where the active backend is likely to see more than this process can.
     *
     * Only worth the round trip when a privileged backend is actually in use: without
     * one the backend is this same uid and would hit the identical wall, slower.
     */
    private boolean preferBackend(File f) {
        if (!Shell.get(this).privileged()) return false;
        String p = f.getAbsolutePath();
        return p.matches("(?i)^/(sdcard|storage/emulated/0)/Android(/.*)?$")
            || p.startsWith("/data");
    }

    /** Size, falling back to the backend when the direct call reports zero. */
    private long sizeOf(File f) { return Vfs.size(this, f); }

    /** Whether anything we have can write this file. */
    private boolean writable(File f) {
        return f.canWrite() || Shell.get(this).privileged();
    }

    /**
     * Write staged contents over a target through the active backend.
     *
     * @return how it was done, or null if it was refused (with the reason appended)
     */
    private String throughBackend(File target, File staged, long want, StringBuilder tried) {
        Shell sh = Shell.get(this);
        try {
            Shell.Result r = sh.exec("cat " + quote(staged.getAbsolutePath())
                    + " > " + quote(target.getAbsolutePath()), 25);
            if (sizeOf(target) == want) {
                staged.delete();
                return sh.privileged() ? "via Shizuku" : "via shell";
            }
            tried.append("\n· ").append(sh.current().name()).append(": ")
                 .append(r.out == null || r.out.trim().isEmpty()
                         ? "no output, size is " + sizeOf(target) + " not " + want
                         : r.out.trim());
        } catch (Exception e) {
            tried.append("\n· backend: ").append(e.getMessage());
        }
        return null;
    }

    /** Make a local copy of a file we may only be able to reach through the backend. */
    private void pull(File src, File dest) throws Exception {
        if (!preferBackend(src)) {
            try { copy(src, dest); if (dest.length() > 0) return; }
            catch (Exception ignored) { }
        }
        // The backend cannot write into our cache — /data/data is closed to uid 2000 as
        // well — so it copies into the shared staging directory, which both sides can
        // reach, and we move it the rest of the way ourselves.
        try {
            File relay = stage("pull-" + src.getName());
            Shell.get(this).exec("cp " + quote(src.getAbsolutePath())
                    + " " + quote(relay.getAbsolutePath()), 30);
            if (relay.isFile() && relay.length() > 0) {
                copy(relay, dest);
                relay.delete();
                if (dest.length() > 0) return;
            }
        } catch (Exception ignored) { }
        // Last resort: through base64, which needs no shared writable directory.
        byte[] all = Vfs.bytes(this, src, 32 * 1024 * 1024);
        FileOutputStream o = new FileOutputStream(dest);
        try { o.write(all); } finally { o.close(); }
    }

    // ------------------------------------------------------- replacing a file

    /**
     * Somewhere to build the new contents before they go anywhere near the original.
     *
     * The external media directory is preferred over the cache for one specific reason:
     * it is the only app-writable place the shell uid can also read, which is what makes
     * the privileged fallback in {@link #replace} possible at all. /data/data is closed
     * to everything but us.
     */
    private File stage(String name) throws Exception {
        File dir = null;
        try {
            File[] media = getExternalMediaDirs();
            if (media != null && media.length > 0 && media[0] != null) {
                dir = new File(media[0], "staging");
                if (!dir.isDirectory() && !dir.mkdirs()) dir = null;
            }
        } catch (Throwable ignored) { }
        if (dir == null) dir = getCacheDir();
        File f = new File(dir, "stage-" + name);
        if (f.exists() && !f.delete()) throw new Exception("stale staging file: " + f);
        return f;
    }

    /**
     * Put staged contents in place of an existing file, trying each mechanism this
     * platform actually permits, in increasing order of intrusiveness.
     *
     * Truncating a file in place is the obvious move and the one that fails most often
     * here. Under /sdcard/Android the FUSE layer can refuse an open-for-write on a path
     * it will happily let us unlink and recreate, and an owning app holding the file open
     * can block the write as well. The working method on those paths is the one done by
     * hand for years: write a sibling, remove the original, move the sibling into its
     * place. That is attempted automatically rather than left to the user.
     *
     * The original is copied aside before anything destructive happens, because step two
     * deletes it: if the rename then failed there would otherwise be nothing to go back
     * to. The backup path is reported in the exception when every method fails.
     *
     * @return a short description of which method succeeded
     */
    private String replace(File target, File staged) throws Exception {
        if (!staged.isFile()) throw new Exception("nothing staged to write");
        final long want = staged.length();

        // Keep the old contents. Not optional — the swap below unlinks the original.
        File backup = null;
        try {
            File bdir = new File(getCacheDir(), "backup");
            bdir.mkdirs();
            backup = new File(bdir, target.getName() + "."
                    + System.currentTimeMillis() + ".bak");
            pull(target, backup);
            if (backup.length() == 0) backup = null;
        } catch (Exception e) { backup = null; }
        String recover = backup == null ? ""
                : "\n\nThe previous contents are safe at:\n" + backup.getAbsolutePath();

        StringBuilder tried = new StringBuilder();

        // Where the backend is the only thing that can reach the path at all, go
        // straight there: the two direct methods below would not merely fail, the
        // second one fails partway through having already unlinked the original.
        if (preferBackend(target)) {
            String viaBackend = throughBackend(target, staged, want, tried);
            if (viaBackend != null) return viaBackend;
        }

        // 1. Straight truncating write.
        try {
            copy(staged, target);
            if (sizeOf(target) == want) { staged.delete(); return "in place"; }
            tried.append("\n· in-place write produced ").append(sizeOf(target))
                 .append(" of ").append(want).append(" bytes");
        } catch (Exception e) {
            tried.append("\n· in-place write: ").append(e.getMessage());
        }

        // 2. Sibling, unlink, rename — the dance that works when (1) does not.
        File tmp = new File(target.getParentFile(), "." + target.getName() + ".bsnew");
        try {
            if (tmp.exists()) tmp.delete();
            copy(staged, tmp);
            if (tmp.length() != want) throw new Exception("sibling copy came up short");
            if (target.exists() && !target.delete()) throw new Exception("cannot unlink original");
            if (tmp.renameTo(target)) { staged.delete(); return "replaced"; }
            // Rename refused but the original is already gone, so copy it back in.
            copy(tmp, target);
            if (sizeOf(target) == want) {
                tmp.delete(); staged.delete(); return "recreated";
            }
            throw new Exception("rename and recreate both refused");
        } catch (Exception e) {
            tried.append("\n· replace: ").append(e.getMessage());
        } finally {
            if (tmp.exists() && tmp.length() == 0) tmp.delete();
        }

        // 3. Through whichever backend is active. With Shizuku this reaches paths the app
        //    uid cannot touch at all; without it this is the same uid and simply fails.
        Shell sh = Shell.get(this);
        String viaBackend = throughBackend(target, staged, want, tried);
        if (viaBackend != null) return viaBackend;

        // Nothing worked. If the target is now missing, put the backup back.
        if (!target.isFile() && backup != null) {
            try { copy(backup, target); tried.append("\n· original restored"); }
            catch (Exception ignored) { }
        }
        throw new Exception("Every write method was refused."
                + tried + "\n\n"
                + (sh.privileged() ? "" : "Shizuku would likely open this path. ")
                + "The staged version is at:\n" + staged.getAbsolutePath()
                + recover);
    }

    /**
     * Find a string or byte sequence, and offer to patch at what follows it.
     *
     * Reading an offset off a hex dump and typing it into a box is how you edit one
     * byte and corrupt a save. In a structured file — a .NET BinaryFormatter
     * dictionary, say — what you want is almost always a known key with its value a
     * few bytes later, so searching for the key and patching from there is quicker
     * and much harder to get wrong.
     */
    private void find() {
        if (viewing == null) { toast("Open a file first"); return; }
        final File target = viewing;

        LinearLayout box = th.column(this);
        int p = th.dp(16);
        box.setPadding(p, p, p, 0);
        final EditText q = new EditText(this);
        q.setHint("text to find, or hex like 08 01 01");
        q.setSingleLine(true);
        q.setTextColor(skin.inkPrimary);
        q.setTypeface(Typeface.MONOSPACE);
        box.addView(q);

        Sheet.of(this, "Find in " + target.getName())
             .view(box)
             .action("Find", true, new Runnable() {
                 @Override public void run() { runFind(target, q.getText().toString()); }
             })
             .close("Cancel")
             .show();
    }

    private void runFind(final File target, final String query) {
        if (query == null || query.trim().isEmpty()) return;
        final String needle = query.trim();
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                final byte[] data = Vfs.bytes(FilesActivity.this, target, 8 * 1024 * 1024);
                // A query of only hex digits is read as bytes. The ambiguity is real
                // — "0001" could be either — and hex wins, because that is what
                // someone types while looking at a hex dump.
                byte[] pat = looksHex(needle) ? parseHex(needle) : null;
                if (pat == null || pat.length == 0) {
                    try { pat = needle.getBytes("UTF-8"); } catch (Exception e) { return; }
                }
                final byte[] pattern = pat;
                final java.util.List<Integer> hits = new java.util.ArrayList<>();
                outer:
                for (int i = 0; i + pattern.length <= data.length; i++) {
                    for (int j = 0; j < pattern.length; j++) {
                        if (data[i + j] != pattern[j]) continue outer;
                    }
                    hits.add(i);
                    if (hits.size() >= 50) break;
                }
                h.post(new Runnable() {
                    @Override public void run() { showHits(target, needle, pattern, data, hits); }
                });
            }
        }, "find").start();
    }

    private static boolean looksHex(String s) {
        String t = s.replaceAll("\\s", "");
        return t.length() >= 2 && t.length() % 2 == 0 && t.matches("[0-9a-fA-F]+");
    }

    private void showHits(final File target, String needle, final byte[] pattern,
                          final byte[] data, final java.util.List<Integer> hits) {
        if (hits.isEmpty()) { toast("Not found"); return; }
        final String[] items = new String[hits.size()];
        for (int i = 0; i < hits.size(); i++) {
            int at = hits.get(i);
            int after = at + pattern.length;
            StringBuilder next = new StringBuilder();
            for (int k = after; k < Math.min(after + 6, data.length); k++) {
                next.append(String.format(Locale.US, "%02X ", data[k]));
            }
            items[i] = String.format(Locale.US, "%d  (0x%X)   then  %s",
                    at, at, next.toString().trim());
        }
        Sheet s = Sheet.of(this, hits.size() + " match"
                + (hits.size() == 1 ? "" : "es") + " for " + needle);
        for (int i = 0; i < items.length; i++) {
            final int w = i;
            s.choice(items[i], null, false, new Runnable() {
                @Override public void run() {
                    viewing = target;
                    // Offer the position just past the match, which is where a value
                    // sits when the match was its key.
                    patchBytes(hits.get(w) + pattern.length);
                }
            });
        }
        s.close("Close").show();
    }

    /**
     * Overwrite bytes at an offset. The whole point of a hex view is being able to
     * change something, and a byte patch is the one edit that works on any format.
     */
    private void patchBytes(long presetOffset) {
        if (viewing == null) { toast("Open a file first"); return; }
        final File target = viewing;

        LinearLayout box = th.column(this);
        int p = th.dp(16);
        box.setPadding(p, p, p, 0);
        final EditText at = new EditText(this);
        at.setHint("offset, decimal or 0x hex");
        if (presetOffset > 0) at.setText(String.valueOf(presetOffset));
        at.setSingleLine(true);
        at.setTextColor(skin.inkPrimary);
        at.setTypeface(Typeface.MONOSPACE);
        box.addView(at);
        final EditText bytes = new EditText(this);
        bytes.setHint("bytes, e.g. 4A 6F 00 or 4a6f00");
        bytes.setSingleLine(true);
        bytes.setTextColor(skin.inkPrimary);
        bytes.setTypeface(Typeface.MONOSPACE);
        box.addView(bytes);

        Sheet.of(this, "Patch " + target.getName())
                .view(box)
                .action("Write", true, new Runnable() {
                    @Override public void run() {
                        try {
                            String os = at.getText().toString().trim();
                            long off = os.toLowerCase(Locale.US).startsWith("0x")
                                    ? Long.parseLong(os.substring(2), 16)
                                    : Long.parseLong(os);
                            byte[] data = parseHex(bytes.getText().toString());
                            if (data.length == 0) { toast("No bytes given"); return; }
                                            // sizeOf, not length(): java.io.File cannot stat a file
                            // in a blocked tier and reports 0, which made every patch
                            // of an Android/data file refuse itself as out of range.
                            long fileSize = sizeOf(target);
                            if (off < 0 || off + data.length > fileSize) {
                                info("Out of range", "The file is " + fileSize
                                        + " bytes; that patch would run past the end. "
                                        + "Growing a file is not done here on purpose.");
                                return;
                            }
                            String how;
                            try {
                                // A byte patch at an offset neither truncates nor
                                // relinks, so this often succeeds where a full
                                // overwrite would not. Try it first.
                                RandomAccessFile raf = new RandomAccessFile(target, "rw");
                                try { raf.seek(off); raf.write(data); }
                                finally { raf.close(); }
                                how = "in place";
                            } catch (Exception direct) {
                                // Refused in place. Patch a staged copy and swap it
                                // in — and pull the original through the backend,
                                // because copy() reads with java.io and throws the
                                // same EACCES that sent us down this path.
                                File staged = stage(target.getName());
                                pull(target, staged);
                                RandomAccessFile raf = new RandomAccessFile(staged, "rw");
                                try { raf.seek(off); raf.write(data); }
                                finally { raf.close(); }
                                how = replace(target, staged);
                            }
                            toast(data.length + " bytes at " + off + " (" + how + ")");
                            viewHex(target);
                        } catch (Exception e) {
                            info("Could not patch", String.valueOf(e.getMessage()));
                        }
                    }
                })
                .close("Cancel")
                .show();
    }

    private static byte[] parseHex(String s) {
        String clean = s.replaceAll("[^0-9a-fA-F]", "");
        if (clean.length() % 2 != 0) clean = clean.substring(0, clean.length() - 1);
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /**
     * Run a statement against the working copy, then offer to put it back. Editing the
     * original in place would be reckless: the app that owns it may have it open, and a
     * half-applied change to a save file is worse than none.
     */
    private void runSql() {
        if (pendingDb == null) { toast("Open a database first"); return; }
        final File original = pendingDb;
        final File work = new File(getCacheDir(), "peek.db");
        if (!work.isFile()) { toast("No working copy"); return; }

        final EditText sql = new EditText(this);
        sql.setHint("SELECT * FROM table   or   UPDATE …");
        sql.setTextColor(skin.inkPrimary);
        sql.setTypeface(Typeface.MONOSPACE);
        sql.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        Sheet.of(this, "SQL on a copy of " + original.getName())
                .view(sql)
                .action("Run", true, new Runnable() {
                    @Override public void run() { execSql(work, sql.getText().toString()); }
                })
                .action("Write back", true, new Runnable() {
                    @Override public void run() { writeBackDb(original, work); }
                })
                .close("Cancel")
                .show();
    }

    private void execSql(File work, String statement) {
        if (statement == null || statement.trim().isEmpty()) return;
        SQLiteDatabase db = null;
        StringBuilder out = new StringBuilder();
        try {
            db = SQLiteDatabase.openDatabase(work.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
            String head = statement.trim().split("\\s+")[0].toLowerCase(Locale.US);
            if (head.equals("select") || head.equals("pragma") || head.equals("explain")) {
                Cursor c = db.rawQuery(statement, null);
                try {
                    int cols = c.getColumnCount();
                    for (int i = 0; i < cols; i++) {
                        out.append(c.getColumnName(i)).append(i < cols - 1 ? " | " : "\n");
                    }
                    int rows = 0;
                    while (c.moveToNext() && rows < 200) {
                        for (int i = 0; i < cols; i++) {
                            String v;
                            try { v = c.getString(i); } catch (Exception e) { v = "<blob>"; }
                            out.append(v == null ? "null" : v).append(i < cols - 1 ? " | " : "\n");
                        }
                        rows++;
                    }
                    out.append("(").append(rows).append(" rows)");
                } finally { c.close(); }
            } else {
                db.execSQL(statement);
                out.append("statement executed against the working copy.\n\n")
                   .append("Nothing has touched the original yet — use Write back.");
            }
        } catch (Exception e) {
            out.append("error: ").append(e.getMessage());
        } finally {
            if (db != null) try { db.close(); } catch (Exception ignored) {}
        }
        info("SQL", out.toString());
    }

    private void writeBackDb(final File original, final File work) {
        Sheet.of(this, "Replace the original?")
                .mono(original.getAbsolutePath())
                .text("The edited copy replaces it, and a copy of the current database "
                    + "is kept first. If the owning app has it open, that app may "
                    + "overwrite this again or misbehave — close it first.")
                .action("Replace", true, new Runnable() {
                    @Override public void run() {
                        try {
                            File staged = stage(original.getName());
                            copy(work, staged);
                            toast("Written back (" + replace(original, staged) + ")");
                        } catch (Exception e) {
                            info("Failed", String.valueOf(e.getMessage()));
                        }
                    }
                })
                .close("Cancel")
                .show();
    }

    private void rename(final File f) {
        ask("Rename " + f.getName(), f.getName(), new Consumer<String>() {
            @Override public void accept(String name) {
                if (name == null || name.trim().isEmpty()) return;
                File dest = new File(f.getParentFile(), name.trim());
                if (dest.exists()) { toast("Already exists"); return; }
                if (f.renameTo(dest)) { toast("Renamed"); open(cwd); return; }
                // Same reasoning as the write path: what Java refuses, a move through
                // the active backend may still do.
                Shell sh = Shell.get(FilesActivity.this);
                sh.exec("mv " + quote(f.getAbsolutePath())
                        + " " + quote(dest.getAbsolutePath()), 15);
                if (dest.exists() && !f.exists()) { toast("Renamed"); open(cwd); return; }
                info("Could not rename", "Both the filesystem and the "
                        + sh.current().name() + " backend refused."
                        + (sh.privileged() ? "" : "\n\nShizuku would likely allow it."));
            }
        });
    }

    private void delete(final File f) {
        Sheet.of(this, "Delete?")
                .mono(f.getAbsolutePath())
                .text("There is no undo and nothing goes to a recycle bin.")
                .action("Delete", true, new Runnable() {
                    @Override public void run() {
                        boolean ok = f.isDirectory() ? deleteTree(f) : f.delete();
                        if (!ok) {
                            Shell.get(FilesActivity.this).exec(
                                    "rm -rf " + quote(f.getAbsolutePath()), 20);
                            ok = !f.exists();
                        }
                        toast(ok ? "Deleted" : "Refused");
                        open(cwd);
                    }
                })
                .close("Cancel")
                .show();
    }

    private static boolean deleteTree(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) {
            if (k.isDirectory()) deleteTree(k); else k.delete();
        }
        return dir.delete();
    }

    private void create() {
        final String[] what = {"New folder", "New empty file"};
        Sheet cs = Sheet.of(this, "Create in " + cwd.getName());
        for (int i = 0; i < what.length; i++) {
            final int w = i;
            cs.choice(what[i], null, false, new Runnable() {
                    @Override public void run() {
                        ask(what[w], "", new Consumer<String>() {
                            @Override public void accept(String name) {
                                if (name == null || name.trim().isEmpty()) return;
                                File dest = new File(cwd, name.trim());
                                try {
                                    boolean ok = w == 0 ? dest.mkdirs() : dest.createNewFile();
                                    toast(ok ? "Created" : "Refused");
                                } catch (Exception e) {
                                    info("Could not create", String.valueOf(e.getMessage()));
                                }
                                open(cwd);
                            }
                        });
                    }
                });
        }
        cs.close("Cancel").show();
    }

    /** Paste whatever was copied or cut, into the directory being viewed. */
    private void paste() {
        if (clipFile == null) { toast("Nothing copied yet"); return; }
        final File src = clipFile;
        File dest = new File(cwd, src.getName());
        if (dest.getAbsolutePath().equals(src.getAbsolutePath())) {
            toast("Same place"); return;
        }
        if (dest.exists()) { toast(src.getName() + " already exists here"); return; }
        try {
            if (src.isDirectory()) { info("Folders", "Only files are pasted for now."); return; }
            copy(src, dest);
            if (clipCut && !src.delete()) {
                info("Copied, not moved", "The copy succeeded but the original could not "
                        + "be deleted, so it is still there.");
            }
            clipFile = null;
            toast(clipCut ? "Moved" : "Copied");
            open(cwd);
        } catch (Exception e) {
            info("Could not paste", String.valueOf(e.getMessage()));
        }
    }
    // ------------------------------------------------------------------ helpers

    private boolean hasAllFiles() {
        return Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager();
    }

    private void requestAllFiles() {
        if (hasAllFiles()) { toast("Already granted"); return; }
        try {
            Intent i = new Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception e2) {
                toast("No settings page for this on this build");
            }
        }
    }

    private static void copy(File from, File to) throws Exception {
        FileInputStream in = new FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { out.close(); } catch (Exception ignored) {}
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        }
        return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
    }

    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    private static String quoteIdent(String s) { return "\"" + s.replace("\"", "\"\"") + "\""; }
}
