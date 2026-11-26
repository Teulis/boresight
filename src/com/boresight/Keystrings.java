package com.boresight;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Samsung dialer keystrings, and a record of which ones actually did something.
 *
 * Ported from the standalone Keystrings app. Two facts drive the whole design, both
 * measured on an S25 Ultra rather than assumed:
 *
 *  - **Samsung does not use the AOSP secret-code broadcast.** Querying
 *    android.provider.Telephony.SECRET_CODE returns nothing; every receiver on the
 *    device registers under com.samsung.android.action.SECRET_CODE with scheme
 *    android_secret_code and the code as the authority. That action is also protected
 *    and signature|privileged, so this app can look receivers up but cannot fire one —
 *    dialling is the only route.
 *  - **94 of the 305 catalogued codes are addressed by a word, not digits** —
 *    MSL_Checker, CP_RAMDUMP, DEBUG and friends. They resolve, they are genuinely
 *    registered, and there is no way to type a letter on a dialpad. They are not
 *    broken; they are unreachable that way, and the list says so instead of leaving
 *    the user to discover it one dead code at a time.
 *
 * Do not Uri.encode the authority: codes like "0*" are registered literally and
 * percent-encoding breaks the match.
 */
public final class Keystrings {

    public static final String ACTION = "com.samsung.android.action.SECRET_CODE";
    public static final String SCHEME = "android_secret_code";

