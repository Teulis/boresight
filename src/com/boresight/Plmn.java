package com.boresight;

import java.util.HashMap;
import java.util.Map;

/** MCC+MNC to a name worth reading. US-heavy, since that is where this gets used. */
public final class Plmn {

    private static final Map<String, String> M = new HashMap<>();

    static {
        // T-Mobile and its satellite PLMN
        put("T-Mobile", "310260", "310240", "310160", "310200", "310210", "310220",
                "310230", "310250", "310270", "310300", "310310", "310660", "310800",
                "311882", "311883");
        put("T-Satellite", "310830");

        put("AT&T", "310410", "310280", "311180", "310150", "310170", "310380",
                "310560", "310680", "310980", "313100");
        put("Verizon", "311480", "310012", "311270", "310004", "310010", "311271",
                "311272", "311273", "311274", "311275", "311276", "311277", "311278",
                "311279", "311280", "311281", "311282", "311283", "311284", "311285",
                "311286", "311287", "311288", "311289", "311390");
        put("Dish", "312530", "313340", "310110");
        put("UScellular", "311220", "310730", "311580", "310320");
        put("Cellcom", "311090", "310010", "312170", "310053");
        put("Union Wireless", "311860");
        put("Pine Cellular", "310770");
        put("Custer Telephone", "312040");
        put("Indigo Wireless", "311030");
        put("Carolina West", "310560");
        put("GCI", "311370", "310430");
        put("Appalachian Wireless", "312300");
        put("Bluegrass Cellular", "311440");
        put("Nex-Tech", "310120");
        put("SRT Communications", "310680");
        put("Viaero", "311480");
        put("Commnet", "310400");

        // Canada and Mexico, for anyone near the border
        put("Rogers", "302720");
        put("Bell", "302610");
        put("Telus", "302220");
        put("Telcel", "334020");
        put("AT&T Mexico", "334050", "334090");
        put("Movistar MX", "334030");
    }

    private static void put(String name, String... plmns) {
        for (String p : plmns) if (!M.containsKey(p)) M.put(p, name);
    }

    /** Name for a PLMN, or null when we have no entry. */
    public static String name(String plmn) {
        return plmn == null ? null : M.get(plmn);
    }

    /** True when this is the T-Mobile satellite network. */
    public static boolean satellite(String plmn) {
        return "310830".equals(plmn);
    }

    private Plmn() {}
}
