package com.boresight;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;

import java.io.File;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * What a banking app sees when it looks at this phone.
 *
 * Two kinds of evidence here, and the difference is the whole point.
 *
 * System properties — verified boot state, the Knox warranty bit, build tags — are
 * easy to read and easy to lie about. Anything with root can set them. They are shown
 * because they are informative on an honest device, and labelled for what they are.
 *
 * Hardware key attestation is the other kind. The app asks the keystore to generate a
 * key and sign a statement about the device, and that statement is signed by a key
 * burned into the secure element at manufacture and chained to Google's root. Nothing
 * running in Android can forge it, which is exactly why Play Integrity and every
 * serious banking app lean on it rather than on properties. If attestation says the
 * bootloader is unlocked, it is unlocked.
 *
 * This class does not pass judgement on whether the device "should" run anything. It
 * reports what the mechanisms report.
 */
public final class Integrity {

    public static final class Fact {
        public final String name, value, note;
        /** 1 good, 0 neutral, -1 a thing an integrity check would object to. */
        public final int grade;
        Fact(String n, String v, int g, String note) {
            this.name = n; this.value = v; this.grade = g; this.note = note;
        }
    }

    private Integrity() { }

    // ------------------------------------------------------------------ properties

    public static List<Fact> boot(Context c) {
        List<Fact> out = new ArrayList<>();
        String[] p = props(c,
                "ro.boot.verifiedbootstate", "ro.boot.flash.locked",
                "ro.boot.vbmeta.device_state", "ro.boot.veritymode",
                "ro.boot.warranty_bit", "ro.build.tags", "ro.debuggable",
                "ro.secure", "ro.adb.secure", "ro.boot.bootloader",
                "ro.config.iccc_version", "knox.kg.state");

        String vbs = p[0];
        out.add(new Fact("Verified boot", vbs.isEmpty() ? "not reported" : vbs,
                "green".equals(vbs) ? 1 : vbs.isEmpty() ? 0 : -1,
                "green means the boot chain was signed by the manufacturer and "
              + "unmodified. yellow is a custom key, orange is an unlocked "
              + "bootloader, red is a verification failure."));

        out.add(new Fact("Bootloader", "1".equals(p[1]) ? "locked" : p[1].isEmpty()
                        ? "not reported" : "UNLOCKED",
                "1".equals(p[1]) ? 1 : p[1].isEmpty() ? 0 : -1,
                "Unlocking is the single thing most banking apps refuse outright, and "
              + "on Samsung it is one-way: the Knox bit below trips permanently."));

        out.add(new Fact("vbmeta state", p[2].isEmpty() ? "not reported" : p[2],
                "locked".equals(p[2]) ? 1 : p[2].isEmpty() ? 0 : -1,
                "The verified boot metadata partition's own view of lock state."));

        out.add(new Fact("dm-verity", p[3].isEmpty() ? "not reported" : p[3],
                "enforcing".equals(p[3]) ? 1 : p[3].isEmpty() ? 0 : -1,
                "enforcing means the system partition is checked block by block at "
              + "read time, so a modified system image fails rather than boots."));

        out.add(new Fact("Knox warranty bit", "0".equals(p[4]) ? "0 — intact"
                        : p[4].isEmpty() ? "not reported" : p[4] + " — TRIPPED",
                "0".equals(p[4]) ? 1 : p[4].isEmpty() ? 0 : -1,
                "An eFuse. Flashing unofficial firmware burns it and it can never be "
              + "reset — not by Samsung, not by reflashing stock. Once tripped, Knox, "
              + "Secure Folder, Samsung Pay and Samsung Health's secure parts are gone "
              + "for the life of the device."));

        out.add(new Fact("Build signed with", p[5].isEmpty() ? "not reported" : p[5],
                "release-keys".equals(p[5]) ? 1 : p[5].isEmpty() ? 0 : -1,
                "release-keys is a production build. test-keys means it was signed "
              + "with the public AOSP keys, which anyone has."));

        out.add(new Fact("Debuggable build", "0".equals(p[6]) ? "no" : "YES",
                "0".equals(p[6]) ? 1 : -1,
                "A userdebug or eng build lets anything become root."));

        if (!p[9].isEmpty()) out.add(new Fact("Bootloader version", p[9], 0, ""));
        if (!p[10].isEmpty()) out.add(new Fact("ICCC version", p[10], 0,
                "Samsung's integrity control chain, the Knox-era secure boot layer."));
        if (!p[11].isEmpty()) out.add(new Fact("Knox Guard", p[11], 0,
                "Carrier remote-lock state. Completed means not under Knox Guard "
              + "control."));
        return out;
    }

