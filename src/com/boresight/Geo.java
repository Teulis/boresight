package com.boresight;

/**
 * The geometry between an orbit and a person standing on the ground.
 *
 * SGP4 produces TEME coordinates, which are inertial — they do not turn with the Earth.
 * Everything here exists to get from there to "where should I look, and how fast is it
 * moving away from me": TEME to Earth-fixed via sidereal time, Earth-fixed to geodetic
 * latitude and longitude, and finally to topocentric azimuth, elevation and range rate.
 *
 * Polar motion is ignored. It is a few milliarcseconds, which is nothing next to the
 * kilometre-scale error already present in a TLE.
 *
 * No Android imports, so it can be checked on a desktop.
 */
public final class Geo {

    /** WGS-84, used for the ground station and sub-satellite point. */
    public static final double WGS84_A = 6378.137;             // km
    public static final double WGS84_F = 1.0 / 298.257223563;
    public static final double WGS84_E2 = WGS84_F * (2 - WGS84_F);
    /** Earth rotation rate, rad/s. */
    public static final double OMEGA_E = 7.292115e-5;
    public static final double C_KM_S = 299792.458;

    private static final double TWOPI = Math.PI * 2;

    // ------------------------------------------------------------------ time

    /** Greenwich mean sidereal time in radians, IAU 1982. */
    public static double gmst(double jdUt1) {
        double t = (jdUt1 - 2451545.0) / 36525.0;
        double s = -6.2e-6 * t * t * t
                 + 0.093104 * t * t
                 + (876600.0 * 3600.0 + 8640184.812866) * t
                 + 67310.54841;
        // seconds of time -> radians
        s = Math.toRadians((s % 86400.0) / 240.0);
        return s < 0 ? s + TWOPI : s % TWOPI;
    }

    public static double julianFromMillis(long epochMillis) {
        return epochMillis / 86400000.0 + 2440587.5;
    }

    public static long millisFromJulian(double jd) {
        return Math.round((jd - 2440587.5) * 86400000.0);
    }

    // ------------------------------------------------------------------ frames

    public static final class Vec {
        public double x, y, z;
        public Vec() {}
        public Vec(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        public double norm() { return Math.sqrt(x * x + y * y + z * z); }
    }

    /** Earth-fixed position and velocity, km and km/s. */
    public static final class Ecef {
        public final Vec r = new Vec();
        public final Vec v = new Vec();
    }

    /**
     * TEME to Earth-fixed. A rotation about Z by the sidereal angle, with the velocity
     * corrected for the fact that the new frame is itself turning.
     */
    public static Ecef temeToEcef(Sgp4.State s, double jd) {
        double g = gmst(jd);
        double cg = Math.cos(g), sg = Math.sin(g);
        Ecef e = new Ecef();
        e.r.x =  s.x * cg + s.y * sg;
        e.r.y = -s.x * sg + s.y * cg;
        e.r.z =  s.z;
        e.v.x =  s.vx * cg + s.vy * sg + OMEGA_E * e.r.y;
        e.v.y = -s.vx * sg + s.vy * cg - OMEGA_E * e.r.x;
        e.v.z =  s.vz;
        return e;
    }

    /** Geodetic position of a ground point, in Earth-fixed km. */
    public static Vec geodeticToEcef(double latDeg, double lonDeg, double altKm) {
        double lat = Math.toRadians(latDeg), lon = Math.toRadians(lonDeg);
        double sLat = Math.sin(lat), cLat = Math.cos(lat);
        double n = WGS84_A / Math.sqrt(1 - WGS84_E2 * sLat * sLat);
        return new Vec(
                (n + altKm) * cLat * Math.cos(lon),
                (n + altKm) * cLat * Math.sin(lon),
                (n * (1 - WGS84_E2) + altKm) * sLat);
    }

    public static final class Geodetic {
        public double latDeg, lonDeg, altKm;
    }

    /** Earth-fixed to geodetic. Iterative, converges in a few passes at these altitudes. */
    public static Geodetic ecefToGeodetic(Vec r) {
        Geodetic g = new Geodetic();
        double p = Math.sqrt(r.x * r.x + r.y * r.y);
        g.lonDeg = Math.toDegrees(Math.atan2(r.y, r.x));
        double lat = Math.atan2(r.z, p * (1 - WGS84_E2));
        double n = WGS84_A;
        for (int i = 0; i < 8; i++) {
            double s = Math.sin(lat);
            n = WGS84_A / Math.sqrt(1 - WGS84_E2 * s * s);
            double alt = p / Math.cos(lat) - n;
            double next = Math.atan2(r.z, p * (1 - WGS84_E2 * n / (n + alt)));
            if (Math.abs(next - lat) < 1e-12) { lat = next; break; }
            lat = next;
        }
        double s = Math.sin(lat);
        n = WGS84_A / Math.sqrt(1 - WGS84_E2 * s * s);
        g.altKm = p / Math.cos(lat) - n;
        g.latDeg = Math.toDegrees(lat);
        return g;
    }

    // ------------------------------------------------------------------ look angles

    public static final class Look {
        /** Degrees. Elevation is negative below the horizon. */
        public double azimuth, elevation;
        /** Kilometres, and km/s positive when receding. */
        public double range, rangeRate;
        public boolean up() { return elevation > 0; }

        /** Doppler shift in Hz for a transmitted frequency, negative when receding. */
        public double dopplerHz(double carrierHz) {
            return -carrierHz * rangeRate / C_KM_S;
        }
    }