    private Keystrings() { }
private static final String[][] DATA = {
    {"ServiceMode (modem / RF)",
        "0011|ServiceMode - serving cell, RF, neighbour list*",
        "1575|GPS control",
        "197328640|ServiceMode main menu*",
        "2263|Band selection*",
        "27663368378|Event-based logging",
        "276633683782|Event-based logging (Wi-Fi)",
        "301279|HSDPA/HSUPA control*",
        "3214789|CP dump menu via debug screen",
        "32489|Ciphering / encryption status*",
        "58366|Force LTE on",
        "66336|CP dump menu via debug screen",
        "758353266223|Trigger CP crash dump",
        "9090|Diagnostic configuration*",
        "CP_RAMDUMP|Modem RAM dump",
        "TESTMODE|Event-based logging",
        "00112|",
        "03|",
        "1111|",
        "119|",
        "123456|",
        "1234567890|",
        "147852|",
        "1478963|",
        "2222|",
        "22553767|",
        "22632|",
        "2580|",
        "2683662|",
        "2684|",
        "2767*2878|",
        "279301|",
        "2886|",
        "33725327|",
        "369852|",
        "3698741|",
        "37375625|",
        "4238378|",
        "4387264636|",
        "5337632|",
        "548378|",
        "622|",
        "6984125*|",
        "73|",
        "738767633|",
        "73876766|",
        "7387677763|",
        "7387678378|",
        "745|",
        "746|",
        "827|",
        "827828868378|",
        "8888|"},

    {"ServiceMode (phone)",
        "0514|ServiceMode debug screen*",
        "06|IMEI*",
        "66336|CP dump menu via debug screen",
        "7284|USB / UART control",
        "9900|SysDump*",
        "22558463|",
        "272886|",
        "3214789650|",
        "66336324|",
        "773738|"},

    {"Factory keystring",
        "0228|Battery status",
        "0673|Audio test",
        "0782|Real-time clock",
        "0842|Vibration / backlight test",
        "2664|Touchscreen test",
        "001120|",
        "0212|",
        "02288|",
        "022884478|",
        "0229|",
        "0289|",
        "0588|",
        "0589|",
        "07|",
        "0775|",
        "1004|",
        "1105|",
        "1106|",
        "1470|",
        "1471|",
        "15987|",
        "159874|",
        "19242022|",
        "22228378|",
        "22235|",
        "2272|",
        "22888973|",
        "250|",
        "251|",
        "266536966|",
        "26732875|",
        "272447|",
        "27743|",
        "297277|",
        "31597|",
        "3215987|",
        "3226|",
        "32266|",
        "3264|",
        "33586|",
        "350|",
        "354537|",
        "35789|",
        "362|",
        "3736|",
        "3833|",
        "4327|",
        "435517|",
        "4378873|",
        "4562580|",
        "4747|",
        "5238973|",
        "5395377|",
        "5442|",
        "576|",
        "6070|",
        "6364366633|",
        "66867|",
        "726387|",
        "7328735824|",
        "7383773|",
        "767*2878|",
        "7676|",
        "7736|",
        "77361379|",
        "77368378|",
        "7769|",
        "77692|",
        "77694|",
        "7785|",
        "8213|",
        "87976633|",
        "88|",
        "9998*3288|"},

    {"Factory keystring  >  Display brightness",
        "42663|"},

    {"Factory keystring  >  PDP backup / restore",
        "7372288|"},

    {"Factory keystring  >  Phone utility",
        "2288379|"},

    {"Factory keystring  >  Serial number label",
        "737425|"},

    {"Factory keystring  >  Shutdown setting",
        "7594|",
        "7595|"},

    {"Factory keystring  >  Touchscreen (TSP)",
        "2663|Touchscreen firmware version*",
        "46837|",
        "80|",
        "86824|",
        "877|",
        "8778378|"},

    {"Factory keystring  >  Version info",
        "0000|Firmware / software version*",
        "1234|Firmware version detail*",
        "12580*369|Software & hardware info",
        "1111|",
        "2222|",
        "278837|",
        "4943|",
        "99732|",
        "9999|"},

    {"HiddenMenu (CDMA / legacy)",
        "FIELDTESTMODE|Field test mode*",
        "TESTMODE|Event-based logging",
        "#|",
        "4433366335623|",
        "CLEAR|",
        "DATA|",
        "DEBUG|",
        "DNSSET|",
        "FEATUREMODE|",
        "IOTHIDDENMENU|",
        "LOG|",
        "LTEMODE|",
        "MSLNAM|",
        "MSL_Checker|",
        "MSL_OTKSL|",
        "NAMBASIC|",
        "OTKSL|",
        "PROG|",
        "RTN|",
        "SERIALSET|",
        "TEST|",
        "UICC|",
        "UNLOCKKERNEL|",
        "com.samsung.operator_defined|",
        "diag_msl|",
        "setDMMODEM|",
        "setDMMODEMADB|",
        "setMASSSTORAGE|",
        "setMASSSTORAGEADB|",
        "setMTP|",
        "setMTPADB|",
        "setPTP|",
        "setPTPADB|",
        "setRMNETDMMODEM|",
        "setRNDIS|",
        "setRNDISACMDMADB|",
        "setRNDISADB|",
        "setRNDISDMMODEM|"},

    {"HiddenMenu (CDMA / legacy)  >  AKEY2",
        "AKEY|"},

    {"HiddenMenu (CDMA / legacy)  >  AKEY2 Vzw",
        "AKEY_Vzw|"},

    {"HiddenMenu (CDMA / legacy)  >  AKEY2 via",
        "AKEY_via|"},

    {"HiddenMenu (CDMA / legacy)  >  Advanced1x",
        "Advance1x_title|"},

    {"HiddenMenu (CDMA / legacy)  >  BC10 Edit",
        "BC10_title|"},

    {"HiddenMenu (CDMA / legacy)  >  BLV",
        "BLV|"},

    {"HiddenMenu (CDMA / legacy)  >  BRAND",
        "BRAND|"},

    {"HiddenMenu (CDMA / legacy)  >  CLEAR Reset",
        "CLEAR_Reset|"},

    {"HiddenMenu (CDMA / legacy)  >  DNS Set",
        "DNS_Set|"},

    {"HiddenMenu (CDMA / legacy)  >  DSA Edit",
        "DSA|"},

    {"HiddenMenu (CDMA / legacy)  >  DSA View",
        "DSA_View|"},

    {"HiddenMenu (CDMA / legacy)  >  DSSOption",
        "DSSOption|"},

    {"HiddenMenu (CDMA / legacy)  >  DTMF Set",
        "DTMF_Set|"},

    {"HiddenMenu (CDMA / legacy)  >  DUN",
        "DUN_title|"},

    {"HiddenMenu (CDMA / legacy)  >  Dataprofile Edit",
        "Dataprofile_Edit|"},

    {"HiddenMenu (CDMA / legacy)  >  Dataprofile View",
        "Username_View|"},

    {"HiddenMenu (CDMA / legacy)  >  DmMode",
        "DMMODE|"},

    {"HiddenMenu (CDMA / legacy)  >  EHRPD Edit",
        "EHRPD|"},

    {"HiddenMenu (CDMA / legacy)  >  EVRCB Edit",
        "EVRC-B|"},

    {"HiddenMenu (CDMA / legacy)  >  FIELDTESTMODE View",
        "FIELDTESTMODE_View|"},

    {"HiddenMenu (CDMA / legacy)  >  GPSCLRX",
        "GPSCLRX|"},

    {"HiddenMenu (CDMA / legacy)  >  GlobalHiddenMenuEnable",
        "GlobalHmenu|"},

    {"HiddenMenu (CDMA / legacy)  >  HDR1X Selection",
        "HDR1X_Selection|"},

    {"HiddenMenu (CDMA / legacy)  >  HDR1X Selection C1vzw",
        "HDR1X_Selection_C1vzw|"},

    {"HiddenMenu (CDMA / legacy)  >  HDR1X Selection2",
        "HDR1X_Selection2|"},

    {"HiddenMenu (CDMA / legacy)  >  Hdata edit others",
        "Others_title|"},

    {"HiddenMenu (CDMA / legacy)  >  HiddenMenuEnable",
        "HIDDENMENUENABLE|"},

    {"HiddenMenu (CDMA / legacy)  >  KOREA Mode",
        "Korea_mode_title|"},

    {"HiddenMenu (CDMA / legacy)  >  KoreaMode Prevail",
        "Korea_mode_prev_title|"},

    {"HiddenMenu (CDMA / legacy)  >  LTEMode",
        "LTEMode|"},

    {"HiddenMenu (CDMA / legacy)  >  MEIDInfo",
        "MEID|"},

    {"HiddenMenu (CDMA / legacy)  >  MMSC",
        "MMSC|"},

    {"HiddenMenu (CDMA / legacy)  >  MRD",
        "MRD|"},

    {"HiddenMenu (CDMA / legacy)  >  Multimedia Edit",
        "Multimedia|"},

    {"HiddenMenu (CDMA / legacy)  >  Multimedia View",
        "Multimedia_View|"},

    {"HiddenMenu (CDMA / legacy)  >  OTATest",
        "OTATEST|"},

    {"HiddenMenu (CDMA / legacy)  >  PhoneUtil",
        "PUTIL|"},

    {"HiddenMenu (CDMA / legacy)  >  Port Map",
        "PORTMAP|"},

    {"HiddenMenu (CDMA / legacy)  >  ProgramMenu",
        "PROGRAM|"},

    {"HiddenMenu (CDMA / legacy)  >  RC DATA",
        "RC_DATA|"},

    {"HiddenMenu (CDMA / legacy)  >  RESET",
        "RESET|"},

    {"HiddenMenu (CDMA / legacy)  >  RTN Reset",
        "RTN_Reset|"},

    {"HiddenMenu (CDMA / legacy)  >  RTN View",
        "RTN_View|"},

    {"HiddenMenu (CDMA / legacy)  >  SCRTN",
        "SCRTN|"},

    {"HiddenMenu (CDMA / legacy)  >  SO73COP0",
        "SO73COP0_title|"},

    {"HiddenMenu (CDMA / legacy)  >  SO73COP17",
        "SO73COP17_title|"},

    {"HiddenMenu (CDMA / legacy)  >  TTY",
        "TTY|"},

    {"HiddenMenu (CDMA / legacy)  >  Test",
        "Test|"},

    {"HiddenMenu (CDMA / legacy)  >  Test Edit",
        "Test_Edit|"},

    {"HiddenMenu (CDMA / legacy)  >  Test View",
        "Test_View|"},

    {"HiddenMenu (CDMA / legacy)  >  Toggle NAI",
        "Toggle_NAI|"},

    {"HiddenMenu (CDMA / legacy)  >  ToolLauncherEnabler",
        "TLAUNCHER|"},

    {"HiddenMenu (CDMA / legacy)  >  USBSettings",
        "USBSettings|"},

    {"HiddenMenu (CDMA / legacy)  >  WiMAX Menu",
        "WiMax_Menu|"},

    {"Protected factory test",
        "$$15|",
        "08|",
        "12345|",
        "2013|",
        "2015|",
        "2538378|",
        "46744674|",
        "5667|",
        "6648|",
        "7547|",
        "767*2878|",
        "775|",
        "9998*3288|"},

    {"Bluetooth  >  Bluetooth keystring",
        "232337|Bluetooth MAC address*",
        "232330|",
        "232331|",
        "533880|",
        "533881|",
        "533885|",
        "533888|"},

    {"Wi-Fi test  >  Secret code handler",
        "232338|Wi-Fi MAC address*",
        "526|Wi-Fi test",
        "232339|"},

    {"Hardware module test  >  Hardware module test",
        "0*|General hardware test menu*",
        "0437|",
        "43788731|",
        "43788732|"},

    {"Camera factory  >  Camera",
        "34971539|Camera firmware info*",
        "349715390|",
        "4562580|",
        "7412365|"},

    {"SIM lock / personalization  >  SIM lock / personalization",
        "7465625*638*|Network lock status",
        "7465625|",
        "7465625*27*|",
        "7465625*77*|",
        "7465625*782*|"},

    {"UWB test",
        "8928378|"},

    {"USB settings  >  Ultra keystring",
        "0808|USB mode / settings*"},

    {"Settings",
        "4636|Testing / phone info menu*"},

    {"IMS settings",
        "467|",
        "77467|",
        "865838378|"},

    {"Telephony UI  >  Network settings",
        "232580|",
        "47359|"},

    {"eSIM  >  Network settings",
        "3746|"},

    {"CID manager  >  Pre-configuration",
        "22623277326634424320|",
        "27262826|",
        "83052020100812173552301071192687|"},

    {"OMC agent  >  Hidden menu",
        "6628378|",
        "66283782|"},

    {"CSC / carrier config",
        "9920|"},

    {"ODA agent  >  Network settings",
        "37462277437|"},

    {"Device management",
        "66636|"},

    {"FOTA client",
        "8736364|"},

    {"Automation  >  Ultra keystring",
        "638|"},

    {"LTE functionality",
        "STEALTHMODE|"}
};
    /** One catalogued code, with whatever has been learned about it. */
    public static final class Entry {
        public String group = "", code = "", note = "";
        /** Untested, worked, or did nothing — see the RESULT_ constants. */
        public int result;
        public String receiver = "";
        public boolean resolves;

