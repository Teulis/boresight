package com.boresight;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

/**
 * The app's own title bar, in place of the platform ActionBar.
 *
 * Targeting SDK 35 puts the window edge-to-edge on Android 15 and up, and the OEM action
 * bar then sits over the content with a height that does not match what it draws, so
 * insetting around it never quite lands. Drawing the bar ourselves removes the guesswork,
 * and a flat bar with a hamburger, a title and an overflow is closer to the desktop
 * instrument look than the system one anyway.
 */
public class Header extends LinearLayout {

    public interface MenuPick { void picked(int id); }

    private final Theme th;
    private final Skin skin;
    private final Activity host;

    private final TextView titleView, subtitleView, overflow;
    private String[] menuLabels = new String[0];
    private MenuPick menuPick;

    public Header(Activity a, Theme theme, Skin s, String title, final Runnable onMenu) {
        super(a);
        host = a;
        th = theme;
        skin = s;

        setOrientation(VERTICAL);
        setBackground(skin.headerBg(th));
        setBackgroundColor(skin.header[0]);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(th.dp(6), th.dp(6), th.dp(6), th.dp((int) skin.headerBottomPadding));

        // Hamburger. Drawn as three rules rather than an icon font so it inherits the skin.
        View burger = new View(a) {
            private final android.graphics.Paint p =
                    new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas c) {
                p.setColor(skin.inkControl);
                float w = getWidth(), h = getHeight();
                float barH = Math.max(2f, th.dpf(1.8f));
                float left = w * 0.26f, right = w * 0.74f;
                float gap = th.dpf(5.5f);
                float cy = h / 2f;
                c.drawRect(left, cy - gap - barH / 2, right, cy - gap + barH / 2, p);
                c.drawRect(left, cy - barH / 2, right * 0.88f, cy + barH / 2, p);
                c.drawRect(left, cy + gap - barH / 2, right * 0.72f, cy + gap + barH / 2, p);
            }
        };
        burger.setBackground(th.ripple(th.solid(0x00000000, 20)));
        burger.setClickable(true);
        burger.setContentDescription("Menu");
        burger.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { onMenu.run(); }
        });
        row.addView(burger, new LinearLayout.LayoutParams(th.dp(44), th.dp(44)));

        LinearLayout text = new LinearLayout(a);
        text.setOrientation(VERTICAL);
        text.setPadding(th.dp(6), 0, th.dp(6), 0);

        titleView = new TextView(a);
        titleView.setText(title);
        titleView.setTextColor(skin.inkTitle);
        titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, skin.szTitle);
        titleView.setTypeface(skin.face(skin.monoTitle, Typeface.BOLD));
        titleView.setLetterSpacing(skin.titleTracking + 0.06f);
        titleView.setSingleLine(true);
        text.addView(titleView);

        subtitleView = new TextView(a);
        subtitleView.setTextColor(skin.inkSecondary);
        subtitleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        subtitleView.setTypeface(Typeface.MONOSPACE);
        subtitleView.setSingleLine(true);
        subtitleView.setVisibility(GONE);
        text.addView(subtitleView);

        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        overflow = new TextView(a);
        overflow.setText("⋮");                       // vertical ellipsis
        overflow.setTextColor(skin.inkControl);
        overflow.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20);
        overflow.setGravity(Gravity.CENTER);
        overflow.setBackground(th.ripple(th.solid(0x00000000, 20)));
        overflow.setClickable(true);
        overflow.setVisibility(GONE);
        overflow.setContentDescription("More");
        overflow.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { showMenu(v); }
        });
        row.addView(overflow, new LinearLayout.LayoutParams(th.dp(40), th.dp(44)));

        addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View rule = new View(a);
        rule.setBackgroundColor(skin.headerRule);
        addView(rule, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, th.hair())));
    }

    public void setTitle(String s) { titleView.setText(s); }

    public void setSubtitle(String s) {
        if (s == null || s.isEmpty()) {
            subtitleView.setVisibility(GONE);
        } else {
            subtitleView.setText(s);
            subtitleView.setVisibility(VISIBLE);
        }
    }

    /** Items are offered in order; the pick callback gets the index. */
    public void setMenu(String[] labels, MenuPick pick) {
        menuLabels = labels == null ? new String[0] : labels;
        menuPick = pick;
        overflow.setVisibility(menuLabels.length == 0 ? GONE : VISIBLE);
    }

    private void showMenu(View anchor) {
        if (menuLabels.length == 0 || menuPick == null) return;
        PopupMenu m = new PopupMenu(host, anchor);
        for (int i = 0; i < menuLabels.length; i++) m.getMenu().add(0, i, i, menuLabels[i]);
        m.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                menuPick.picked(item.getItemId());
                return true;
            }
        });
        m.show();
    }

    /**
     * Pad the bar itself by the status bar inset, and the page by the rest. The bar's own
     * background then runs under the status bar instead of leaving a stripe of page colour.
     */
    public static void inset(final Activity a, final Header header, final View page,
                             final Theme th) {
        inset(a, header, page, th, null);
    }

    public static void inset(final Activity a, final Header header, final View page,
                             final Theme th, final Drawer drawer) {
        final View content = a.findViewById(android.R.id.content);
        if (content == null) return;
        content.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public android.view.WindowInsets onApplyWindowInsets(
                    View v, android.view.WindowInsets insets) {
                android.graphics.Insets b = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout());
                header.setPadding(0, b.top, 0, 0);
                if (page != null) page.setPadding(b.left, 0, b.right, b.bottom);
                if (drawer != null) drawer.applyInsets(b.top, b.bottom);
                return insets;
            }
        });
        content.requestApplyInsets();
    }
}