    /** Several getprop reads in one shell call. */
    private static String[] props(Context c, String... names) {
        StringBuilder cmd = new StringBuilder();
        for (String n : names) cmd.append("getprop ").append(n).append("; ");
        String[] out = new String[names.length];
        for (int i = 0; i < out.length; i++) out[i] = "";
        Shell.Result r = Shell.get(c).exec(cmd.toString(), 10);
        if (r.ran && r.out != null) {
            String[] lines = r.out.split("\n", -1);
            for (int i = 0; i < out.length && i < lines.length; i++) {
                out[i] = lines[i].trim();
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ keystore

    public static final class Attestation {
        public boolean ran;
        public boolean hardwareBacked;
        public boolean strongBox;
        public int chainLength;
        public String securityLevel = "unknown";
        public String rootIssuer = "";
        public String error;
    }

    /**
     * Ask the secure element to attest to itself.
     *
     * A key is generated with an attestation challenge and then thrown away. What
     * comes back is a certificate chain: the leaf carries a signed description of the
     * device — boot state, lock state, OS patch level — and chains up to a root
     * Google published. The chain is the evidence; a device with no secure element
     * returns a single self-signed certificate and nothing worth trusting.
     *
     * The challenge is random because a fixed one would let a previous response be
     * replayed. This app only looks at the shape of the answer, but doing it properly
     * costs nothing and doing it wrong teaches the wrong lesson.
     */
    public static Attestation attest(Context c, boolean preferStrongBox) {
        Attestation a = new Attestation();
        final String alias = "boresight.attest.probe";
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            try { ks.deleteEntry(alias); } catch (Throwable ignored) { }

            byte[] challenge = new byte[32];
            new java.security.SecureRandom().nextBytes(challenge);

            KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(
                    alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(
                            new java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(challenge);
            if (preferStrongBox && Build.VERSION.SDK_INT >= 28) {
                b.setIsStrongBoxBacked(true);
            }

            KeyPairGenerator g = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            g.initialize(b.build());
            g.generateKeyPair();

            Certificate[] chain = ks.getCertificateChain(alias);
            a.chainLength = chain == null ? 0 : chain.length;
            if (chain != null && chain.length > 0) {
                Certificate root = chain[chain.length - 1];
                if (root instanceof java.security.cert.X509Certificate) {
                    a.rootIssuer = readableDn(((java.security.cert.X509Certificate) root)
                            .getIssuerX500Principal().getName());
                }
            }

            PrivateKey pk = (PrivateKey) ks.getKey(alias, null);
            KeyFactory kf = KeyFactory.getInstance(pk.getAlgorithm(), "AndroidKeyStore");
            KeyInfo info = kf.getKeySpec(pk, KeyInfo.class);
            if (Build.VERSION.SDK_INT >= 31) {
                int lvl = info.getSecurityLevel();
                a.securityLevel = levelName(lvl);
                a.hardwareBacked = lvl == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
                                || lvl == KeyProperties.SECURITY_LEVEL_STRONGBOX;
                a.strongBox = lvl == KeyProperties.SECURITY_LEVEL_STRONGBOX;
            } else {
                a.hardwareBacked = info.isInsideSecureHardware();
                a.securityLevel = a.hardwareBacked ? "secure hardware" : "software";
                a.strongBox = preferStrongBox;
            }
            a.ran = true;
            try { ks.deleteEntry(alias); } catch (Throwable ignored) { }
        } catch (Throwable t) {
            a.error = String.valueOf(t.getMessage());
            if (preferStrongBox) {
                // StrongBox is not on every device and refusing is normal; fall back
                // rather than reporting a failure that means nothing.
                return attest(c, false);
            }
        }
        return a;
    }

    /**
     * A distinguished name a person can read.
     *
     * Attestation roots carry no CN — the identity is a serialNumber encoded as a raw
     * OID, which prints as 2.5.4.5=#1310... and tells the reader nothing. Say what it
     * actually is instead.
     */
    private static String readableDn(String dn) {
        if (dn == null || dn.isEmpty()) return "";
        for (String part : dn.split(",")) {
            String p = part.trim();
            if (p.startsWith("CN=")) return p.substring(3);
            if (p.startsWith("O=")) return p.substring(2);
        }
        if (dn.contains("2.5.4.5")) {
            return "a per-device attestation root (serial number only, no common name)";
        }
        return dn;
    }

    private static String levelName(int lvl) {
        switch (lvl) {
            case KeyProperties.SECURITY_LEVEL_STRONGBOX:
                return "StrongBox — a separate tamper-resistant chip";
            case KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT:
                return "TEE — the processor's secure world";
            case KeyProperties.SECURITY_LEVEL_SOFTWARE:
                return "software only";
            default:
                return "unspecified";
        }
    }

    public static boolean hasStrongBox(Context c) {
        return Build.VERSION.SDK_INT >= 28 && c.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE);
    }

    public static int keystoreVersion(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            if (pm.hasSystemFeature("android.hardware.hardware_keystore")) {
                android.content.pm.FeatureInfo[] fs = pm.getSystemAvailableFeatures();
                if (fs != null) for (android.content.pm.FeatureInfo f : fs) {
                    if ("android.hardware.hardware_keystore".equals(f.name)) return f.version;
                }
            }
        } catch (Throwable ignored) { }
        return -1;
    }

    // ------------------------------------------------------------------ root signs

    /**
     * The obvious places a root binary sits.
     *
     * Worth being honest about what this is: a check anything can hide from. Every
     * root-hiding module defeats it, and a clean result proves nothing. It is here
     * because a *positive* result is still informative, not because a negative one
     * means anything.
     */
    public static List<String> rootSigns(Context c) {
        List<String> found = new ArrayList<>();
        String[] paths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
                "/system/app/Superuser.apk", "/data/adb/magisk",
                "/data/adb/ksu", "/data/adb/ap",
        };
        for (String p : paths) {
            if (new File(p).exists()) found.add(p);
        }
        String[] pkgs = {
                "com.topjohnwu.magisk", "eu.chainfire.supersu",
                "me.weishu.kernelsu", "com.koushikdutta.superuser",
        };
        for (String p : pkgs) {
            try {
                c.getPackageManager().getPackageInfo(p, 0);
                found.add(p);
            } catch (Throwable ignored) { }
        }
        return found;
    }

    /** A plain sentence about what an integrity check would likely conclude. */
    public static String verdict(List<Fact> facts, Attestation a) {
        int bad = 0;
        for (Fact f : facts) if (f.grade < 0) bad++;
        if (bad == 0 && a.ran && a.hardwareBacked) {
            return "Nothing here would fail a standard integrity check. The boot chain "
                 + "is intact, the Knox bit is unburnt, and the keystore can attest in "
                 + "hardware — which is the evidence that actually counts.";
        }
        if (bad == 0) {
            return "The properties all look right, but hardware attestation did not "
                 + "complete, so the strongest evidence is missing.";
        }
        return bad + " item(s) below would be objected to. Anything marked in red is "
             + "visible to any app that asks, and an unlocked bootloader or a burnt "
             + "Knox bit cannot be hidden from attestation however the properties are "
             + "set.";
    }
}