        /** Dialable means every character can be typed on a dialpad. */
        public boolean dialable() {
            for (int i = 0; i < code.length(); i++) {
                char c = code.charAt(i);
                if (!(Character.isDigit(c) || c == '*' || c == '#')) return false;
            }
            return code.length() > 0;
        }

        public String dialString() { return "*#" + code + "#"; }

        /**
         * The dialpad letters for a word code, offered as a guess.
         *
         * Samsung fires these internally, so there is no evidence the keypad
         * translation is what the receiver expects. It is labelled a guess in the UI
         * for exactly that reason.
         */
        public String keypadGuess() {
            StringBuilder b = new StringBuilder();
            for (char c : code.toUpperCase(Locale.US).toCharArray()) {
                if (Character.isDigit(c)) { b.append(c); continue; }
                if (c < 'A' || c > 'Z') continue;
                if (c <= 'C') b.append('2'); else if (c <= 'F') b.append('3');
                else if (c <= 'I') b.append('4'); else if (c <= 'L') b.append('5');
                else if (c <= 'O') b.append('6'); else if (c <= 'S') b.append('7');
                else if (c <= 'V') b.append('8'); else b.append('9');
            }
            return b.toString();
        }
    }

    public static final int UNTESTED = 0, WORKED = 1, NOTHING = 2;

