package com.boresight;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encryption for anything the user asks us to remember that we would rather not hold.
 *
 * SSH passwords and key passphrases are the reason this exists. Keeping them in
 * SharedPreferences is keeping them in plain text in a file, and while /data/data is
 * closed to other apps and to the shell, it is not closed to a backup, to a rooted
 * device, or to anyone holding the unlocked phone with a file manager like the one in
 * this very app.
 *
 * The key lives in the AndroidKeyStore and never enters this process: it is generated
 * inside the keystore, used by reference, and cannot be exported even by us. Hardware
 * backing is requested implicitly — on these devices the key lands in the TEE or
 * StrongBox — and a factory reset or app uninstall destroys it, which is the correct
 * outcome for a stored credential.
 *
 * No androidx.security dependency: EncryptedSharedPreferences would pull in a library
 * for what is thirty lines of platform API.
 */
public final class Secret {

    private static final String KEY = "boresight.secrets";
    private static final String STORE = "AndroidKeyStore";
    private static final String XFORM = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;

    private Secret() { }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(STORE);
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(KEY, null);
        if (e instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        }
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
        g.init(new KeyGenParameterSpec.Builder(KEY,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // No user authentication requirement: the survey app is used with
                // gloves on a ladder, and a biometric prompt per SSH connect would
                // simply push people back to plain text somewhere else.
                .setRandomizedEncryptionRequired(true)
                .build());
        return g.generateKey();
    }

    /**
     * @return "iv:ciphertext", both base64, or null if the keystore refused — in which
     *         case the caller must not fall back to storing it in the clear
     */
    public static String seal(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            Cipher c = Cipher.getInstance(XFORM);
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
            return Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":"
                 + Base64.encodeToString(ct, Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    public static String open(String sealed) {
        if (sealed == null || sealed.isEmpty()) return "";
        try {
            int i = sealed.indexOf(':');
            if (i < 0) return "";
            byte[] iv = Base64.decode(sealed.substring(0, i), Base64.NO_WRAP);
            byte[] ct = Base64.decode(sealed.substring(i + 1), Base64.NO_WRAP);
            Cipher c = Cipher.getInstance(XFORM);
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), "UTF-8");
        } catch (Throwable t) {
            // A wiped keystore (factory reset, restored backup) makes old ciphertext
            // undecryptable for good. Returning empty asks for the password again,
            // which is right; throwing would strand the user on a broken host entry.
            return "";
        }
    }

    /** Whether the keystore is usable at all on this device. */
    public static boolean available(Context c) {
        String probe = seal("x");
        return probe != null && "x".equals(open(probe));
    }
}
