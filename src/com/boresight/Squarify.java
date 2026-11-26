package com.boresight;

/**
 * The squarified treemap layout, kept free of Android types so it can be reasoned
 * about and tested on its own.
 *
 * Splitting it out of the view is not tidiness for its own sake. A treemap that is
 * subtly wrong — overlapping rectangles, areas that do not match the sizes — still
 * draws something plausible, so the failure mode is a confident picture that lies.
 * Separating the arithmetic from the canvas means it can be checked against the
 * invariants that actually matter: areas proportional to sizes, nothing overlapping,
 * everything inside the bounds.
 *
 * Algorithm: Bruls, Huizing and van Wijk, "Squarified Treemaps" (2000). Items are laid
 * into rows across the shorter side of the remaining space, and a row stops accepting
 * items the moment adding one more would make its worst aspect ratio worse.
 */
public final class Squarify {

    /** x, y, w, h for each input item, in the same order. */
    public static float[][] layout(long[] sizes, float x, float y, float w, float h) {
        float[][] out = new float[sizes.length][4];
        if (sizes.length == 0 || w <= 0 || h <= 0) return out;

        long total = 0;
        for (long s : sizes) total += Math.max(0, s);
        if (total <= 0) return out;

        // Index order, largest first. The algorithm assumes a descending sequence;
        // feeding it unsorted input produces valid but ugly layouts.
        Integer[] order = new Integer[sizes.length];
        for (int i = 0; i < sizes.length; i++) order[i] = i;
        java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                return Long.compare(sizes[b], sizes[a]);
            }
        });

        double areaPer = (double) w * h / total;
        float left = x, top = y, right = x + w, bottom = y + h;

        int i = 0;
        while (i < order.length) {
            float fw = right - left, fh = bottom - top;
            if (fw <= 0.01f || fh <= 0.01f) break;

            boolean vertical = fw >= fh;             // rows run down the left edge
            double shortSide = vertical ? fh : fw;

            int start = i;
            double rowArea = 0;
            double worst = Double.MAX_VALUE;

            while (i < order.length) {
                double a = Math.max(0, sizes[order[i]]) * areaPer;
                if (a <= 0) { i++; continue; }
                double candidate = worstRatio(sizes, order, start, i, rowArea, a,
                                              areaPer, shortSide);
                if (i > start && candidate > worst) break;
                rowArea += a;
                worst = candidate;
                i++;
            }
            if (i == start) { i++; continue; }

            double thickness = rowArea / shortSide;
            double along = vertical ? top : left;
            for (int k = start; k < i; k++) {
                double a = Math.max(0, sizes[order[k]]) * areaPer;
                double len = thickness > 0 ? a / thickness : 0;
                float[] r = out[order[k]];
                if (vertical) {
                    r[0] = left;               r[1] = (float) along;
                    r[2] = (float) thickness;  r[3] = (float) len;
                } else {
                    r[0] = (float) along;      r[1] = top;
                    r[2] = (float) len;        r[3] = (float) thickness;
                }
                along += len;
            }
            if (vertical) left += (float) thickness; else top += (float) thickness;
        }
        return out;
    }

    /**
     * Worst aspect ratio of the row [start, end) once an item of area {@code add}
     * joins it. Lower is squarer.
     */
    private static double worstRatio(long[] sizes, Integer[] order, int start, int end,
                                     double rowArea, double add, double areaPer,
                                     double shortSide) {
        double area = rowArea + add;
        if (area <= 0 || shortSide <= 0) return Double.MAX_VALUE;

        double min = add, max = add;
        for (int k = start; k < end; k++) {
            double a = Math.max(0, sizes[order[k]]) * areaPer;
            if (a <= 0) continue;
            if (a < min) min = a;
            if (a > max) max = a;
        }
        if (min <= 0) return Double.MAX_VALUE;

        double side2 = shortSide * shortSide;
        double area2 = area * area;
        return Math.max(side2 * max / area2, area2 / (side2 * min));
    }

    private Squarify() { }
}
