package com.boresight;

import android.content.Context;
import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

import java.util.Arrays;

/**
 * Emulate an NFC Forum Type 4 tag, so another reader sees this phone as a tag.
 *
 * This is the whole of what Android allows an app to emulate, and it is worth being
 * precise about why. Host card emulation routes ISO-DEP (ISO 14443-4) traffic to an app
 * as APDUs. A Type 4 tag is defined entirely in APDUs — select an application, select a
 * file, read it — so it can be implemented here exactly. MIFARE Classic cannot: it is
 * not ISO-DEP, it speaks a proprietary protocol with a cipher that runs inside the chip,
 * and the NFC controller never hands those frames to software. Nor can the UID be
 * chosen: the controller generates it, randomly, per activation.
 *
 * So this phone can be a tag that carries a message. It cannot be a copy of your door
 * fob, and anything claiming otherwise on an unrooted phone is selling something.
 *
 * The protocol, in the order a reader does it:
 *
 *   SELECT by name, AID D2760000850101   — "are you an NDEF tag?"
 *   SELECT by id, E103                   — the capability container
 *   READ BINARY                          — 15 bytes describing the NDEF file
 *   SELECT by id, E104                   — the NDEF file itself
 *   READ BINARY                          — two bytes of length, then the message
 */
public class NfcHce extends HostApduService {

    private static final byte[] OK        = {(byte) 0x90, 0x00};
    private static final byte[] NOT_FOUND = {0x6A, (byte) 0x82};
    private static final byte[] WRONG     = {0x6A, (byte) 0x00};

    private static final byte[] AID_NDEF = {
            (byte) 0xD2, 0x76, 0x00, 0x00, (byte) 0x85, 0x01, 0x01 };
    private static final byte[] FILE_CC   = {(byte) 0xE1, 0x03};
    private static final byte[] FILE_NDEF = {(byte) 0xE1, 0x04};

    /**
     * The capability container. Fixed apart from the maximum NDEF size, which is set to
     * something larger than anything this will serve so a reader never truncates.
     */
    private static final byte[] CC = {
            0x00, 0x0F,                   // CC length
            0x20,                         // mapping version 2.0
            0x00, (byte) 0x3B,            // max R-APDU
            0x00, 0x34,                   // max C-APDU
            0x04, 0x06,                   // NDEF file control TLV, 6 bytes
            (byte) 0xE1, 0x04,            // file id
            0x04, 0x00,                   // max NDEF size, 1024
            0x00,                         // read access: free
            (byte) 0xFF                   // write access: none
    };

    /** 0 = none selected, 1 = CC, 2 = NDEF. */
    private int selected;

    @Override public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        if (apdu == null || apdu.length < 4) return WRONG;

        int ins = apdu[1] & 0xFF, p1 = apdu[2] & 0xFF, p2 = apdu[3] & 0xFF;

        // SELECT
        if (ins == 0xA4) {
            if (p1 == 0x04) {                                   // by name
                byte[] aid = body(apdu);
                selected = Arrays.equals(aid, AID_NDEF) ? 0 : -1;
                return selected == 0 ? OK : NOT_FOUND;
            }
            if (p1 == 0x00) {                                   // by file id
                byte[] id = body(apdu);
                if (Arrays.equals(id, FILE_CC))   { selected = 1; return OK; }
                if (Arrays.equals(id, FILE_NDEF)) { selected = 2; return OK; }
                return NOT_FOUND;
            }
            return WRONG;
        }

        // READ BINARY
        if (ins == 0xB0) {
            int offset = (p1 << 8) | p2;
            int want = apdu.length > 4 ? (apdu[apdu.length - 1] & 0xFF) : 0;
            byte[] file = selected == 1 ? CC
                        : selected == 2 ? ndefFile(this)
                        : null;
            if (file == null) return NOT_FOUND;
            if (offset > file.length) return WRONG;
            int n = Math.min(want == 0 ? file.length - offset : want, file.length - offset);
            byte[] out = new byte[n + 2];
            System.arraycopy(file, offset, out, 0, n);
            out[n] = (byte) 0x90;
            out[n + 1] = 0x00;
            return out;
        }

        return WRONG;
    }

    /** The data field of a case-3 APDU: one length byte, then that many bytes. */
    private static byte[] body(byte[] apdu) {
        if (apdu.length < 5) return new byte[0];
        int lc = apdu[4] & 0xFF;
        if (lc == 0 || apdu.length < 5 + lc) return new byte[0];
        return Arrays.copyOfRange(apdu, 5, 5 + lc);
    }

    /**
     * The NDEF file: a two-byte big-endian length, then the message.
     *
     * Built fresh for each read rather than cached, so changing the message in the app
     * takes effect on the next tap instead of the next reboot.
     */
    static byte[] ndefFile(Context c) {
        byte[] msg = message(c);
        byte[] out = new byte[msg.length + 2];
        out[0] = (byte) ((msg.length >> 8) & 0xFF);
        out[1] = (byte) (msg.length & 0xFF);
        System.arraycopy(msg, 0, out, 2, msg.length);
        return out;
    }

    /** Whatever the user set, as a single NDEF record. */
    static byte[] message(Context c) {
        String text = Prefs.string(c, Prefs.NFC_EMULATE, "");
        try {
            android.nfc.NdefRecord r = text.startsWith("http://")
                                    || text.startsWith("https://")
                    ? android.nfc.NdefRecord.createUri(text)
                    : android.nfc.NdefRecord.createTextRecord("en",
                            text.isEmpty() ? "Boresight" : text);
            return new android.nfc.NdefMessage(new android.nfc.NdefRecord[]{r})
                    .toByteArray();
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    @Override public void onDeactivated(int reason) { selected = 0; }
}
