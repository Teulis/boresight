package com.boresight;

import java.util.ArrayList;
import java.util.List;

/**
 * Estimates where a cell is transmitting from, using only sightings this phone recorded.
 *
 * Two kinds of evidence, in order of how much they are worth:
 *
 *   Timing advance. On LTE the network tells the handset how early to transmit, in units
 *   of 16·Ts. One step is c·16·Ts/2 = 78.1 m of one-way path, so a TA reading is a real
 *   ranging measurement. It is coarse and it measures path length rather than straight
 *   line, so it reads long in multipath, but it is measured rather than modelled.
 *
 *   Received power. Converting RSRP to a distance needs a propagation model and the
 *   tower's radiated power, neither of which we know. The model is fitted from TA data
 *   when there is any, and otherwise falls back to defaults the user can tune. Treat
 *   power-only estimates as an order of magnitude, not a fix.
 *
 * The solve is a damped Gauss-Newton on a local tangent plane, minimising the weighted
 * squared error between measured ranges and the distance from each sighting to the
 * candidate position. It reports a covariance ellipse, because the honest answer to
 * "where is the tower" after driving one straight road is "somewhere on this line".
 */
public final class Locate {

    /** One-way metres per LTE timing-advance step: c · 16 · Ts / 2, Ts = 1/(15000·2048). */
    public static final double TA_METRES = 78.125;

    /** Defaults when nothing can be fitted. A macro sector and a middling exponent. */
    public static final double DEFAULT_EIRP_DBM = 60.0;
    public static final double DEFAULT_EXPONENT = 3.0;

    public static class Fix {
        public double lat, lon;
        /** 1-sigma circular error, metres. */
        public double sigmaM;
        /** Covariance ellipse: semi-axes in metres and the major axis bearing, degrees true. */
        public double majorM, minorM, bearingDeg;
        /** RMS of the range residuals, metres. */
        public double rmsM;
        public int used, taUsed;
        public String method = "none";
        /** 0 to 100. Geometry, agreement and evidence type all feed this. */
        public int quality;
        public String caveat = "";
        public double eirpDbm = DEFAULT_EIRP_DBM, exponent = DEFAULT_EXPONENT;
        public boolean fitted;
        /** Mean measured range, useful as a sanity check against the ellipse. */
        public double meanRangeM;

        /**
         * False when the solution is not physically possible for a terrestrial cell —
         * the estimate landed further from the sightings than any tower could serve, or
         * the uncertainty ellipse is larger than a cell could ever be. A degenerate fit
         * still produces finite numbers, so without this the map cheerfully draws a
         * confidence ellipse spanning several states.
         */
        public boolean plausible = true;
        /** Why it was rejected, when it was. */
        public String implausible = "";

        public boolean ok() { return used >= 2 && !Double.isNaN(lat) && plausible; }
    }

    /**
     * The furthest a terrestrial cell can plausibly be from somewhere it was received.
     *
     * LTE's timing-advance field tops out around 100 km with the extended cyclic prefix,
     * and real macro cells are a small fraction of that. Anything beyond this is not a
     * distant tower, it is a failed solve.
     */
    public static final double MAX_CELL_RANGE_M = 100_000;

    /**
     * Largest uncertainty worth drawing.
     *
     * Set from real data rather than theory. With a 20 km bound, a mountain highway
     * drive still produced half a dozen overlapping ellipses 20 km across,
     * labelled around 47% — technically inside the physical limit and completely useless
     * for finding a tower. An estimate earns a pin on the map when it narrows the search
     * to something you could drive to and see. Past a few kilometres it does not, and
     * drawing it anyway makes the map worse than leaving it blank.
     */
    public static final double MAX_USEFUL_ELLIPSE_M = 5_000;

    /** A sighting reduced to what the solver needs. */
    public static class Point {
        public double lat, lon;
        public float acc;
        public int dbm = Integer.MAX_VALUE;
        public int ta = Integer.MAX_VALUE;
        public double freqMhz = Double.NaN;

