package com.boresight;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Lays children out left to right, wrapping to a new line when they run out of width.
 *
 * The framework has no flow container, and the alternative — a horizontal scroller —
 * hides controls off the edge where they are easy to miss. Chips and shortcut buttons
 * want to be all visible at once, so they wrap instead.
 *
 * Child margins are honoured; everything else about a child's layout params is ignored
 * beyond its measured size.
 */
public class FlowLayout extends ViewGroup {

    private int lineGap;

    public FlowLayout(Context c) {
        super(c);
        lineGap = 0;
    }

    /** Extra vertical space between wrapped lines, on top of child margins. */
    public FlowLayout gap(int px) { lineGap = px; requestLayout(); return this; }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        int childSpec = MeasureSpec.makeMeasureSpec(Math.max(0, available),
                MeasureSpec.AT_MOST);

        int x = 0, lineHeight = 0, totalHeight = 0, widest = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;

            MarginLayoutParams lp = marginsOf(child);
            measureChild(child, childSpec, heightSpec);

            int w = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            int h = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;

            // Wrap, unless this is the first thing on the line — a child wider than the
            // container still has to go somewhere.
            if (x > 0 && x + w > available) {
                widest = Math.max(widest, x);
                totalHeight += lineHeight + lineGap;
                x = 0;
                lineHeight = 0;
            }
            x += w;
            lineHeight = Math.max(lineHeight, h);
        }
        widest = Math.max(widest, x);
        totalHeight += lineHeight;

        int width = resolveSize(widest + getPaddingLeft() + getPaddingRight(), widthSpec);
        int height = resolveSize(totalHeight + getPaddingTop() + getPaddingBottom(), heightSpec);
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int available = getWidth() - getPaddingLeft() - getPaddingRight();
        int x = getPaddingLeft(), y = getPaddingTop(), lineHeight = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;

            MarginLayoutParams lp = marginsOf(child);
            int w = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            int h = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;

            if (x > getPaddingLeft() && x + w > getPaddingLeft() + available) {
                x = getPaddingLeft();
                y += lineHeight + lineGap;
                lineHeight = 0;
            }
            child.layout(x + lp.leftMargin, y + lp.topMargin,
                    x + lp.leftMargin + child.getMeasuredWidth(),
                    y + lp.topMargin + child.getMeasuredHeight());
            x += w;
            lineHeight = Math.max(lineHeight, h);
        }
    }

    private static MarginLayoutParams marginsOf(View child) {
        ViewGroup.LayoutParams lp = child.getLayoutParams();
        return lp instanceof MarginLayoutParams ? (MarginLayoutParams) lp
                : new MarginLayoutParams(lp);
    }

    // Children arrive carrying LinearLayout.LayoutParams from the builders that made
    // them; those are MarginLayoutParams, so they are accepted as-is.

    @Override protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override public ViewGroup.LayoutParams generateLayoutParams(android.util.AttributeSet a) {
        return new MarginLayoutParams(getContext(), a);
    }

    @Override protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof MarginLayoutParams ? p : new MarginLayoutParams(p);
    }

    @Override protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof MarginLayoutParams;
    }
}
