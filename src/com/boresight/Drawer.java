package com.boresight;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The hamburger panel. Hand-rolled rather than pulled from a support library, because this
 * project has no dependency graph: a frame holding the page, a scrim, and a panel that
 * slides in from the left. Edge-drag opens it, a drag or a scrim tap closes it, and the
 * host activity routes its back press here.
 */
public class Drawer extends FrameLayout {

    public interface Nav { void go(Class<?> activity); }

    private final Theme th;
    private final Skin skin;
    private final Activity host;

    private final View scrim;
    private final LinearLayout panel;
    private final LinearLayout items;
    private final TextView status;

    private float panelWidth;
    private boolean open;
    private float dragStartX, dragStartY, openAtDragStart;
    private boolean dragging, decided;

    public Drawer(Activity a, Theme theme, Skin s) {
        super(a);
        host = a;
        th = theme;
        skin = s;

        scrim = new View(a);
        scrim.setBackgroundColor(0xCC000000);
        scrim.setAlpha(0f);
        scrim.setVisibility(GONE);
        scrim.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { close(); }
        });
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(skin.windowBg(th));
        panel.setBackgroundColor(skin.windowFlat());
        panel.setElevation(th.dpf(12));

        // Header.
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setBackgroundColor(Skin.alpha(skin.header[0], 1f));
        head.setPadding(th.dp(16), th.dp(22), th.dp(16), th.dp(14));

        TextView name = new TextView(a);
        name.setText("BORESIGHT");
        name.setTextColor(skin.inkTitle);
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        name.setTypeface(skin.face(false, Typeface.BOLD));
        name.setLetterSpacing(0.18f);
        head.addView(name);

        status = new TextView(a);
        status.setTextColor(skin.inkSecondary);
        status.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        status.setTypeface(Typeface.MONOSPACE);
        status.setPadding(0, th.dp(3), 0, 0);
        head.addView(status);

        panel.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View rule = new View(a);
        rule.setBackgroundColor(skin.headerRule);
        panel.addView(rule, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, th.dp(1))));

        ScrollView sv = new ScrollView(a);
        items = new LinearLayout(a);
        items.setOrientation(LinearLayout.VERTICAL);
        items.setPadding(0, th.dp(6), 0, th.dp(24));
        sv.addView(items);
        panel.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // The width has to be set explicitly. WRAP_CONTENT resolves to the full screen
        // here, because the panel's header child is MATCH_PARENT, and a full-width panel
        // slid left by its intended width leaves the remainder parked over the page.
        panelWidth = Math.min(th.dpf(310),
                a.getResources().getDisplayMetrics().widthPixels * 0.82f);
        panel.setTranslationX(-panelWidth);
        panel.setVisibility(GONE);
        addView(panel, new LayoutParams((int) panelWidth,
                LayoutParams.MATCH_PARENT, Gravity.START));
    }

    /** The page content goes underneath the scrim and panel. */
    public void setContent(View content) {
        addView(content, 0, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // Re-derive on rotation. The width lives in the layout params, not just the
        // translation, so a closed panel is genuinely off-screen rather than merely
        // shifted.
        panelWidth = Math.min(th.dpf(310), w * 0.82f);
        ViewGroup.LayoutParams lp = panel.getLayoutParams();
        if (lp.width != (int) panelWidth) {
            lp.width = (int) panelWidth;
            panel.setLayoutParams(lp);
        }
        panel.setTranslationX(open ? 0 : -panelWidth);
        panel.setVisibility(open ? VISIBLE : GONE);
    }

    // ------------------------------------------------------------------ items

    public void caption(String text) {
        TextView t = th.caption(getContext(), text);
        t.setTextColor(skin.accent(Skin.CH_RSRP));
        t.setPadding(th.dp(16), th.dp(16), th.dp(16), th.dp(6));
        items.addView(t);
    }

    public void item(final String label, final String hint, final Runnable action) {
        item(label, hint, false, action);
    }

    /**
     * One menu row. {@code current} marks the screen you are already on: without it the
     * menu gives no clue where you are, and tapping the row you are standing on looks
     * like the app ignored you.
     */
    public void item(final String label, final String hint, boolean current,
                     final Runnable action) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(th.dp(16), th.dp(11), th.dp(16), th.dp(11));
        row.setBackground(th.ripple(current
                ? th.solid(Skin.alpha(skin.accent(Skin.CH_RSRP), 0.10f), 0)
                : th.solid(0x00000000, 0)));
        row.setClickable(true);

        TextView t = new TextView(getContext());
        t.setText(label);
        t.setTextColor(current ? skin.accent(Skin.CH_RSRP) : skin.inkPrimary);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(skin.face(false, current ? Typeface.BOLD : Typeface.NORMAL));
        row.addView(t);

        if (hint != null && !hint.isEmpty()) {
            TextView h = new TextView(getContext());
            h.setText(hint);
            h.setTextColor(skin.inkSecondary);
            h.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
            h.setPadding(0, th.dp(1), 0, 0);
            row.addView(h);
        }

        row.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                close();
                postDelayed(new Runnable() {
                    @Override public void run() { action.run(); }
                }, 140);
            }
        });
        items.addView(row);
    }

    public void screen(String label, String hint, final Class<?> activity) {
        item(label, hint, host.getClass() == activity, new Runnable() {
            @Override public void run() {
                if (host.getClass() == activity) return;
                // Reuse the existing instance rather than stacking a new copy each time.
                // Without this, hopping between panes piles up activities and back walks
                // the whole history instead of leaving the app.
                host.startActivity(new Intent(host, activity)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            }
        });
    }

    public void divider() {
        View v = new View(getContext());
        v.setBackgroundColor(skin.inkRule);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, th.hair()));
        lp.setMargins(th.dp(16), th.dp(8), th.dp(16), th.dp(8));
        items.addView(v, lp);
    }

    public void setStatus(String s) { status.setText(s); }

    /**
     * Keep the panel's own content clear of the system bars. The page's inset listener
     * consumes the dispatch, so the panel has to be told rather than listening itself.
     */
    public void applyInsets(int top, int bottom) {
        View head = panel.getChildAt(0);
        if (head != null) {
            head.setPadding(th.dp(16), top + th.dp(16), th.dp(16), th.dp(14));
        }
        items.setPadding(0, th.dp(6), 0, bottom + th.dp(24));
    }

    // ------------------------------------------------------------------ motion

    public boolean isOpen() { return open; }

    public void toggle() { if (open) close(); else open(); }

    public void open() {
        open = true;
        scrim.setVisibility(VISIBLE);
        panel.setVisibility(VISIBLE);
        animateTo(0f, 1f);
    }

    public void close() {
        open = false;
        animateTo(-panelWidth, 0f);
    }

    private void animateTo(final float tx, final float alpha) {
        final float fromX = panel.getTranslationX();
        final float fromA = scrim.getAlpha();
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(230);
        a.setInterpolator(new DecelerateInterpolator(1.6f));
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator v) {
                float f = (float) v.getAnimatedValue();
                panel.setTranslationX(fromX + (tx - fromX) * f);
                scrim.setAlpha(fromA + (alpha - fromA) * f);
            }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) {
                if (!open) {
                    scrim.setVisibility(GONE);
                    // Take it out of the tree entirely; a panel wider than its slide
                    // distance would otherwise leave an edge showing.
                    panel.setVisibility(GONE);
                }
            }
        });
        a.start();
    }

    /** Edge drag to open, drag anywhere on the panel to close. */
    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragStartX = e.getX();
                dragStartY = e.getY();
                openAtDragStart = panel.getTranslationX();
                dragging = false;
                decided = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (decided) break;
                float dx = e.getX() - dragStartX, dy = e.getY() - dragStartY;
                if (Math.abs(dx) < th.dpf(10) && Math.abs(dy) < th.dpf(10)) break;
                decided = true;
                boolean horizontal = Math.abs(dx) > Math.abs(dy) * 1.4f;
                boolean fromEdge = dragStartX < th.dpf(26);
                if (horizontal && ((!open && fromEdge && dx > 0) || (open && dx < 0))) {
                    dragging = true;
                    if (!open) {
                        scrim.setVisibility(VISIBLE);
                        panel.setVisibility(VISIBLE);
                    }
                    return true;
                }
                break;
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!dragging) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - dragStartX;
                float tx = Math.max(-panelWidth, Math.min(0, openAtDragStart + dx));
                panel.setTranslationX(tx);
                scrim.setAlpha(1f + tx / panelWidth);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                dragging = false;
                if (panel.getTranslationX() > -panelWidth * 0.5f) open(); else close();
                return true;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ wiring

    /**
     * Build the standard menu. Every screen gets the same one, so the app navigates the
     * same way wherever you are.
     */
    public static Drawer attach(Activity a, Theme th, Skin skin, View content) {
        Drawer d = new Drawer(a, th, skin);
        d.setContent(content);
        // Never leave the header subtitle blank. The Signal screen overwrites this with
        // live radio state; everywhere else it says what the app can currently reach,
        // which is the thing that changes underneath you.
        try { d.setStatus(Shell.get(a).backendShort()); }
        catch (Throwable ignored) { }

        d.buildMenu(a);
        a.setContentView(d);
        return d;
    }

    // ------------------------------------------------------------------ profiles

    public static final int VIEW_DAS = 0, VIEW_SIMPLE = 1, VIEW_ALL = 2;

    public static String viewName(int v) {
        switch (v) {
            case VIEW_SIMPLE: return "Simple";
            case VIEW_ALL:    return "Everything";
            default:          return "DAS";
        }
    }

    private static String viewNote(int v) {
        switch (v) {
            case VIEW_SIMPLE:
                return "The handful of screens most days need.";
            case VIEW_ALL:
                return "Every screen, including the ones that need a privileged "
                     + "backend to be interesting.";
            default:
                return "Site work: the radio, the survey, and the tools that go with "
                     + "a commissioning walk.";
        }
    }

    /**
     * Which screens appear in which view.
     *
     * Columns are DAS, Simple, Everything. The app grew past twenty screens, and a
     * flat list of twenty is a list nobody reads — the question on a ladder is "where
     * is the session log", not "what does this app do". Everything is still one
     * switch away, so nothing is hidden, only filed.
     */
    private static final Object[][] MENU = {
        // caption,            das,   simple, all
        {"Live radio",         true,  true,   true},
        {"Signal",             true,  true,   true,  MainActivity.class,
                "Serving cell, neighbours and traces"},
        {"Modulation",         true,  false,  true,  ModemActivity.class,
                "CQI, modulation order, constellation"},
        {"Network",            false, true,   true,  NetActivity.class,
                "Transport, capabilities, addressing, throughput"},
        {"Full detail",        true,  false,  true,  DetailsActivity.class,
                "Everything the platform will report"},
        {"Keystrings",         true,  false,  true,  KeystringActivity.class,
                "Samsung dialer codes, and what each one did"},

        // Making a record of somewhere.
        {"Capture",            true,  true,   true},
        {"Session log",        true,  true,   true,  SessionActivity.class,
                "Carriers, bands, DL/UL and PCI for the walk"},
        {"Walk test",          true,  false,  true,  WalkActivity.class,
                "Indoor survey on a floor plan"},
        {"Driving",            true,  true,   true,  HudActivity.class,
                "Big readouts for a windscreen mount"},
        {"Recording",          true,  false,  true,  WardriveActivity.class,
                "Start, stop, intervals and layers"},

        // Looking at what was recorded.
        {"Review",             true,  true,   true},
        {"Map",                true,  true,   true,  MapActivity.class,
                "Sightings, tracks and estimated towers"},
        {"History",            true,  false,  true,  HistoryActivity.class,
                "Sessions and per-cell history"},
        {"Radios",             true,  true,   true,  RadiosActivity.class,
                "Wi-Fi networks and Bluetooth devices heard, and where"},
        {"Detector",           false, false,  true,  FlockActivity.class,
                "Surveillance signatures found while recording"},

        {"Positioning",        true,  true,   true},
        {"Satellites",         true,  true,   true,  GnssActivity.class,
                "GNSS sky view, compass and signal levels"},
        {"Direct to cell",     false, true,   true,  SatActivity.class,
                "Which Starlink is overhead right now"},

        {"Network tools",      true,  false,  true},
        {"Net tools",          true,  false,  true,  NetToolsActivity.class,
                "Ping, traceroute, port scan, link facts"},
        {"SSH",                true,  false,  true,  SshActivity.class,
                "Log into network devices and move files"},
        {"Console",            false, false,  true,  ConsoleActivity.class,
                "Run shell commands, see what is permitted"},

        // What the phone itself is doing.
        {"This phone",         false, true,   true},
        {"Tasks",              false, false,  true,  TaskActivity.class,
                "Load, memory, power and running processes"},
        {"Storage",            false, true,   true,  StorageActivity.class,
                "What is using the space, drawn as a map"},
        {"Files",              false, false,  true,  FilesActivity.class,
                "Browse, hex edit, open databases"},
        {"USB",                false, false,  true,  UsbActivity.class,
                "Port state, power delivery and attached devices"},
        {"NFC",                false, false,  true,  NfcActivity.class,
                "Read, dump and write tags; MIFARE keys"},
        {"Sensors",            false, true,   true,  SensorsActivity.class,
                "Every sensor the phone exposes"},

        {"Control",            false, false,  true},
        {"Device",             false, false,  true,  DeviceActivity.class,
                "Security, OTA blocking and package control"},
    };
    /** What the user has typed into the filter, if anything. */
    private String query = "";
    /** Groups the user has opened. Remembered for the life of the drawer. */
    private final java.util.HashSet<String> expanded = new java.util.HashSet<>();
    private android.widget.EditText search;
    private LinearLayout searchRow;

    /**
     * Build the list.
     *
     * Twenty-six destinations, each with a line of explanation, is sixty lines of text
     * behind a hamburger — and the explanations are worth keeping, so the fix is not to
     * delete them but to stop showing all of them at once. Groups are collapsed to
     * their headings, which turns the default view into eight lines you can take in
     * without scrolling, and the group holding the current screen opens itself so you
     * always have your bearings.
     *
     * The search field is the real answer though. The question at the menu is almost
     * never "what does this app do", it is "where is the session log" — and typing
     * four letters beats finding the right category every time. Searching ignores the
     * view profile, because being unable to find a screen that exists is precisely the
     * frustration profiles cause.
     */
    private void buildMenu(final Activity a) {
        final int view = Prefs.integer(a, Prefs.MENU_VIEW, VIEW_DAS);

        addSearch(a);

        final String q = query.trim().toLowerCase();
        final boolean searching = !q.isEmpty();

        int col = view == VIEW_SIMPLE ? 2 : view == VIEW_ALL ? 3 : 1;
        String group = null;
        boolean groupOn = false;
        int hits = 0;

        // Collect by group first, so a heading is only drawn when it has something
        // under it — in search especially, most headings have nothing.
        java.util.LinkedHashMap<String, java.util.List<Object[]>> byGroup =
                new java.util.LinkedHashMap<>();
        for (Object[] row : MENU) {
            if (row.length == 4) {
                group = (String) row[0];
                groupOn = Boolean.TRUE.equals(row[col]);
                continue;
            }
            // Search reaches every screen regardless of profile; browsing honours it.
            boolean visible = searching
                    ? matches(q, (String) row[0], (String) row[5], group)
                    : (groupOn && Boolean.TRUE.equals(row[col]));
            if (!visible) continue;
            String g = group == null ? "Other" : group;
            java.util.List<Object[]> list = byGroup.get(g);
            if (list == null) { list = new java.util.ArrayList<>(); byGroup.put(g, list); }
            list.add(row);
            hits++;
        }

        if (searching && hits == 0) {
            caption("No match");
            item("Nothing called \"" + query.trim() + "\"",
                 "Search covers every screen, including ones the current view hides.",
                 false, null);
        }

        Class<?> here = a.getClass();
        for (java.util.Map.Entry<String, java.util.List<Object[]>> e : byGroup.entrySet()) {
            final String g = e.getKey();
            java.util.List<Object[]> rows = e.getValue();

            boolean holdsCurrent = false;
            for (Object[] r : rows) if (r[4] == here) holdsCurrent = true;

            // Searching shows results outright; there is no sense hiding a match
            // behind a heading the user then has to open.
            boolean open = searching || holdsCurrent || expanded.contains(g);

            if (!searching) {
                groupHeader(a, g, rows.size(), open);
            } else {
                caption(g);
            }
            if (!open) continue;
            for (Object[] r : rows) {
                screen((String) r[0], (String) r[5], (Class<?>) r[4]);
            }
        }

        if (byGroup.isEmpty() && !searching) {
            caption("Radio");
            screen("Signal", "Serving cell, neighbours and traces", MainActivity.class);
        }

        divider();
        // The profile switch moved below the list. It is the answer to "where did that
        // screen go", but search answers that better and the switch was taking the
        // most valuable line on the screen to say something most sessions never need.
        item("View: " + viewName(view), viewNote(view), false, new Runnable() {
            @Override public void run() { chooseView(a); }
        });
        // In the drawer rather than buried in Console, and in every view. The Console
        // and Device screens are hidden in the DAS and Simple profiles, so leaving the
        // backend button there would mean the one thing a stuck user needs is in the
        // one place they cannot get to.
        item("Privileged backend", Backend.summary(a), false, new Runnable() {
            @Override public void run() {
                Backend.show(a, 4242, new Runnable() {
                    @Override public void run() {
                        try { setStatus(Shell.get(a).backendShort()); }
                        catch (Throwable ignored) { }
                    }
                });
            }
        });
        screen("Sync", "Send and retrieve the survey between devices", SyncActivity.class);
        screen("Settings", "Skin, privacy, retention, export", SettingsActivity.class);
        screen("About", "Version, credits and what leaves this phone", AboutActivity.class);
        screen("Log", "What the app did, and checking for an update", LogActivity.class);
    }

    /**
     * Match a screen against what was typed.
     *
     * Matches the name, the description and the group, because people search for what
     * a thing does as often as what it is called — "camera" should find the Detector,
     * and "wifi" should find the screens that deal in it.
     */
    private static boolean matches(String q, String name, String note, String group) {
        if (name != null && name.toLowerCase().contains(q)) return true;
        if (note != null && note.toLowerCase().contains(q)) return true;
        return group != null && group.toLowerCase().contains(q);
    }

    /**
     * The search field, created once and never rebuilt.
     *
     * It deliberately lives in the panel rather than in the list, because the list is
     * torn down and rebuilt on every keystroke. The first version put it inside, and
     * typing "camera" produced "caea": each character arrived, triggered a rebuild
     * that destroyed the very view being typed into, and the next keystrokes landed on
     * a view that no longer existed. A control that filters a list cannot be part of
     * the list it filters.
     */
    private void addSearch(final Activity a) {
        if (searchRow != null) return;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(th.dp(14), th.dp(8), th.dp(14), th.dp(8));

        search = new android.widget.EditText(a);
        search.setHint("Search screens");
        search.setSingleLine(true);
        search.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        search.setTextColor(skin.inkPrimary);
        search.setHintTextColor(skin.inkSecondary);
        search.setBackground(skin.tileBg(th));
        search.setPadding(th.dp(12), th.dp(10), th.dp(12), th.dp(10));
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setText(query);
        search.setSelection(query.length());

        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a1, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a1, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                String now = s.toString();
                if (now.equals(query)) return;
                query = now;
                // Rebuild in place and put the cursor back. Cheap: this is a few dozen
                // text views, not a list that needs recycling.
                rebuild(a);
            }
        });

        box.addView(search, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchRow = box;
        // Index 2: after the header and its rule, before the scrolling list.
        panel.addView(box, 2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** A collapsed group: its name, how many screens are inside, and which way it is. */
    private void groupHeader(final Activity a, final String name, int count,
                             final boolean open) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(th.dp(16), th.dp(12), th.dp(16), th.dp(12));
        row.setClickable(true);
        // An open group gets a faint wash so the eye can see where it starts and ends
        // once several screens are listed beneath it.
        if (open) row.setBackgroundColor(Skin.alpha(skin.header[0], 0.5f));

        TextView t = new TextView(a);
        t.setText(name.toUpperCase());
        t.setTextColor(open ? skin.accent(Skin.CH_RSRP) : skin.inkSecondary);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(skin.face(false, Typeface.BOLD));
        t.setLetterSpacing(0.12f);
        row.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView n = new TextView(a);
        // The count is what makes a collapsed heading worth tapping — without it a
        // closed group gives no clue whether there is one screen in there or six.
        n.setText(open ? "−" : count + "  +");
        n.setTextColor(skin.inkSecondary);
        n.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        n.setTypeface(Typeface.MONOSPACE);
        row.addView(n);

        row.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (!expanded.remove(name)) expanded.add(name);
                rebuild(a);
            }
        });
        items.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * Redraw the list only.
     *
     * The search field is not touched, so typing is never interrupted and the caret
     * needs no restoring.
     */
    private void rebuild(Activity a) {
        items.removeAllViews();
        buildMenu(a);
    }

    private void chooseView(final Activity a) {
        final int now = Prefs.integer(a, Prefs.MENU_VIEW, VIEW_DAS);
        Sheet s = Sheet.of(a, "Menu")
                .text("Which screens appear. The choice is remembered, and nothing is "
                    + "removed by it — Everything is always one tap away.");
        for (final int v : new int[]{ VIEW_DAS, VIEW_SIMPLE, VIEW_ALL }) {
            s.choice(viewName(v), viewNote(v), v == now, new Runnable() {
                @Override public void run() {
                    if (v == now) return;
                    Prefs.put(a, Prefs.MENU_VIEW, v);
                    // Rebuilding in place would leave the old rows behind and the
                    // caller holds a reference to this Drawer, so the activity is
                    // recreated to apply it everywhere at once.
                    a.recreate();
                }
            });
        }
        s.close("Cancel").show();
    }

    // The hamburger lives in Header now; there is no platform action bar to borrow.
}
