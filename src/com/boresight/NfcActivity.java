package com.boresight;

import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.MifareUltralight;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcB;
import android.nfc.tech.NfcF;
import android.nfc.tech.NfcV;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read, dump and write the NFC tags this phone can actually reach.
 *
 * What a phone can do here is narrower than a dedicated reader, and the limits are worth
 * stating rather than discovering:
 *
 *   Reading is good. Any tag the controller supports can be identified and, with the
 *   right keys, dumped — MIFARE Classic sector by sector, Ultralight page by page, NDEF
 *   as records, and anything ISO-DEP by APDU.
 *
 *   Writing is good, with the same caveat about keys and about locked pages.
 *
 *   Emulation is not. Android's card emulation is host-based and ISO-DEP only: it can
 *   pretend to be a smartcard that answers APDUs, which covers NDEF Type 4 and payment-
 *   shaped applets. It cannot present a chosen UID — the controller generates that — and
 *   it cannot emulate MIFARE Classic at all, because Classic is not ISO-DEP and its
 *   crypto lives in the chip. A phone will not clone a door fob. That is a hardware and
 *   policy limit, not a missing feature here, and no app on an unrooted phone does it.
 *
 * The reading is done on a worker thread. A MIFARE Classic dump is up to forty sector
 * authentications over a radio link, and each failed key costs a full retry — on the
 * main thread that is a frozen screen and eventually an ANR.
 */
public class NfcActivity extends Screen {

    private NfcAdapter adapter;

    private TextView state, idLine, typeLine, techLine, sizeLine, writableLine,
            emulateLine;
    private TextView dumpBody, progressLine;
    private LinearLayout dumpPane;

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final android.os.Handler ui =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** The last tag seen, so the buttons have something to act on after the tap. */
    private Tag tag;
    private boolean reading;
    /** Poll ISO 15693 alone, giving a slow 15693 card the whole duty cycle. */
    private boolean vOnly;
    private android.widget.TextView vOnlyButton;
    private byte[][] lastBlocks;
    private String[] lastKeys;
    private int lastSectors;

    @Override protected String title() { return "NFC"; }