    /**
     * Where to point, from a ground station to a satellite, both in Earth-fixed km.
     * Range rate comes out of the same geometry, which is what Doppler needs.
     */
    public static Look look(Vec obs, double obsLatDeg, double obsLonDeg, Ecef sat) {
        double rx = sat.r.x - obs.x, ry = sat.r.y - obs.y, rz = sat.r.z - obs.z;

        double lat = Math.toRadians(obsLatDeg), lon = Math.toRadians(obsLonDeg);
        double sLat = Math.sin(lat), cLat = Math.cos(lat);
        double sLon = Math.sin(lon), cLon = Math.cos(lon);

        // South, East, Zenith.
        double s = sLat * cLon * rx + sLat * sLon * ry - cLat * rz;
        double e = -sLon * rx + cLon * ry;
        double z = cLat * cLon * rx + cLat * sLon * ry + sLat * rz;

        Look out = new Look();
        out.range = Math.sqrt(rx * rx + ry * ry + rz * rz);
        out.elevation = Math.toDegrees(Math.asin(z / out.range));
        double az = Math.toDegrees(Math.atan2(e, -s));
        out.azimuth = az < 0 ? az + 360 : az;
        // The ground station is stationary in this frame, so the relative velocity is
        // simply the satellite's.
        out.rangeRate = (rx * sat.v.x + ry * sat.v.y + rz * sat.v.z) / out.range;
        return out;
    }

    /** Convenience: propagate and reduce to look angles in one step. */
    public static Look lookAt(Sgp4 sgp4, double jd, Vec obsEcef,
                              double obsLatDeg, double obsLonDeg) {
        Sgp4.State st = sgp4.at(jd);
        if (!st.ok) return null;
        return look(obsEcef, obsLatDeg, obsLonDeg, temeToEcef(st, jd));
    }

    /** Where the satellite is directly overhead. */
    public static Geodetic subPoint(Sgp4 sgp4, double jd) {
        Sgp4.State st = sgp4.at(jd);
        if (!st.ok) return null;
        return ecefToGeodetic(temeToEcef(st, jd).r);
    }

    // ------------------------------------------------------------------ passes

    public static final class Pass {
        public double riseJd, peakJd, setJd;
        public double peakElevation, riseAz, peakAz, setAz;
        public double minRangeKm;
        public Sgp4.Tle tle;

        public long riseMillis() { return millisFromJulian(riseJd); }
        public long peakMillis() { return millisFromJulian(peakJd); }
        public long setMillis()  { return millisFromJulian(setJd); }
        public double durationMin() { return (setJd - riseJd) * 1440.0; }
    }

    /**
     * Find passes above a minimum elevation within a window.
     *
     * Coarse stepping to find a crossing, then bisection to tighten the rise and set
     * times. A low-orbit satellite crosses the sky in minutes, so the coarse step has to
     * stay well under that or passes are missed entirely.
     *
     * @param stepSec coarse search step; 20 s is safe for a 350 km orbit
     */
    public static java.util.List<Pass> passes(Sgp4 sgp4, Vec obsEcef,
                                              double obsLat, double obsLon,
                                              double startJd, double hours,
                                              double minElevationDeg, double stepSec) {
        java.util.List<Pass> out = new java.util.ArrayList<>();
        if (!sgp4.valid()) return out;

        double step = stepSec / 86400.0;
        double endJd = startJd + hours / 24.0;

        boolean wasUp = false;
        double riseJd = 0, peakEl = -90, peakJd = 0;

        for (double jd = startJd; jd <= endJd; jd += step) {
            Look l = lookAt(sgp4, jd, obsEcef, obsLat, obsLon);
            if (l == null) continue;
            boolean isUp = l.elevation >= minElevationDeg;

            if (isUp && !wasUp) {
                riseJd = refine(sgp4, obsEcef, obsLat, obsLon, jd - step, jd, minElevationDeg);
                peakEl = l.elevation;
                peakJd = jd;
            } else if (isUp) {
                if (l.elevation > peakEl) { peakEl = l.elevation; peakJd = jd; }
            } else if (wasUp) {
                double setJd = refine(sgp4, obsEcef, obsLat, obsLon, jd - step, jd, minElevationDeg);
                Pass p = new Pass();
                p.tle = sgp4.tle();
                p.riseJd = riseJd;
                p.setJd = setJd;
                p.peakJd = peakJd;
                p.peakElevation = peakEl;
                Look r = lookAt(sgp4, riseJd, obsEcef, obsLat, obsLon);
                Look k = lookAt(sgp4, peakJd, obsEcef, obsLat, obsLon);
                Look s = lookAt(sgp4, setJd, obsEcef, obsLat, obsLon);
                if (r != null) p.riseAz = r.azimuth;
                if (k != null) { p.peakAz = k.azimuth; p.minRangeKm = k.range; }
                if (s != null) p.setAz = s.azimuth;
                out.add(p);
                peakEl = -90;
            }
            wasUp = isUp;
        }
        return out;
    }

    /**
     * Bisect for the moment the elevation crosses the threshold.
     *
     * The bracket runs below-to-above at a rise and above-to-below at a set, so the
     * direction is taken from the start of the interval rather than assumed.
     */
    private static double refine(Sgp4 sgp4, Vec obs, double lat, double lon,
                                 double a, double b, double minEl) {
        Look first = lookAt(sgp4, a, obs, lat, lon);
        boolean risingEdge = first == null || first.elevation < minEl;
        for (int i = 0; i < 24; i++) {
            double m = (a + b) / 2;
            Look l = lookAt(sgp4, m, obs, lat, lon);
            if (l == null) return m;
            boolean above = l.elevation >= minEl;
            // Keep the endpoint that still straddles the crossing.
            if (above == risingEdge) b = m; else a = m;
        }
        return (a + b) / 2;
    }

    private Geo() {}
}
