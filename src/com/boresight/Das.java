package com.boresight;

/**
 * The band families that matter for in-building DAS work.
 *
 * A survey phone sees everything the modem can find — NR, 5 GHz TDD, whatever the
 * macro network happens to be running. On a DAS commissioning walk almost none of that
 * is relevant: the remotes carry LTE on the four low and mid band families, and the
 * list is only useful if it is the list of those.
 *
 * Grouping by family rather than by band number is the point. B12, B13, B17 and B29
 * are four numbers for the same 700 MHz hardware port, and an installer reading a
 * sheet wants to see "700" against them, not to remember which operator uses which.
 */
public final class Das {

    public static final int OTHER    = 0;
    public static final int B700     = 1;
    public static final int CELL     = 2;   // 800/850 cellular
    public static final int PCS      = 3;   // 1900
    public static final int AWS      = 4;   // 1700/2100
    public static final int FIRSTNET = 5;   // AT&T band 14

    /** FirstNet's own network code. AT&T's commercial PLMNs are 310-410 and friends. */
    public static final String FIRSTNET_MCC = "313";
    public static final String FIRSTNET_MNC = "100";

    /**
     * Default acceptance criterion, taken from a real in-building design document:
     * "-95 dBm or better through 95% or greater of the building area". The same pair
     * appears in IFC 510 and NFPA 1225 for public-safety coverage. Both numbers are
     * adjustable — the edition and the authority having jurisdiction both move them —
     * but these are the ones worth starting from.
     */
    public static final int DEFAULT_RSRP_TARGET = -95;
    public static final int DEFAULT_COVERAGE_PCT = 95;

    private Das() { }

    /**
     * FirstNet, which is band 14 on AT&T's core under its own PLMN.
     *
     * Treated as its own family rather than folded into 700 because on a DAS sheet it
     * is usually its own port and its own acceptance criteria, even though the
     * hardware shares the low-band path. Band 14 alone is enough to identify it —
     * nobody else holds that spectrum — so the PLMN is a confirmation, not a
     * requirement, which matters because neighbour measurements often carry no PLMN.
     */
    public static boolean isFirstNet(String tech, int band, String mcc, String mnc) {
        if (!"LTE".equals(tech) && !"NR".equals(tech)) return false;
        if (Sampler.has(band) && band == 14) return true;
        return FIRSTNET_MCC.equals(mcc) && FIRSTNET_MNC.equals(mnc);
    }

    /** Which DAS family a cell belongs to, with FirstNet split out. */
    public static int family(String tech, int band, String mcc, String mnc) {
        if (isFirstNet(tech, band, mcc, mnc)) return FIRSTNET;
        return family(tech, band);
    }

    /**
     * Which DAS family a band belongs to, or OTHER.
     *
     * LTE *and* NR, which is not what it first looked like. A real design can carry
     * T-Mobile on "B25 (5G)" and AT&T on B25 — the same band, the same remote, one
     * carrier running NR on it. Filtering to LTE alone would quietly drop half the
     * system on a site like that, so the technology test is "is it a 3GPP cellular
     * band on a DAS family", not "is it LTE".
     *
     * Band numbering is shared between LTE and NR for these families (n25 is 1900,
     * n66 is AWS-3), so one table serves both.
     */
    public static int family(String tech, int band) {
        boolean cellular = "LTE".equals(tech) || "NR".equals(tech);
        if (!cellular || !Sampler.has(band)) return OTHER;
        switch (band) {
            // 700 MHz: lower A/B/C (12, 17), upper C (13), FirstNet (14),
            // lower D/E supplemental downlink (29), and 85. Band 14 only lands here
            // when the caller did not ask for the FirstNet split.
            case 12: case 13: case 14: case 17: case 29: case 85:
                return B700;
            // Cellular 800/850: band 5, extended 850 (26), and 27.
            case 5: case 26: case 27:
                return CELL;
            // PCS 1900: band 2 and the G-block superset 25.
            case 2: case 25:
                return PCS;
            // AWS: AWS-1 (4), AWS-3 extended (66), AWS-4 (70).
            case 4: case 66: case 70:
                return AWS;
            default:
                return OTHER;
        }
    }

    public static boolean isDas(String tech, int band) {
        return family(tech, band) != OTHER;
    }

    public static boolean isDas(String tech, int band, String mcc, String mnc) {
        return family(tech, band, mcc, mnc) != OTHER;
    }

    public static String name(int family) {
        switch (family) {
            case B700: return "700";
            case CELL: return "Cellular 850";
            case PCS:  return "PCS 1900";
            case AWS:  return "AWS";
            case FIRSTNET: return "FirstNet (B14)";
            default:   return "other";
        }
    }

    /** Short label for a chip or a table cell. */
    public static String shortName(int family) {
        switch (family) {
            case B700: return "700";
            case CELL: return "850";
            case PCS:  return "PCS";
            case AWS:  return "AWS";
            case FIRSTNET: return "FN";
            default:   return "—";
        }
    }

    /** Sort order that puts the families in the order a sweep sheet lists them. */
    public static int order(int family) {
        switch (family) {
            case B700:     return 0;
            case FIRSTNET: return 1;
            case CELL:     return 2;
            case PCS:      return 3;
            case AWS:      return 4;
            default:       return 5;
        }
    }
}