    @Override protected void content() {
        adapter = NfcAdapter.getDefaultAdapter(this);

        LinearLayout tagPane = pane("Tag");
        state = th.text(this, "", 11, skin.accent(Skin.CH_ALERT));
        tagPane.addView(state);
        idLine       = kv(tagPane, "UID", "—");
        typeLine     = kv(tagPane, "Type", "—");
        techLine     = kv(tagPane, "Technologies", "—");
        sizeLine     = kv(tagPane, "Size", "—");
        writableLine = kv(tagPane, "Writable", "—");
        hint(tagPane, "Hold a tag against the back of the phone, near the top. The "
                    + "screen fills in as soon as the controller sees it.");

        LinearLayout act = pane("Read");
        LinearLayout r1 = actionRow(act);
        smallAction(r1, "Dump", new Runnable() {
            @Override public void run() { dump(); }
        });
        smallAction(r1, "NDEF", new Runnable() {
            @Override public void run() { readNdef(); }
        });
        smallAction(r1, "Save dump", new Runnable() {
            @Override public void run() { saveDump(); }
        });
        LinearLayout r3 = actionRow(act);
        smallAction(r3, "Raw frame", new Runnable() {
            @Override public void run() { rawFrame(); }
        });
        vOnlyButton = smallAction(r3, "15693 only", new Runnable() {
            @Override public void run() { toggleVOnly(); }
        });
        progressLine = th.text(this, "", 11, skin.inkSecondary);
        act.addView(progressLine);
        hint(act, "Dump authenticates each sector against the key dictionary and reads "
                + "what opens. Sectors whose key is not in the dictionary are marked in "
                + "the output rather than left out, so a partial dump cannot be mistaken "
                + "for a whole one.");
        hint(act, "Raw frame sends hex of your own to the tag and shows the reply. "
                + "15693 only narrows the radio to ISO 15693, which these tags often "
                + "need to be seen at all — they are slow, and sharing the poll loop "
                + "with four other technologies can leave them no time to answer.");

        LinearLayout write = pane("Write");
        LinearLayout r2 = actionRow(write);
        smallAction(r2, "Write text", new Runnable() {
            @Override public void run() { writeNdefText(); }
        });
        smallAction(r2, "Write URL", new Runnable() {
            @Override public void run() { writeNdefUrl(); }
        });
        smallAction(r2, "Write block", new Runnable() {
            @Override public void run() { writeBlock(); }
        });
        hint(write, "A block write goes straight to the card and there is no undo. Dump "
                 + "first: the dump is the only copy of what was there.");

        LinearLayout keys = pane("Keys");
        hint(keys, Mifare.dictionary(null).size() + " keys built in — the factory "
                 + "default and the ones published in NXP's notes and the public "
                 + "dictionaries. They open cards whose key was never changed, which is "
                 + "most of them, and nothing else.");
        action(keys, "My extra keys", new Runnable() {
            @Override public void run() { editKeys(); }
        });

        LinearLayout emu = pane("Emulation");
        hint(emu, "Android emulates ISO-DEP cards in software — an applet that answers "
                + "APDUs, which covers NDEF Type 4. It cannot present a chosen UID, and "
                + "it cannot emulate MIFARE Classic: Classic is not ISO-DEP and its "
                + "cipher runs in the chip, not the host. A phone will not clone a door "
                + "fob, and no app on an unrooted phone does.");
        kv(emu, "Host card emulation",
                getPackageManager().hasSystemFeature("android.hardware.nfc.hce")
                        ? "supported" : "not supported");
        kv(emu, "FeliCa emulation",
                getPackageManager().hasSystemFeature("android.hardware.nfc.hcef")
                        ? "supported" : "not supported");
        kv(emu, "MIFARE Classic",
                getPackageManager().hasSystemFeature("com.nxp.mifare")
                        ? "readable and writable" : "not supported by this controller");
        emulateLine = kv(emu, "This phone presents", "—");
        action(emu, "Set what this phone presents", new Runnable() {
            @Override public void run() { editEmulated(); }
        });
        hint(emu, "With that set, a reader or another phone held against this one reads "
                + "an NDEF tag carrying it. Android will not answer from a locked "
                + "screen, which is deliberate and not something an app may override.");

        dumpPane = pane("Dump");
        dumpBody = th.num(this, "Nothing read yet.", 10, skin.inkSecondary);
        dumpBody.setTypeface(android.graphics.Typeface.MONOSPACE);
        dumpBody.setTextIsSelectable(true);
        dumpPane.addView(dumpBody);

        paintEmulated();
        paintState();
    }

    // ------------------------------------------------------------------ dispatch

    @Override protected void onResume() {
        super.onResume();
        paintState();
        startReader();
    }

    @Override protected void onPause() {
        super.onPause();
        stopReader();
    }

