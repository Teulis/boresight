package com.boresight;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;


/**
 * A dialog that looks like the rest of the app.
 *
 * The platform AlertDialog cannot be skinned in any useful way without a theme
 * overlay per attribute, and the result still carries Material's typography and
 * corner radii — so a TMOG-styled app opens a dialog and suddenly looks like Settings.
 * This draws its own panel instead: the skin's surface, ink and accent, its corner
 * radius, its type.
 *
 * It also avoids the trap that broke the backend dialog: an AlertDialog shows a
 * message or a list, never both, and silently drops whichever lost. Here the body and
 * the actions are just views in a column, so both always appear.
 */
public final class Sheet {

    private final Activity a;
    private final Theme th;
    private final Skin skin;
    private final Dialog dialog;
    private final LinearLayout column;
    private final LinearLayout actions;
    private EditText field;

    private Sheet(Activity activity, String title) {
        a = activity;
        th = new Theme(a);
        skin = Skin.current(a);

        dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        // The skin's tile colour is 90% opaque, which is right for a tile sitting on
        // the page and wrong for a dialog floating over it: the screen underneath read
        // straight through the options. An opaque base of the window colour goes behind
        // the tile so the panel keeps its look and stops being see-through.
        panel.setBackground(new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{
                        th.solid(0xFF000000 | (skin.windowFlat() & 0xFFFFFF),
                                 skin.tileCornerRadius),
                        skin.tileBg(th)
                }));
        int pad = th.dp(18);
        panel.setPadding(pad, th.dp(16), pad, th.dp(12));

        if (title != null && !title.isEmpty()) {
            TextView t = new TextView(a);
            t.setText(title);
            t.setTextColor(skin.inkTitle);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            t.setTypeface(skin.face(false, android.graphics.Typeface.BOLD));
            t.setLetterSpacing(0.06f);
            t.setPadding(0, 0, 0, th.dp(10));
            panel.addView(t);
        }

        column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(a);
        scroll.addView(column);
        // Cap the height so a long body still leaves the actions reachable; the
        // scroll view takes whatever is left rather than pushing them off screen.
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        panel.addView(scroll, slp);

        actions = new LinearLayout(a);
        actions.setOrientation(LinearLayout.VERTICAL);
        panel.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        if (w != null) {
            // Transparent behind our own panel, otherwise the platform's white card
            // sits underneath and shows at the corners.
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (a.getResources().getDisplayMetrics().widthPixels * 0.92f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }
    }

    public static Sheet of(Activity a, String title) { return new Sheet(a, title); }

    /** Body text, in the skin's secondary ink. */
    public Sheet text(String s) {
        if (s == null || s.isEmpty()) return this;
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(skin.inkSecondary);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(0, 0, 0, th.dp(8));
        column.addView(t);
        return this;
    }

    /** Monospaced block, for anything column-aligned. */
    public Sheet mono(String s) {
        if (s == null || s.isEmpty()) return this;
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(skin.inkPrimary);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTextIsSelectable(true);
        t.setPadding(0, 0, 0, th.dp(8));
        column.addView(t);
        return this;
    }

