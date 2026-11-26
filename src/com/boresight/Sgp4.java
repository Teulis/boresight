package com.boresight;

/**
 * SGP4 orbital propagator, near-Earth branch, with TLE parsing.
 *
 * This is the standard model from Spacetrack Report No. 3 as corrected by Vallado's
 * "Revisiting Spacetrack Report #3". Only the near-Earth path is implemented: it applies
 * to orbits with a period under 225 minutes, which covers Starlink's ~95 minutes with
 * room to spare. A deep-space object fed to this will produce nonsense, so
 * {@link Tle#deepSpace()} flags them and the caller is expected to skip them.
 *
 * Deliberately free of Android imports so it can be run and checked against the
 * published test vectors on a desktop before it is trusted on the phone.
 *
 * Output is TEME (true equator, mean equinox) position in km and velocity in km/s —
 * the frame SGP4 natively works in. Converting to something Earth-fixed is
 * {@link Geo}'s job.
 */
public final class Sgp4 {

    // WGS-72, which is the model the TLEs themselves are fitted against. Using WGS-84
    // here would be more "modern" and less correct.
    public static final double MU = 398600.8;
    public static final double RE = 6378.135;           // km
    public static final double XKE = 60.0 / Math.sqrt(RE * RE * RE / MU);
    public static final double J2 = 0.001082616;
    public static final double J3 = -0.00000253881;
    public static final double J4 = -0.00000165597;
    private static final double J3OJ2 = J3 / J2;
    private static final double X2O3 = 2.0 / 3.0;
    private static final double TWOPI = Math.PI * 2;

    // ------------------------------------------------------------------ TLE

    public static final class Tle {
        public String name = "";
        public int satnum;
        public String intlDesignator = "";
        /** Epoch as a Julian date. */
        public double epochJd;
        public int epochYear;
        public double epochDays;
        public double ndot, nddot, bstar;
        /** Radians, and radians per minute for the mean motion. */
        public double inclo, nodeo, ecco, argpo, mo, noKozai;
        public int revNum;

        /** Semi-major axis in km, from the mean motion. */
        public double semiMajorKm() {
            double a = Math.pow(XKE / noKozai, X2O3);
            return a * RE;
        }

        public double perigeeKm() { return semiMajorKm() * (1 - ecco) - RE; }
        public double apogeeKm()  { return semiMajorKm() * (1 + ecco) - RE; }
        public double periodMin() { return TWOPI / noKozai; }

        /** SGP4's near-Earth branch is only valid below this. */
        public boolean deepSpace() { return periodMin() >= 225.0; }

        @Override public String toString() {
            return name + " (" + satnum + ")";
        }
    }