        public Point() {}

        public Point(double lat, double lon, int dbm, int ta, double freqMhz, float acc) {
            this.lat = lat; this.lon = lon; this.dbm = dbm; this.ta = ta;
            this.freqMhz = freqMhz; this.acc = acc;
        }
    }

    /**
     * Split sightings into geographically separate groups before solving.
     *
     * This is not an optimisation, it is a correctness fix. A cell's identity is only
     * unique when the network gives us the full cell id; when it does not — which is the
     * normal case for neighbour cells — Sampler falls back to PCI plus EARFCN. There are
     * 504 physical cell ids, so that label repeats every few kilometres. Fitting one
     * tower to every sighting carrying it merges hundreds of distinct cells spread over
     * whole states into a single imaginary transmitter somewhere in the middle, with an
     * uncertainty ellipse to match. That is what put 500 km ellipses on the map.
     *
     * Single-linkage on distance: two sightings join the same group if they are within
     * {@code gapM} of each other, so a continuous drive past one cell stays together
     * while the same PCI seen in the next city starts a new group.
     */
    public static List<List<Point>> cluster(List<Point> pts, double gapM) {
        List<List<Point>> groups = new ArrayList<>();
        if (pts == null || pts.isEmpty()) return groups;

        int n = pts.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (find(parent, i) == find(parent, j)) continue;
                if (haversineM(pts.get(i).lat, pts.get(i).lon,
                               pts.get(j).lat, pts.get(j).lon) <= gapM) {
                    parent[find(parent, i)] = find(parent, j);
                }
            }
        }

        java.util.Map<Integer, List<Point>> by = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            List<Point> g = by.get(root);
            if (g == null) { g = new ArrayList<>(); by.put(root, g); }
            g.add(pts.get(i));
        }
        groups.addAll(by.values());
        return groups;
    }

    /**
     * Great-circle distance in metres, in plain Java.
     *
     * Deliberately not android.location.Location.distanceBetween: clustering is pure
     * geometry and the desktop test suite has to be able to run it. At these ranges the
     * two agree to a fraction of a metre.
     */
    static double haversineM(double la1, double lo1, double la2, double lo2) {
        double r = 6371008.8;
        double p1 = Math.toRadians(la1), p2 = Math.toRadians(la2);
        double dp = Math.toRadians(la2 - la1), dl = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
        return i;
    }

    /**
     * Default separation for {@link #cluster}. Comfortably past the reach of a macro
     * cell, so one cell's coverage is never split in two, and far short of the distance
     * at which a repeated PCI becomes a different cell.
     */
    public static final double CLUSTER_GAP_M = 12_000;

    // ------------------------------------------------------------------ geodesy

    static double mPerDegLat(double lat) {
        double p = Math.toRadians(lat);
        return 111132.92 - 559.82 * Math.cos(2 * p) + 1.175 * Math.cos(4 * p)
                - 0.0023 * Math.cos(6 * p);
    }

    static double mPerDegLon(double lat) {
        double p = Math.toRadians(lat);
        return 111412.84 * Math.cos(p) - 93.5 * Math.cos(3 * p) + 0.118 * Math.cos(5 * p);
    }

    // ------------------------------------------------------------------ model

    /** Free-space path loss at one metre for a frequency, in dB. */
    static double pl0(double freqMhz) {
        double f = Double.isNaN(freqMhz) || freqMhz <= 0 ? 1900 : freqMhz;
        return 20 * Math.log10(f) - 27.55;
    }

    /** Distance implied by a received level, metres. */
    static double rangeFromPower(int dbm, double freqMhz, double eirp, double n) {
        double exp = (eirp - dbm - pl0(freqMhz)) / (10 * Math.max(1.2, n));
        double d = Math.pow(10, exp);
        return Math.max(10, Math.min(60000, d));
    }

    /** Range implied by a timing advance reading, metres. */
    public static double rangeFromTa(int ta) {
        return ta * TA_METRES;
    }

    /**
     * Fit radiated power and path-loss exponent from the points that carry both a TA
     * range and an RSRP. Straight line fit of RSRP against log10(distance):
     * RSRP = EIRP - PL0 - 10n·log10(d).
     */
    private static boolean fitModel(List<Point> pts, Fix out) {
        List<double[]> xy = new ArrayList<>();
        double fsum = 0; int fn = 0;
        for (Point p : pts) {
            if (!Sampler.has(p.ta) || p.ta <= 0 || !Sampler.has(p.dbm)) continue;
            double d = rangeFromTa(p.ta);
            if (d < 50) continue;                       // a zero-ish TA tells us nothing
            xy.add(new double[]{Math.log10(d), p.dbm});
            if (!Double.isNaN(p.freqMhz) && p.freqMhz > 0) { fsum += p.freqMhz; fn++; }
        }
        if (xy.size() < 4) return false;

        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        int n = xy.size();
        for (double[] v : xy) { sx += v[0]; sy += v[1]; sxx += v[0] * v[0]; sxy += v[0] * v[1]; }
        double den = n * sxx - sx * sx;
        if (Math.abs(den) < 1e-9) return false;
        double slope = (n * sxy - sx * sy) / den;       // = -10n
        double intercept = (sy - slope * sx) / n;       // = EIRP - PL0

        double exponent = -slope / 10.0;
        if (exponent < 1.6 || exponent > 6.0) return false;   // nonsense fit, keep defaults

        double freq = fn > 0 ? fsum / fn : 1900;
        double eirp = intercept + pl0(freq);
        if (eirp < 20 || eirp > 85) return false;

        out.exponent = exponent;
        out.eirpDbm = eirp;
        out.fitted = true;
        return true;
    }

    // ------------------------------------------------------------------ solve

    public static Fix solve(List<Point> input) {
        return solve(input, DEFAULT_EIRP_DBM, DEFAULT_EXPONENT, true);
    }

    /**
     * @param eirpDbm   assumed radiated power when the model cannot be fitted
     * @param exponent  assumed path-loss exponent when the model cannot be fitted
     * @param autoFit   fit the model from timing-advance data when enough exists
     */
    public static Fix solve(List<Point> input, double eirpDbm, double exponent,
                            boolean autoFit) {
        Fix f = new Fix();
        f.lat = Double.NaN; f.lon = Double.NaN;
        f.eirpDbm = eirpDbm; f.exponent = exponent;

        List<Point> pts = new ArrayList<>();
        for (Point p : input) {
            if (Double.isNaN(p.lat) || Double.isNaN(p.lon)) continue;
            if (p.lat == 0 && p.lon == 0) continue;
            if (!Sampler.has(p.dbm) && !Sampler.has(p.ta)) continue;
            pts.add(p);
        }
        if (pts.isEmpty()) { f.caveat = "No located sightings yet."; return f; }

        if (autoFit) fitModel(pts, f);

        // Reference origin: the signal-weighted centroid of the sightings.
        double wsum = 0, lat0 = 0, lon0 = 0;
        for (Point p : pts) {
            double w = Sampler.has(p.dbm) ? Math.pow(10, p.dbm / 20.0) : 1e-6;
            wsum += w; lat0 += p.lat * w; lon0 += p.lon * w;
        }
        lat0 /= wsum; lon0 /= wsum;

        double mLat = mPerDegLat(lat0), mLon = mPerDegLon(lat0);

        // Build range observations in the local plane.
        int n = pts.size();
        double[] px = new double[n], py = new double[n], r = new double[n], w = new double[n];
        int taCount = 0, keep = 0;
        for (Point p : pts) {
            double range, weight;
            if (Sampler.has(p.ta) && p.ta > 0) {
                range = rangeFromTa(p.ta);
                // One TA step of quantisation, plus the position error.
                double sd = Math.max(TA_METRES, p.acc > 0 ? p.acc : 15);
                weight = 1.0 / (sd * sd);
                taCount++;
            } else if (Sampler.has(p.dbm)) {
                range = rangeFromPower(p.dbm, p.freqMhz, f.eirpDbm, f.exponent);
                // Log-normal shadowing of ~8 dB turns into a large fractional range error.
                double sd = Math.max(60, range * 0.55);
                weight = 1.0 / (sd * sd);
            } else {
                continue;
            }
            px[keep] = (p.lon - lon0) * mLon;
            py[keep] = (p.lat - lat0) * mLat;
            r[keep] = range;
            w[keep] = weight;
            keep++;
        }
        n = keep;
        f.used = n;
        f.taUsed = taCount;
        if (n == 0) { f.caveat = "No usable readings."; return f; }

        double rsum = 0;
        for (int i = 0; i < n; i++) rsum += r[i];
        f.meanRangeM = rsum / n;

        if (n == 1) {
            // A single sighting gives a radius, not a position. Say so plainly.
            f.lat = lat0; f.lon = lon0;
            f.sigmaM = f.majorM = f.minorM = r[0];
            f.rmsM = 0;
            f.method = taCount > 0 ? "single TA range" : "single power range";
            f.quality = 5;
            f.caveat = "One sighting only: the tower is somewhere on a circle of about "
                     + Math.round(r[0]) + " m. Move and sample again.";
            return f;
        }

        // Seed away from the strongest sighting, by its own range, so we do not start
        // exactly on a measurement where the Jacobian is degenerate.
        int strongest = 0;
        for (int i = 1; i < n; i++) if (r[i] < r[strongest]) strongest = i;
        double x = px[strongest] + r[strongest] * 0.3;
        double y = py[strongest] + r[strongest] * 0.3;

        // Damped Gauss-Newton.
        double lambda = 1e-3;
        double prev = cost(px, py, r, w, n, x, y);
        for (int iter = 0; iter < 200; iter++) {
            double a11 = 0, a12 = 0, a22 = 0, b1 = 0, b2 = 0;
            for (int i = 0; i < n; i++) {
                double dx = x - px[i], dy = y - py[i];
                double d = Math.sqrt(dx * dx + dy * dy);
                if (d < 1e-6) d = 1e-6;
                double jx = dx / d, jy = dy / d;
                double res = d - r[i];
                a11 += w[i] * jx * jx;
                a12 += w[i] * jx * jy;
                a22 += w[i] * jy * jy;
                b1  -= w[i] * jx * res;
                b2  -= w[i] * jy * res;
            }
            boolean stepped = false;
            for (int t = 0; t < 12; t++) {
                double m11 = a11 * (1 + lambda), m22 = a22 * (1 + lambda);
                double det = m11 * m22 - a12 * a12;
                if (Math.abs(det) < 1e-12) { lambda *= 10; continue; }
                double sx = ( m22 * b1 - a12 * b2) / det;
                double sy = (-a12 * b1 + m11 * b2) / det;
                double nx = x + sx, ny = y + sy;
                double c = cost(px, py, r, w, n, nx, ny);
                if (c <= prev) {
                    x = nx; y = ny; prev = c; lambda = Math.max(1e-9, lambda / 3);
                    stepped = true;
                    if (Math.hypot(sx, sy) < 0.25) { iter = 999; }
                    break;
                }
                lambda *= 10;
            }
            if (!stepped) break;
        }

        // Residuals and covariance.
        double ss = 0, chi2 = 0;
        double a11 = 0, a12 = 0, a22 = 0;
        for (int i = 0; i < n; i++) {
            double dx = x - px[i], dy = y - py[i];
            double d = Math.sqrt(dx * dx + dy * dy);
            if (d < 1e-6) d = 1e-6;
            double res = d - r[i];
            ss += res * res;                            // metres², for the RMS below
            chi2 += w[i] * res * res;                   // dimensionless
            double jx = dx / d, jy = dy / d;
            a11 += w[i] * jx * jx;
            a12 += w[i] * jx * jy;
            a22 += w[i] * jy * jy;
        }
        f.rmsM = Math.sqrt(ss / n);

        int dof = Math.max(1, n - 2);
        double det = a11 * a22 - a12 * a12;
        if (Math.abs(det) < 1e-12) det = 1e-12;

        /*
         * The weights are inverse variances, w = 1/sigma², so (J'WJ)^-1 already has
         * units of metres² and already is the covariance. The previous version then
         * multiplied it by the unweighted residual variance ss/dof, which is also
         * metres² — so the result was metres⁴, square-rooted, and reported as metres.
         *
         * That single line is where the enormous ellipses came from. On a well
         * conditioned test fit it reported 3,775 m of uncertainty for an estimate that
         * landed 30 m from truth.
         *
         * The scaling it was reaching for is the reduced chi-square, which is
         * dimensionless and inflates the ellipse honestly when the measurements
         * disagree with the model by more than their stated error bars. Clamped at 1
         * below, because a fit that happens to beat its own error bars has got lucky,
         * not become more certain than its inputs.
         */
        double chi2red = Math.max(1.0, chi2 / dof);
        double c11 =  a22 / det * chi2red;
        double c22 =  a11 / det * chi2red;
        double c12 = -a12 / det * chi2red;

        // Eigen decomposition of the 2x2 covariance for the error ellipse.
        double tr = c11 + c22, dt = c11 * c22 - c12 * c12;
        double disc = Math.max(0, tr * tr / 4 - dt);
        double e1 = tr / 2 + Math.sqrt(disc), e2 = tr / 2 - Math.sqrt(disc);
        f.majorM = Math.sqrt(Math.max(0, e1));
        f.minorM = Math.sqrt(Math.max(0, e2));
        f.sigmaM = Math.sqrt(Math.max(0, (c11 + c22) / 2));
        // Bearing of the major axis, measured clockwise from north.
        double ang = 0.5 * Math.atan2(2 * c12, c11 - c22);   // from +x (east), ccw
        f.bearingDeg = (90 - Math.toDegrees(ang) + 360) % 360;

        f.lat = lat0 + y / mLat;
        f.lon = lon0 + x / mLon;

        f.method = taCount >= 3 ? "timing advance"
                 : taCount > 0  ? "timing advance + power"
                 : "received power";

        // Quality: evidence type, how many, how well they agree, and the geometry.
        double spreadDeg = angularSpread(px, py, n, x, y);
        int q = 0;
        q += Math.min(30, n * 3);
        q += taCount >= 3 ? 30 : taCount > 0 ? 15 : 0;
        q += (int) Math.min(25, spreadDeg / 180.0 * 25);
        double relErr = f.rmsM / Math.max(50, f.meanRangeM);
        q += relErr < 0.15 ? 15 : relErr < 0.35 ? 8 : 0;

        // The ellipse has to count, and heavily. Without this a cell sampled 120 times
        // along one highway scored in the seventies on sheer sample count while its
        // cross-range uncertainty ran to hundreds of kilometres — which is exactly how
        // the map came to show tower estimates reaching from Montana to Texas.
        if (f.majorM > 3_000)       q -= 60;
        else if (f.majorM > 1_500)  q -= 40;
        else if (f.majorM > 750)    q -= 22;
        else if (f.majorM > 300)    q -= 8;
        f.quality = Math.max(1, Math.min(100, q));

        // Hard physical gates. These are not quality judgements; a fix that fails one is
        // arithmetic that went somewhere a radio cannot.
        double nearest = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            nearest = Math.min(nearest, Math.hypot(x - px[i], y - py[i]));
        }
        if (nearest > MAX_CELL_RANGE_M) {
            f.plausible = false;
            f.implausible = "The solver placed this cell "
                    + Math.round(nearest / 1000) + " km from the nearest place it was "
                    + "received. No terrestrial cell serves that far, so the fit failed.";
        } else if (f.majorM > MAX_USEFUL_ELLIPSE_M) {
            f.plausible = false;
            f.implausible = "The uncertainty ellipse is "
                    + Math.round(f.majorM / 1000) + " km across — larger than any cell. "
                    + "Sightings this close to a straight line cannot fix a position; "
                    + "sample from a road crossing this one.";
        }

        StringBuilder note = new StringBuilder();
        if (taCount == 0) {
            note.append("Power only, so the range depends on an assumed ")
                .append(f.fitted ? "fitted" : "default")
                .append(" model. Expect the distance to be wrong by a factor of two. ");
        }
        if (spreadDeg < 40) {
            note.append("All sightings lie in a ")
                .append(Math.round(spreadDeg))
                .append("-degree arc, so the fix is well constrained in range but poorly "
                      + "across it. Sample from a road at right angles to tighten it. ");
        }
        if (f.majorM > 2000) note.append("The ellipse is kilometres wide: this is a hint, not a fix. ");
        f.caveat = note.toString().trim();
        return f;
    }

    private static double cost(double[] px, double[] py, double[] r, double[] w, int n,
                               double x, double y) {
        double s = 0;
        for (int i = 0; i < n; i++) {
            double d = Math.hypot(x - px[i], y - py[i]);
            double res = d - r[i];
            s += w[i] * res * res;
        }
        return s;
    }

    /**
     * How much of the compass the sightings cover as seen from the estimate. Small means
     * we drove one line and the cross-range is guesswork.
     */
    private static double angularSpread(double[] px, double[] py, int n, double x, double y) {
        if (n < 2) return 0;
        double[] a = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = Math.toDegrees(Math.atan2(py[i] - y, px[i] - x));
            if (a[i] < 0) a[i] += 360;
        }
        java.util.Arrays.sort(a, 0, n);
        double biggestGap = a[0] + 360 - a[n - 1];
        for (int i = 1; i < n; i++) biggestGap = Math.max(biggestGap, a[i] - a[i - 1]);
        return Math.max(0, 360 - biggestGap);
    }

    // ------------------------------------------------------------------ bearing

    /**
     * Where to point, from a position toward a fix. Useful for aiming a panel.
     */
    public static double bearingTo(double fromLat, double fromLon, double toLat, double toLon) {
        double p1 = Math.toRadians(fromLat), p2 = Math.toRadians(toLat);
        double dl = Math.toRadians(toLon - fromLon);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    public static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        float[] out = new float[1];
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, out);
        return out[0];
    }

    public static String compass(double bearing) {
        String[] pts = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        return pts[(int) Math.round(((bearing % 360) / 22.5)) % 16];
    }

    /** Collect the solver's inputs for one identity straight out of the log. */
    public static List<Point> pointsFor(Store store, String ident, int limit) {
        List<Point> out = new ArrayList<>();
        for (Store.Obs o : store.historyOf(ident, limit)) {
            if (!o.located()) continue;
            Point p = new Point();
            p.lat = o.lat; p.lon = o.lon; p.acc = o.acc;
            p.dbm = o.dbm; p.ta = o.ta;
            p.freqMhz = o.dlMhz;
            if (Double.isNaN(p.freqMhz) && Sampler.has(o.arfcn)) {
                p.freqMhz = "NR".equals(o.tech)
                        ? Rf.nrMhz(o.arfcn)
                        : Rf.lteMhz(Sampler.has(o.band) ? o.band : Rf.lteBandFor(o.arfcn), o.arfcn);
            }
            out.add(p);
        }
        return out;
    }

    private Locate() {}
}