    public static String resultName(int r) {
        switch (r) {
            case WORKED:  return "worked";
            case NOTHING: return "did nothing";
            default:      return "untested";
        }
    }

    /** The catalogue, with any recorded results applied. */
    public static List<Entry> all(Context c) {
        JSONObject saved = results(c);
        List<Entry> out = new ArrayList<>();
        for (String[] group : DATA) {
            for (int i = 1; i < group.length; i++) {
                String raw = group[i];
                int bar = raw.indexOf('|');
                Entry e = new Entry();
                e.group = group[0];
                e.code = bar < 0 ? raw : raw.substring(0, bar);
                e.note = bar < 0 ? "" : raw.substring(bar + 1);
                e.result = saved.optInt(e.code, UNTESTED);
                out.add(e);
            }
        }
        return out;
    }

    /**
     * Ask the package manager whether anything is listening for a code.
     *
     * Needs the <queries> entry in the manifest; without it package visibility hides
     * every result on API 30 and up and the whole catalogue reads as dead.
     */
    public static void resolve(Context c, Entry e) {
        try {
            Intent i = new Intent(ACTION, Uri.parse(SCHEME + "://" + e.code));
            List<ResolveInfo> r = c.getPackageManager().queryBroadcastReceivers(i, 0);
            e.resolves = r != null && !r.isEmpty();
            e.receiver = e.resolves && r.get(0).activityInfo != null
                    ? r.get(0).activityInfo.packageName : "";
        } catch (Throwable t) {
            e.resolves = false;
            e.receiver = "";
        }
    }