    /**
     * Parse a two- or three-line element set. Returns null rather than throwing, since
     * a single malformed entry in a file of thousands should not stop the rest loading.
     */
    public static Tle parse(String name, String l1, String l2) {
        try {
            if (l1 == null || l2 == null) return null;
            if (l1.length() < 64 || l2.length() < 63) return null;
            if (l1.charAt(0) != '1' || l2.charAt(0) != '2') return null;

            Tle t = new Tle();
            t.name = name == null ? "" : name.trim();
            t.satnum = Integer.parseInt(l1.substring(2, 7).trim());
            t.intlDesignator = l1.substring(9, 17).trim();

            int yy = Integer.parseInt(l1.substring(18, 20).trim());
            t.epochYear = yy < 57 ? yy + 2000 : yy + 1900;
            t.epochDays = Double.parseDouble(l1.substring(20, 32).trim());
            t.epochJd = julianFromEpoch(t.epochYear, t.epochDays);

            t.ndot = Double.parseDouble(l1.substring(33, 43).trim());
            t.nddot = decimalExp(l1.substring(44, 52));
            t.bstar = decimalExp(l1.substring(53, 61));

            t.inclo = Math.toRadians(Double.parseDouble(l2.substring(8, 16).trim()));
            t.nodeo = Math.toRadians(Double.parseDouble(l2.substring(17, 25).trim()));
            t.ecco = Double.parseDouble("0." + l2.substring(26, 33).trim());
            t.argpo = Math.toRadians(Double.parseDouble(l2.substring(34, 42).trim()));
            t.mo = Math.toRadians(Double.parseDouble(l2.substring(43, 51).trim()));

            double revsPerDay = Double.parseDouble(l2.substring(52, 63).trim());
            t.noKozai = revsPerDay * TWOPI / 1440.0;      // rad/min
            try { t.revNum = Integer.parseInt(l2.substring(63, 68).trim()); }
            catch (Exception ignored) {}

            // ndot and nddot are per the TLE's own odd units; SGP4 proper does not use
            // them, but they are kept for display.
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    /** The TLE "assumed decimal point with exponent" form, e.g. " 28098-4". */
    private static double decimalExp(String s) {
        s = s.trim();
        if (s.isEmpty()) return 0;
        boolean neg = s.charAt(0) == '-';
        if (s.charAt(0) == '+' || s.charAt(0) == '-') s = s.substring(1);
        int e = 0;
        int p = Math.max(s.lastIndexOf('-'), s.lastIndexOf('+'));
        if (p > 0) {
            e = Integer.parseInt(s.substring(p).replace("+", ""));
            s = s.substring(0, p);
        }
        double mant = Double.parseDouble("0." + s.trim());
        double v = mant * Math.pow(10, e);
        return neg ? -v : v;
    }

    /** Julian date from a TLE epoch (two-digit year plus fractional day of year). */
    public static double julianFromEpoch(int year, double dayOfYear) {
        // 1 January is day 1, so the Julian date of "day 0" is 31 December of year-1.
        double jdJan0 = julian(year, 1, 1, 0, 0, 0) - 1.0;
        return jdJan0 + dayOfYear;
    }

    public static double julian(int year, int mon, int day, int hr, int min, double sec) {
        return 367.0 * year
                - Math.floor(7 * (year + Math.floor((mon + 9) / 12.0)) * 0.25)
                + Math.floor(275 * mon / 9.0)
                + day + 1721013.5
                + ((sec / 60.0 + min) / 60.0 + hr) / 24.0;
    }

    // ------------------------------------------------------------------ state

    private final Tle tle;
    private boolean ok = true;

    private double no, aycof, con41, cc1, cc4, cc5, d2, d3, d4;
    private double delmo, eta, argpdot, omgcof, sinmao, t2cof, t3cof, t4cof, t5cof;
    private double x1mth2, x7thm1, mdot, nodedot, xlcof, xmcof, nodecf;
    private boolean isimp;
    private double cosio, sinio;

    public Sgp4(Tle t) {
        tle = t;
        try { init(); } catch (Exception e) { ok = false; }
    }

    public boolean valid() { return ok && !tle.deepSpace(); }
    public Tle tle() { return tle; }

    private void init() {
        double ss = 78.0 / RE + 1.0;
        double qzms2t = Math.pow((120.0 - 78.0) / RE, 4);

        double ecco = tle.ecco, inclo = tle.inclo, argpo = tle.argpo, mo = tle.mo;
        double eccsq = ecco * ecco;
        double omeosq = 1.0 - eccsq;
        double rteosq = Math.sqrt(omeosq);
        cosio = Math.cos(inclo);
        double cosio2 = cosio * cosio;

        // Recover the unperturbed (Brouwer) mean motion from the Kozai one in the TLE.
        double ak = Math.pow(XKE / tle.noKozai, X2O3);
        double d1 = 0.75 * J2 * (3.0 * cosio2 - 1.0) / (rteosq * omeosq);
        double del = d1 / (ak * ak);
        double adel = ak * (1.0 - del * del / 3.0 - del * (1.0 / 3.0 + 134.0 * del * del / 81.0));
        del = d1 / (adel * adel);
        no = tle.noKozai / (1.0 + del);

        double ao = Math.pow(XKE / no, X2O3);
        sinio = Math.sin(inclo);
        double po = ao * omeosq;
        double con42 = 1.0 - 5.0 * cosio2;
        con41 = -con42 - cosio2 - cosio2;
        double posq = po * po;
        double rp = ao * (1.0 - ecco);

        isimp = rp < (220.0 / RE + 1.0);

        double sfour = ss;
        double qzms24 = qzms2t;
        double perige = (rp - 1.0) * RE;
        if (perige < 156.0) {
            sfour = perige - 78.0;
            if (perige < 98.0) sfour = 20.0;
            qzms24 = Math.pow((120.0 - sfour) / RE, 4.0);
            sfour = sfour / RE + 1.0;
        }
        double pinvsq = 1.0 / posq;
        double tsi = 1.0 / (ao - sfour);
        eta = ao * ecco * tsi;
        double etasq = eta * eta;
        double eeta = ecco * eta;
        double psisq = Math.abs(1.0 - etasq);
        double coef = qzms24 * Math.pow(tsi, 4.0);
        double coef1 = coef / Math.pow(psisq, 3.5);

        double cc2 = coef1 * no * (ao * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq))
                + 0.375 * J2 * tsi / psisq * con41 * (8.0 + 3.0 * etasq * (8.0 + etasq)));
        cc1 = tle.bstar * cc2;
        double cc3 = 0.0;
        if (ecco > 1.0e-4) {
            cc3 = -2.0 * coef * tsi * J3OJ2 * no * sinio / ecco;
        }
        x1mth2 = 1.0 - cosio2;
        cc4 = 2.0 * no * coef1 * ao * omeosq
                * (eta * (2.0 + 0.5 * etasq) + ecco * (0.5 + 2.0 * etasq)
                   - J2 * tsi / (ao * psisq)
                     * (-3.0 * con41 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta))
                        + 0.75 * x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq))
                          * Math.cos(2.0 * argpo)));
        cc5 = 2.0 * coef1 * ao * omeosq * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq);

        double cosio4 = cosio2 * cosio2;
        double temp1 = 1.5 * J2 * pinvsq * no;
        double temp2 = 0.5 * temp1 * J2 * pinvsq;
        double temp3 = -0.46875 * J4 * pinvsq * pinvsq * no;

        mdot = no + 0.5 * temp1 * rteosq * con41
                + 0.0625 * temp2 * rteosq * (13.0 - 78.0 * cosio2 + 137.0 * cosio4);
        argpdot = -0.5 * temp1 * con42
                + 0.0625 * temp2 * (7.0 - 114.0 * cosio2 + 395.0 * cosio4)
                + temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4);
        double xhdot1 = -temp1 * cosio;
        nodedot = xhdot1 + (0.5 * temp2 * (4.0 - 19.0 * cosio2)
                + 2.0 * temp3 * (3.0 - 7.0 * cosio2)) * cosio;

        omgcof = tle.bstar * cc3 * Math.cos(argpo);
        xmcof = 0.0;
        if (ecco > 1.0e-4) xmcof = -X2O3 * coef * tle.bstar / eeta;
        nodecf = 3.5 * omeosq * xhdot1 * cc1;
        t2cof = 1.5 * cc1;

        if (Math.abs(cosio + 1.0) > 1.5e-12) {
            xlcof = -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio);
        } else {
            xlcof = -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / 1.5e-12;
        }
        aycof = -0.5 * J3OJ2 * sinio;
        delmo = Math.pow(1.0 + eta * Math.cos(mo), 3.0);
        sinmao = Math.sin(mo);
        x7thm1 = 7.0 * cosio2 - 1.0;

        if (!isimp) {
            double cc1sq = cc1 * cc1;
            d2 = 4.0 * ao * tsi * cc1sq;
            double temp = d2 * tsi * cc1 / 3.0;
            d3 = (17.0 * ao + sfour) * temp;
            d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * cc1;
            t3cof = d2 + 2.0 * cc1sq;
            t4cof = 0.25 * (3.0 * d3 + cc1 * (12.0 * d2 + 10.0 * cc1sq));
            t5cof = 0.2 * (3.0 * d4 + 12.0 * cc1 * d3 + 6.0 * d2 * d2
                    + 15.0 * cc1sq * (2.0 * d2 + cc1sq));
        }
    }

    /** TEME position (km) and velocity (km/s). */
    public static final class State {
        public double x, y, z, vx, vy, vz;
        public boolean ok;
    }

    /** @param tsince minutes since the TLE epoch */
    public State propagate(double tsince) {
        State out = new State();
        if (!valid()) return out;

        double ecco = tle.ecco, inclo = tle.inclo;
        double xmdf = tle.mo + mdot * tsince;
        double argpdf = tle.argpo + argpdot * tsince;
        double nodedf = tle.nodeo + nodedot * tsince;
        double argpm = argpdf;
        double mm = xmdf;
        double t2 = tsince * tsince;
        double nodem = nodedf + nodecf * t2;
        double tempa = 1.0 - cc1 * tsince;
        double tempe = tle.bstar * cc4 * tsince;
        double templ = t2cof * t2;

        if (!isimp) {
            double delomg = omgcof * tsince;
            double delm = xmcof * (Math.pow(1.0 + eta * Math.cos(xmdf), 3.0) - delmo);
            double temp = delomg + delm;
            mm = xmdf + temp;
            argpm = argpdf - temp;
            double t3 = t2 * tsince;
            double t4 = t3 * tsince;
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4;
            tempe = tempe + tle.bstar * cc5 * (Math.sin(mm) - sinmao);
            templ = templ + t3cof * t3 + t4 * (t4cof + tsince * t5cof);
        }

        double nm = no;
        double em = ecco;
        double am = Math.pow(XKE / nm, X2O3) * tempa * tempa;
        nm = XKE / Math.pow(am, 1.5);
        em = em - tempe;
        if (em >= 1.0 || em < -0.001) { return out; }
        if (em < 1.0e-6) em = 1.0e-6;

        mm = mm + no * templ;
        double xlm = mm + argpm + nodem;
        nodem = mod2pi(nodem);
        argpm = mod2pi(argpm);
        xlm = mod2pi(xlm);
        mm = mod2pi(xlm - argpm - nodem);

        // Long period periodics.
        double axnl = em * Math.cos(argpm);
        double temp = 1.0 / (am * (1.0 - em * em));
        double aynl = em * Math.sin(argpm) + temp * aycof;
        double xl = mm + argpm + nodem + temp * xlcof * axnl;

        // Kepler's equation, Newton-Raphson with the classic 0.95 step clamp.
        double u = mod2pi(xl - nodem);
        double eo1 = u;
        double sineo1 = 0, coseo1 = 0;
        for (int i = 0; i < 10; i++) {
            sineo1 = Math.sin(eo1);
            coseo1 = Math.cos(eo1);
            double tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl;
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5;
            if (Math.abs(tem5) >= 0.95) tem5 = tem5 > 0.0 ? 0.95 : -0.95;
            eo1 += tem5;
            if (Math.abs(tem5) < 1.0e-12) break;
        }

        // Short period preliminaries.
        double ecose = axnl * coseo1 + aynl * sineo1;
        double esine = axnl * sineo1 - aynl * coseo1;
        double el2 = axnl * axnl + aynl * aynl;
        double pl = am * (1.0 - el2);
        if (pl < 0.0) return out;

        double rl = am * (1.0 - ecose);
        double rdotl = Math.sqrt(am) * esine / rl;
        double rvdotl = Math.sqrt(pl) / rl;
        double betal = Math.sqrt(1.0 - el2);
        temp = esine / (1.0 + betal);
        double sinu = am / rl * (sineo1 - aynl - axnl * temp);
        double cosu = am / rl * (coseo1 - axnl + aynl * temp);
        double su = Math.atan2(sinu, cosu);
        double sin2u = (cosu + cosu) * sinu;
        double cos2u = 1.0 - 2.0 * sinu * sinu;
        temp = 1.0 / pl;
        double temp1 = 0.5 * J2 * temp;
        double temp2 = temp1 * temp;

        double mrt = rl * (1.0 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u;
        su = su - 0.25 * temp2 * x7thm1 * sin2u;
        double xnode = nodem + 1.5 * temp2 * cosio * sin2u;
        double xinc = inclo + 1.5 * temp2 * cosio * sinio * cos2u;
        double mvt = rdotl - nm * temp1 * x1mth2 * sin2u / XKE;
        double rvdot = rvdotl + nm * temp1 * (x1mth2 * cos2u + 1.5 * con41) / XKE;

        // Orientation vectors.
        double sinsu = Math.sin(su), cossu = Math.cos(su);
        double snod = Math.sin(xnode), cnod = Math.cos(xnode);
        double sini = Math.sin(xinc), cosi = Math.cos(xinc);
        double xmx = -snod * cosi;
        double xmy = cnod * cosi;
        double ux = xmx * sinsu + cnod * cossu;
        double uy = xmy * sinsu + snod * cossu;
        double uz = sini * sinsu;
        double vx = xmx * cossu - cnod * sinsu;
        double vy = xmy * cossu - snod * sinsu;
        double vz = sini * cossu;

        out.x = mrt * ux * RE;
        out.y = mrt * uy * RE;
        out.z = mrt * uz * RE;
        double vk = RE * XKE / 60.0;                 // earth radii per minute -> km/s
        out.vx = (mvt * ux + rvdot * vx) * vk;
        out.vy = (mvt * uy + rvdot * vy) * vk;
        out.vz = (mvt * uz + rvdot * vz) * vk;
        out.ok = true;
        return out;
    }

    /** Propagate to a Julian date rather than an offset. */
    public State at(double jd) {
        return propagate((jd - tle.epochJd) * 1440.0);
    }

    private static double mod2pi(double x) {
        x = x % TWOPI;
        return x < 0 ? x + TWOPI : x;
    }
}