    /**
     * Reader mode rather than foreground dispatch, and the difference matters.
     *
     * Foreground dispatch rides the platform's own discovery loop: it polls for what
     * the system wants, runs an NDEF check on whatever it finds, and hands over a Tag
     * only once that has succeeded. A card that is not NDEF and answers nothing the
     * loop asks — an iCLASS badge, say — is simply never reported. Holding one against
     * the phone does nothing at all, which looks like a broken screen rather than a
     * discovery that was never attempted.
     *
     * Reader mode takes the loop over: these are the technologies to poll for, do not
     * do the NDEF check, do not play the platform's sound. It is the closest an app
     * gets to turning the radio on itself, and it is what makes the unusual cards
     * appear.
     *
     * The presence-check delay is raised because the default is tuned for a tap. A
     * sector-by-sector dump holds the field for several seconds, and a presence check
     * in the middle of that costs a retry on every sector.
     *
     * vOnly narrows the loop to ISO 15693 alone. The controller round-robins the
     * technologies it is given, dwelling briefly on each, so polling five of them
     * leaves a 15693 card about a fifth of the field. These cards are slow to power up
     * and slow to answer, and an iCLASS card in particular may never get far enough
     * through its own wake-up to reply before the loop has moved on to NFC-F. Dropping
     * to one technology is the only control over dwell time the API exposes.
     */
    private void startReader() {
        if (adapter == null || !adapter.isEnabled()) return;
        int flags = vOnly
                  ? NfcAdapter.FLAG_READER_NFC_V
                  : NfcAdapter.FLAG_READER_NFC_A
                  | NfcAdapter.FLAG_READER_NFC_B
                  | NfcAdapter.FLAG_READER_NFC_F
                  | NfcAdapter.FLAG_READER_NFC_V
                  | NfcAdapter.FLAG_READER_NFC_BARCODE;
        flags |= NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
               | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS;
        Bundle extras = new Bundle();
        extras.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 5000);
        try {
            adapter.enableReaderMode(this, new NfcAdapter.ReaderCallback() {
                @Override public void onTagDiscovered(final Tag t) {
                    // Delivered on a binder thread, not this screen's.
                    ui.post(new Runnable() {
                        @Override public void run() { onTag(t); }
                    });
                }
            }, flags, extras);
            reading = true;
        } catch (Throwable e) {
            reading = false;
        }
        paintState();
    }

    /**
     * Re-arm the reader with the narrowed or the full technology list. Reader mode has
     * to be torn down and set up again: the flags are fixed at enable time and there is
     * no call to change them in place.
     */
    private void toggleVOnly() {
        vOnly = !vOnly;
        if (vOnlyButton != null) {
            vOnlyButton.setText(vOnly ? "all tech" : "15693 only");
        }
        stopReader();
        startReader();
    }

    private void stopReader() {
        if (adapter == null) return;
        try { adapter.disableReaderMode(this); } catch (Throwable ignored) { }
        reading = false;
    }

    private void paintState() {
        if (state == null) return;
        if (adapter == null) {
            state.setText("This phone has no NFC controller.");
        } else if (!adapter.isEnabled()) {
            state.setText("NFC is switched off. Turn it on in system settings.");
        } else if (!reading) {
            state.setText("The radio is not polling. Leave and re-enter this screen.");
        } else {
            state.setText(tag == null
                    ? (vOnly
                        ? "Polling ISO 15693 only — the whole field goes to one "
                        + "technology, so a slow 15693 card has the best chance it "
                        + "gets. Nothing else will be seen while this is on."
                        : "Polling A, B, F, V and barcode — hold a tag to the back of "
                        + "the phone, near the top.")
                    : "");
        }
    }

    private void onTag(Tag t) {
        tag = t;
        lastBlocks = null; lastKeys = null; lastSectors = 0;
        state.setText("");
        idLine.setText(Mifare.hexSpaced(t.getId()) + "   (" + t.getId().length + " bytes)");
        techLine.setText(shortTechs(t));
        typeLine.setText(describe(t));
        sizeLine.setText(sizeOf(t));
        writableLine.setText(writableOf(t));
        dumpBody.setText("Nothing read yet.");
        progressLine.setText("");
        toast("Tag read");
    }

    private static String shortTechs(Tag t) {
        StringBuilder b = new StringBuilder();
        for (String s : t.getTechList()) {
            if (b.length() > 0) b.append(", ");
            b.append(s.substring(s.lastIndexOf('.') + 1));
        }
        return b.toString();
    }

    /**
     * What the tag is, in the terms the card industry uses rather than the class names.
     *
     * The SAK and ATQA are the bytes the card answers an anticollision with, and they
     * are what distinguishes a Classic from an Ultralight from a DESFire before anything
     * is authenticated.
     */
    private String describe(Tag t) {
        MifareClassic mc = MifareClassic.get(t);
        if (mc != null) {
            String kind;
            switch (mc.getType()) {
                case MifareClassic.TYPE_CLASSIC: kind = "MIFARE Classic"; break;
                case MifareClassic.TYPE_PLUS:    kind = "MIFARE Plus"; break;
                case MifareClassic.TYPE_PRO:     kind = "MIFARE Pro"; break;
                default:                         kind = "MIFARE (unknown type)";
            }
            return kind + "   " + (mc.getSize() / 1024f == 1 ? "1K"
                    : mc.getSize() == 4096 ? "4K" : mc.getSize() + " bytes");
        }
        if (MifareUltralight.get(t) != null) {
            int ty = MifareUltralight.get(t).getType();
            return ty == MifareUltralight.TYPE_ULTRALIGHT_C
                    ? "MIFARE Ultralight C" : "MIFARE Ultralight";
        }
        if (IsoDep.get(t) != null) return "ISO 14443-4 (ISO-DEP) — smartcard";
        if (NfcV.get(t) != null)   return "ISO 15693 (vicinity)";
        if (NfcF.get(t) != null)   return "FeliCa";
        if (NfcB.get(t) != null)   return "ISO 14443-3B";
        NfcA a = NfcA.get(t);
        if (a != null) {
            return String.format(Locale.US, "ISO 14443-3A   SAK %02X   ATQA %s",
                    a.getSak(), Mifare.hex(a.getAtqa()));
        }
        return "unrecognised";
    }

    private String sizeOf(Tag t) {
        MifareClassic mc = MifareClassic.get(t);
        if (mc != null) {
            return mc.getSize() + " bytes   ·   " + mc.getSectorCount() + " sectors   ·   "
                 + mc.getBlockCount() + " blocks";
        }
        MifareUltralight mu = MifareUltralight.get(t);
        if (mu != null) return "pages of 4 bytes";
        Ndef n = Ndef.get(t);
        if (n != null) return n.getMaxSize() + " bytes of NDEF capacity";
        return "—";
    }

    private String writableOf(Tag t) {
        Ndef n = Ndef.get(t);
        if (n != null) return n.isWritable() ? "yes (NDEF)" : "no — locked";
        if (NdefFormatable.get(t) != null) return "not formatted yet, but formattable";
        if (MifareClassic.get(t) != null) return "block writes, with the right key";
        if (MifareUltralight.get(t) != null) return "page writes, unless locked";
        return "—";
    }

    // ------------------------------------------------------------------ reading

    private void dump() {
        final Tag t = tag;
        if (t == null) { toast("Tap a tag first"); return; }
        if (MifareClassic.get(t) != null) { dumpClassic(t); return; }
        if (MifareUltralight.get(t) != null) { dumpUltralight(t); return; }
        readNdef();
    }

    private void dumpClassic(final Tag t) {
        final List<String> keys = Mifare.dictionary(
                Prefs.string(this, Prefs.NFC_KEYS, ""));
        progressLine.setText("reading…");
        pool.execute(new Runnable() {
            @Override public void run() {
                MifareClassic mc = MifareClassic.get(t);
                final StringBuilder note = new StringBuilder();
                byte[][] blocks = null;
                String[] used = null;
                int sectors = 0;
                int opened = 0;
                try {
                    mc.connect();
                    sectors = mc.getSectorCount();
                    blocks = new byte[mc.getBlockCount()][];
                    used = new String[sectors];

                    for (int s = 0; s < sectors; s++) {
                        final int shown = s + 1, total = sectors;
                        ui.post(new Runnable() {
                            @Override public void run() {
                                progressLine.setText("sector " + shown + " of " + total);
                            }
                        });
                        String hit = null;
                        for (String k : keys) {
                            // Key A then key B: plenty of cards leave one of the two at
                            // the factory value while changing the other.
                            if (mc.authenticateSectorWithKeyA(s, Mifare.keyBytes(k))) {
                                hit = k + " (A)"; break;
                            }
                            if (mc.authenticateSectorWithKeyB(s, Mifare.keyBytes(k))) {
                                hit = k + " (B)"; break;
                            }
                        }
                        if (hit == null) continue;
                        used[s] = hit;
                        opened++;
                        int first = mc.sectorToBlock(s);
                        for (int i = 0; i < mc.getBlockCountInSector(s); i++) {
                            try { blocks[first + i] = mc.readBlock(first + i); }
                            catch (Throwable ignored) { }
                        }
                    }
                } catch (Throwable e) {
                    note.append("\nStopped: ").append(e.getClass().getSimpleName())
                        .append(' ').append(String.valueOf(e.getMessage()));
                } finally {
                    try { mc.close(); } catch (Throwable ignored) { }
                }

                final byte[][] fb = blocks;
                final String[] fk = used;
                final int fs = sectors, fo = opened;
                ui.post(new Runnable() {
                    @Override public void run() {
                        lastBlocks = fb; lastKeys = fk; lastSectors = fs;
                        progressLine.setText(fo + " of " + fs + " sectors opened"
                                + (fo < fs ? "  ·  the rest need keys not in the "
                                           + "dictionary" : ""));
                        dumpBody.setText(fb == null ? "Nothing read." + note
                                : pretty(fb, fk, fs) + note);
                    }
                });
            }
        });
    }

    /** Hex, ASCII and a marker on the trailer, which is keys rather than content. */
    private String pretty(byte[][] blocks, String[] keys, int sectors) {
        StringBuilder b = new StringBuilder();
        for (int s = 0; s < sectors; s++) {
            b.append("sector ").append(s);
            if (keys[s] != null) b.append("   ").append(keys[s]);
            else b.append("   locked");
            b.append('\n');
            int first = Mifare.firstBlock(s);
            for (int i = 0; i < Mifare.blocksInSector(s); i++) {
                int blk = first + i;
                if (blk >= blocks.length) break;
                b.append(String.format(Locale.US, "  %3d ", blk));
                if (blocks[blk] == null) {
                    b.append("-- not read --");
                } else {
                    b.append(Mifare.hexSpaced(blocks[blk]))
                     .append("  |").append(Mifare.ascii(blocks[blk])).append('|');
                    if (Mifare.isTrailer(s, i)) b.append("  <- keys");
                }
                b.append('\n');
            }
        }
        return b.toString();
    }

    private void dumpUltralight(final Tag t) {
        progressLine.setText("reading…");
        pool.execute(new Runnable() {
            @Override public void run() {
                MifareUltralight mu = MifareUltralight.get(t);
                final StringBuilder b = new StringBuilder();
                try {
                    mu.connect();
                    // readPages returns four pages at a time; 16 pages covers a plain
                    // Ultralight and the first part of anything larger.
                    for (int p = 0; p < 16; p += 4) {
                        byte[] four = mu.readPages(p);
                        for (int i = 0; i < 4; i++) {
                            byte[] page = new byte[4];
                            System.arraycopy(four, i * 4, page, 0, 4);
                            b.append(String.format(Locale.US, "  %2d  ", p + i))
                             .append(Mifare.hexSpaced(page))
                             .append("  |").append(Mifare.ascii(page)).append("|\n");
                        }
                    }
                } catch (Throwable e) {
                    b.append("\nStopped: ").append(String.valueOf(e.getMessage()));
                } finally {
                    try { mu.close(); } catch (Throwable ignored) { }
                }
                ui.post(new Runnable() {
                    @Override public void run() {
                        progressLine.setText("");
                        dumpBody.setText(b.toString());
                    }
                });
            }
        });
    }

    private void readNdef() {
        final Tag t = tag;
        if (t == null) { toast("Tap a tag first"); return; }
        pool.execute(new Runnable() {
            @Override public void run() {
                final StringBuilder b = new StringBuilder();
                Ndef n = Ndef.get(t);
                if (n == null) {
                    b.append("No NDEF on this tag.");
                    if (NdefFormatable.get(t) != null) {
                        b.append(" It can be formatted by writing to it.");
                    }
                } else {
                    try {
                        n.connect();
                        NdefMessage m = n.getNdefMessage();
                        b.append(n.getType()).append("   ")
                         .append(n.getMaxSize()).append(" bytes capacity   ")
                         .append(n.isWritable() ? "writable" : "locked").append("\n\n");
                        if (m == null) b.append("Formatted, but empty.");
                        else {
                            NdefRecord[] rs = m.getRecords();
                            b.append(Theme.count(rs.length, "record")).append("\n");
                            for (int i = 0; i < rs.length; i++) {
                                b.append('\n').append(i).append("  ")
                                 .append(tnf(rs[i].getTnf())).append("  type ")
                                 .append(new String(rs[i].getType(), "UTF-8"))
                                 .append('\n');
                                byte[] p = rs[i].getPayload();
                                b.append("   ").append(Mifare.ascii(p)).append('\n');
                                b.append("   ").append(Mifare.hex(p)).append('\n');
                            }
                        }
                    } catch (Throwable e) {
                        b.append("Could not read: ").append(String.valueOf(e.getMessage()));
                    } finally {
                        try { n.close(); } catch (Throwable ignored) { }
                    }
                }
                ui.post(new Runnable() {
                    @Override public void run() { dumpBody.setText(b.toString()); }
                });
            }
        });
    }

    private static String tnf(short t) {
        switch (t) {
            case NdefRecord.TNF_WELL_KNOWN: return "well known";
            case NdefRecord.TNF_MIME_MEDIA: return "mime";
            case NdefRecord.TNF_ABSOLUTE_URI: return "uri";
            case NdefRecord.TNF_EXTERNAL_TYPE: return "external";
            case NdefRecord.TNF_EMPTY: return "empty";
            default: return "tnf " + t;
        }
    }

    // ------------------------------------------------------------------ iclass

    /**
     * Send an arbitrary frame to whichever interface the tag presents.
     *
     * The general tool underneath every specific one. Any protocol this phone can reach
     * can be poked at from here, and what comes back is shown as it arrived rather than
     * interpreted.
     */
    private void rawFrame() {
        final Tag t = tag;
        if (t == null) { toast("Tap a tag first"); return; }
        final Sheet s = Sheet.of(this, "Raw frame");
        s.text("Hex, sent to the first of IsoDep, NfcV, NfcA that this tag presents. "
             + "The controller appends the CRC, which is why a protocol with its own "
             + "CRC cannot be spoken this way.");
        s.input("00 A4 04 00 07 D2 76 00 00 85 01 01", "", 2);
        s.action("Send", true, new Runnable() {
            @Override public void run() {
                final byte[] frame = Mifare.parseHex(s.typed());
                if (frame.length == 0) { toast("Nothing to send"); return; }
                pool.execute(new Runnable() {
                    @Override public void run() {
                        final StringBuilder b = new StringBuilder();
                        b.append("sent  ").append(Mifare.hexSpaced(frame)).append("\n\n");
                        android.nfc.tech.TagTechnology tech = IsoDep.get(t);
                        if (tech == null) tech = NfcV.get(t);
                        if (tech == null) tech = NfcA.get(t);
                        if (tech == null) { b.append("no transceivable interface"); }
                        else {
                            try {
                                tech.connect();
                                byte[] r = tech instanceof IsoDep
                                        ? ((IsoDep) tech).transceive(frame)
                                        : tech instanceof NfcV
                                                ? ((NfcV) tech).transceive(frame)
                                                : ((NfcA) tech).transceive(frame);
                                b.append("got   ").append(Mifare.hexSpaced(r))
                                 .append("\n      |").append(Mifare.ascii(r)).append('|');
                            } catch (Throwable e) {
                                b.append("failed: ").append(e.getClass().getSimpleName())
                                 .append(' ').append(String.valueOf(e.getMessage()));
                            } finally {
                                try { tech.close(); } catch (Throwable ignored) { }
                            }
                        }
                        ui.post(new Runnable() {
                            @Override public void run() { dumpBody.setText(b.toString()); }
                        });
                    }
                });
            }
        });
        s.close("Cancel");
        s.show();
    }

    // ------------------------------------------------------------------ writing

    private void writeNdefText() {
        if (tag == null) { toast("Tap a tag first"); return; }
        ask("Text to write", "", new Consumer<String>() {
            @Override public void accept(String s) {
                if (s == null || s.isEmpty()) return;
                writeMessage(new NdefMessage(new NdefRecord[]{
                        NdefRecord.createTextRecord("en", s) }));
            }
        });
    }

    private void writeNdefUrl() {
        if (tag == null) { toast("Tap a tag first"); return; }
        ask("URL to write", "https://", new Consumer<String>() {
            @Override public void accept(String s) {
                if (s == null || s.trim().isEmpty()) return;
                writeMessage(new NdefMessage(new NdefRecord[]{
                        NdefRecord.createUri(s.trim()) }));
            }
        });
    }

    private void writeMessage(final NdefMessage msg) {
        final Tag t = tag;
        pool.execute(new Runnable() {
            @Override public void run() {
                String result;
                Ndef n = Ndef.get(t);
                try {
                    if (n != null) {
                        n.connect();
                        if (!n.isWritable()) result = "The tag is locked.";
                        else if (n.getMaxSize() < msg.toByteArray().length) {
                            result = "Too big: the tag holds " + n.getMaxSize()
                                   + " bytes and that message is "
                                   + msg.toByteArray().length + ".";
                        } else { n.writeNdefMessage(msg); result = "Written."; }
                        n.close();
                    } else {
                        NdefFormatable f = NdefFormatable.get(t);
                        if (f == null) { result = "This tag does not take NDEF."; }
                        else { f.connect(); f.format(msg); f.close();
                               result = "Formatted and written."; }
                    }
                } catch (Throwable e) {
                    result = "Failed: " + e.getClass().getSimpleName() + " "
                           + String.valueOf(e.getMessage());
                }
                final String r = result;
                ui.post(new Runnable() {
                    @Override public void run() { info("Write", r); }
                });
            }
        });
    }

    /** A raw block write, which is the only way to change a non-NDEF card. */
    private void writeBlock() {
        final Tag t = tag;
        if (t == null) { toast("Tap a tag first"); return; }
        if (MifareClassic.get(t) == null) {
            info("Not a Classic", "Block writes are a MIFARE Classic operation. For an "
                    + "NDEF tag use Write text or Write URL.");
            return;
        }
        LinearLayout box = th.column(this);
        box.setPadding(th.dp(4), 0, th.dp(4), 0);
        final android.widget.EditText blk = new android.widget.EditText(this);
        blk.setHint("block number");
        blk.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        blk.setTextColor(skin.inkPrimary);
        box.addView(blk);
        final android.widget.EditText hex = new android.widget.EditText(this);
        hex.setHint("16 bytes of hex");
        hex.setTypeface(android.graphics.Typeface.MONOSPACE);
        hex.setTextColor(skin.inkPrimary);
        box.addView(hex);

        Sheet.of(this, "Write a block")
             .text("Straight to the card, no undo. The trailer block of a sector holds "
                 + "the keys and the access bits: writing the wrong bytes there locks "
                 + "the sector permanently.")
             .view(box)
             .action("Write", true, new Runnable() {
                 @Override public void run() {
                     try {
                         int n = Integer.parseInt(blk.getText().toString().trim());
                         byte[] data = Mifare.parseHex(hex.getText().toString());
                         if (data.length != 16) {
                             info("Wrong length", "A block is exactly 16 bytes; that is "
                                     + data.length + ".");
                             return;
                         }
                         doWriteBlock(t, n, data);
                     } catch (Exception e) {
                         info("Could not write", String.valueOf(e.getMessage()));
                     }
                 }
             })
             .close("Cancel")
             .show();
    }

    private void doWriteBlock(final Tag t, final int block, final byte[] data) {
        final List<String> keys = Mifare.dictionary(
                Prefs.string(this, Prefs.NFC_KEYS, ""));
        pool.execute(new Runnable() {
            @Override public void run() {
                String result = "No key opened that sector.";
                MifareClassic mc = MifareClassic.get(t);
                try {
                    mc.connect();
                    int sector = mc.blockToSector(block);
                    for (String k : keys) {
                        boolean ok = mc.authenticateSectorWithKeyB(sector, Mifare.keyBytes(k))
                                  || mc.authenticateSectorWithKeyA(sector, Mifare.keyBytes(k));
                        if (!ok) continue;
                        mc.writeBlock(block, data);
                        result = "Block " + block + " written with " + k + ".";
                        break;
                    }
                } catch (Throwable e) {
                    result = "Failed: " + String.valueOf(e.getMessage());
                } finally {
                    try { mc.close(); } catch (Throwable ignored) { }
                }
                final String r = result;
                ui.post(new Runnable() {
                    @Override public void run() { info("Write block", r); }
                });
            }
        });
    }

    // ------------------------------------------------------------------ output

    private void saveDump() {
        if (lastBlocks == null) { toast("Dump a tag first"); return; }
        try {
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                    Locale.US).format(new java.util.Date());
            String base = "tag-" + Mifare.hex(tag.getId()) + "-" + stamp;
            File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "");

            // Two files on purpose: the text is for reading and the binary is what every
            // other MIFARE tool expects to load.
            File txt = new File(dir, base + ".txt");
            FileOutputStream o = new FileOutputStream(txt);
            try { o.write(Mifare.toText(lastBlocks, lastKeys, lastSectors)
                    .getBytes("UTF-8")); } finally { o.close(); }

            File bin = new File(dir, base + ".mfd");
            FileOutputStream b = new FileOutputStream(bin);
            try {
                for (byte[] blk : lastBlocks) b.write(blk == null ? new byte[16] : blk);
            } finally { b.close(); }

            info("Saved", txt.getAbsolutePath() + "\n\n" + bin.getAbsolutePath()
                    + "\n\nUnread blocks are written as zeros in the .mfd, because the "
                    + "format has no way to say \"missing\". The .txt marks them.");
        } catch (Exception e) {
            info("Could not save", String.valueOf(e.getMessage()));
        }
    }

    /** What this phone answers with when a reader treats it as a tag. */
    private void editEmulated() {
        final Sheet s = Sheet.of(this, "Present as a tag");
        s.text("A line of text, or a URL starting http:// or https:// — a URL is "
             + "written as a URI record so a reader opens it, anything else as a text "
             + "record. Leave it empty to present the app's name.");
        s.input("https://example.com  or  any text",
                Prefs.string(this, Prefs.NFC_EMULATE, ""), 2);
        s.action("Save", true, new Runnable() {
            @Override public void run() {
                Prefs.put(NfcActivity.this, Prefs.NFC_EMULATE, s.typed());
                paintEmulated();
                toast("Saved — hold a reader to the phone to test it");
            }
        });
        s.close("Cancel");
        s.show();
    }

    private void paintEmulated() {
        if (emulateLine == null) return;
        String t = Prefs.string(this, Prefs.NFC_EMULATE, "");
        emulateLine.setText(t.isEmpty() ? "the app's name (nothing set)" : t);
    }

    private void editKeys() {
        final Sheet s = Sheet.of(this, "My extra keys");
        s.text("One 12-digit hex key per line. These are tried after the built-ins.");
        s.input("A0A1A2A3A4A5", Prefs.string(this, Prefs.NFC_KEYS, ""), 5);
        s.action("Save", true, new Runnable() {
            @Override public void run() {
                Prefs.put(NfcActivity.this, Prefs.NFC_KEYS, s.typed());
                toast(Mifare.dictionary(s.typed()).size() + " keys in the dictionary");
            }
        });
        s.close("Cancel");
        s.show();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }
}