    /**
     * Put the code in the dialer, prefilled.
     *
     * ACTION_DIAL rather than ACTION_CALL: Samsung's dialer fires a keystring the
     * moment the last character is entered, so the code never needs to be placed as a
     * call, and asking for CALL_PHONE for this would be a permission we do not need.
     */
    public static void dial(Context c, Entry e) {
        String s = e.dialString();
        Intent i = new Intent(Intent.ACTION_DIAL,
                Uri.parse("tel:" + Uri.encode(s)));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    // ------------------------------------------------------------------ results

    private static final String STORE = "keystring_results";

    private static JSONObject results(Context c) {
        try { return new JSONObject(Prefs.string(c, STORE, "{}")); }
        catch (Throwable t) { return new JSONObject(); }
    }

    public static void record(Context c, String code, int result) {
        try {
            JSONObject o = results(c);
            if (result == UNTESTED) o.remove(code); else o.put(code, result);
            Prefs.get(c).edit().putString(STORE, o.toString()).apply();
        } catch (Throwable ignored) { }
    }

    public static void clearResults(Context c) {
        Prefs.get(c).edit().remove(STORE).apply();
    }

    /** Codes marked as doing nothing, as a pasteable list. */
    public static String deadList(Context c) {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Entry e : all(c)) {
            if (e.result != NOTHING) continue;
            b.append(e.dialString());
            if (!e.note.isEmpty()) b.append("   ").append(e.note);
            b.append('\n');
            n++;
        }
        return n == 0 ? "" : b.toString();
    }
}