    /**
     * An editable field, for the cases where a dialog has to take text rather than a pick.
     *
     * Suggestions are off. Every use of this so far is an identifier, a pattern or a path,
     * and autocorrect on those is actively destructive: it will helpfully retitle a MAC
     * address. One field per sheet; {@link #typed()} reads it back.
     */
    public Sheet input(String hint, String initial, int lines) {
        field = new EditText(a);
        field.setHint(hint == null ? "" : hint);
        if (initial != null) field.setText(initial);
        field.setTextColor(skin.inkPrimary);
        field.setHintTextColor(skin.inkSecondary);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        field.setTypeface(android.graphics.Typeface.MONOSPACE);
        field.setBackground(skin.tileBg(th));
        field.setPadding(th.dp(12), th.dp(10), th.dp(12), th.dp(10));
        field.setGravity(Gravity.TOP | Gravity.START);
        int type = android.text.InputType.TYPE_CLASS_TEXT
                 | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        if (lines > 1) {
            type |= android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE;
            field.setSingleLine(false);
            field.setMinLines(lines);
            field.setMaxLines(Math.max(lines, 8));
        } else {
            field.setSingleLine(true);
        }
        field.setInputType(type);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, th.dp(4), 0, th.dp(6));
        column.addView(field, lp);
        return this;
    }

    /**
     * Any view, for the dialogs that need more than one field.
     *
     * The platform AlertDialog's setView is the only reason a few screens were still
     * building platform dialogs; this is the equivalent, inside our own panel.
     */
    public Sheet view(View v) {
        if (v != null) column.addView(v);
        return this;
    }

    /** What is in the field, or an empty string when the sheet has none. */
    public String typed() {
        return field == null || field.getText() == null ? "" : field.getText().toString();
    }

    public Sheet rule() {
        View v = new View(a);
        v.setBackgroundColor(skin.inkRule);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, th.hair()));
        lp.setMargins(0, th.dp(6), 0, th.dp(8));
        column.addView(v, lp);
        return this;
    }

    /** A selectable row with a title and a description, like the drawer's. */
    public Sheet choice(final String label, final String note, final boolean current,
                        final Runnable onPick) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(th.dp(12), th.dp(11), th.dp(12), th.dp(11));
        row.setBackground(th.ripple(current
                ? skin.badgeBg(th, Skin.alpha(skin.accent(Skin.CH_RSRP), 0.14f))
                : th.solid(0x00000000, 0)));
        row.setClickable(true);

        TextView t = new TextView(a);
        t.setText(current ? label + "   ✓" : label);
        t.setTextColor(current ? skin.accent(Skin.CH_RSRP) : skin.inkPrimary);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(skin.face(false, current ? android.graphics.Typeface.BOLD
                                               : android.graphics.Typeface.NORMAL));
        row.addView(t);

        if (note != null && !note.isEmpty()) {
            TextView n = new TextView(a);
            n.setText(note);
            n.setTextColor(skin.inkSecondary);
            n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            n.setPadding(0, th.dp(2), 0, 0);
            row.addView(n);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, th.dp(3), 0, 0);
        row.setLayoutParams(lp);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                if (onPick != null) onPick.run();
            }
        });
        column.addView(row);
        return this;
    }

    /** A button in the action strip at the bottom. */
    public Sheet action(final String label, final boolean closes, final Runnable run) {
        TextView btn = new TextView(a);
        btn.setText(label);
        btn.setTextColor(skin.accent(Skin.CH_RSRP));
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(th.dp(14), th.dp(12), th.dp(14), th.dp(12));
        btn.setBackground(th.ripple(skin.badgeBg(th,
                Skin.alpha(skin.accent(Skin.CH_RSRP), 0.10f))));
        btn.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, th.dp(7), 0, 0);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (run != null) run.run();
                if (closes) dialog.dismiss();
            }
        });
        actions.addView(btn);
        return this;
    }

    public Sheet close(String label) {
        TextView btn = new TextView(a);
        btn.setText(label == null ? "Close" : label);
        btn.setTextColor(skin.inkSecondary);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(th.dp(14), th.dp(12), th.dp(14), th.dp(10));
        btn.setBackground(th.ripple(th.solid(0x00000000, 0)));
        btn.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, th.dp(4), 0, 0);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        actions.addView(btn);
        return this;
    }

    /**
     * Refuse to close on back or an outside tap.
     *
     * For the handful of prompts something is waiting on: the host-key check blocks the
     * connecting thread on a latch until an answer arrives, so a dialog that could be
     * dismissed without answering would hang the connection instead of refusing it.
     */
    public Sheet sticky() {
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        return this;
    }

    public Dialog show() {
        dialog.show();
        return dialog;
    }

    public void dismiss() { dialog.dismiss(); }

    /** Convenience for the common "title, body, one Close" case. */
    public static void info(Activity a, String title, String body) {
        of(a, title).mono(body).close("Close").show();
    }
}
