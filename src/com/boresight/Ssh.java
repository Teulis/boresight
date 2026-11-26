package com.boresight;

import android.content.Context;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.UserInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Vector;

/**
 * SSH and file transfer, on top of JSch.
 *
 * The fork matters: this is com.github.mwiede's JSch, not the original jcraft one. The
 * original stopped at 0.1.55 with defaults — ssh-rsa with SHA-1, diffie-hellman-group1 —
 * that current OpenSSH refuses outright, so it fails to connect to anything maintained
 * while appearing to be the obvious choice. The fork carries curve25519, ed25519,
 * rsa-sha2 and AES-GCM.
 *
 * Host keys are checked, not waved through. StrictHostKeyChecking is left on and an
 * unknown key raises a prompt showing the fingerprint; accepting writes it to a
 * known_hosts file in the app's own storage. Turning this off would be one line and
 * would quietly make every connection interceptable, which is a poor trade for saving
 * the user one tap the first time.
 *
 * Transfers use SFTP rather than the scp binary protocol. They are the same thing to a
 * user, but scp's protocol is a shell-quoting hazard by design — it runs a remote
 * command built from the path — and OpenSSH itself switched scp to an SFTP backend in
 * version 9. Going straight to SFTP skips the quoting problem and gets directory
 * listings with real metadata for free.
 */
public final class Ssh {

    /** A saved host. The password, if kept at all, is sealed by {@link Secret}. */
    public static final class Host {
        public String label = "", host = "", user = "", sealedPass = "";
        public int port = 22;
        public String keyPath = "", sealedPassphrase = "";
        public boolean savePass;

        public String title() {
            String t = label != null && !label.isEmpty() ? label : host;
            return t + (port == 22 ? "" : ":" + port);
        }

        public String subtitle() {
            return (user == null || user.isEmpty() ? "" : user + "@") + host
                 + (port == 22 ? "" : ":" + port)
                 + (keyPath != null && !keyPath.isEmpty() ? "  ·  key" : "")
                 + (savePass ? "  ·  password saved" : "");
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("label", label); o.put("host", host); o.put("user", user);
            o.put("port", port); o.put("keyPath", keyPath);
            o.put("savePass", savePass);
            if (savePass) o.put("pass", sealedPass);
            o.put("passphrase", sealedPassphrase);
            return o;
        }

        static Host fromJson(JSONObject o) {
            Host h = new Host();
            h.label = o.optString("label", "");
            h.host = o.optString("host", "");
            h.user = o.optString("user", "");
            h.port = o.optInt("port", 22);
            h.keyPath = o.optString("keyPath", "");
            h.savePass = o.optBoolean("savePass", false);
            h.sealedPass = o.optString("pass", "");
            h.sealedPassphrase = o.optString("passphrase", "");
            return h;
        }
    }

    // ------------------------------------------------------------------ saved hosts

    private static final String HOSTS = "ssh_hosts";

    public static List<Host> hosts(Context c) {
        List<Host> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.string(c, HOSTS, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Host.fromJson(a.getJSONObject(i)));
        } catch (Throwable ignored) { }
        return out;
    }

    public static void saveHosts(Context c, List<Host> list) {
        try {
            JSONArray a = new JSONArray();
            for (Host h : list) a.put(h.toJson());
            Prefs.get(c).edit().putString(HOSTS, a.toString()).apply();
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ host keys

    /** What the user must answer before an unknown host is trusted. */
    public interface Trust {
        /**
         * @param fingerprint SHA-256 fingerprint in OpenSSH's own format
         * @return true to accept and remember
         */
        boolean accept(String host, String type, String fingerprint, boolean changed);
    }

    private static File knownHosts(Context c) {
        File f = new File(c.getFilesDir(), "known_hosts");
        if (!f.exists()) {
            try { f.createNewFile(); } catch (Throwable ignored) { }
        }
        return f;
    }

    public static String knownHostsPath(Context c) {
        return knownHosts(c).getAbsolutePath();
    }

    public static List<String> knownHostList(Context c) {
        List<String> out = new ArrayList<>();
        try {
            JSch j = new JSch();
            j.setKnownHosts(knownHostsPath(c));
            HostKeyRepository r = j.getHostKeyRepository();
            HostKey[] keys = r.getHostKey();
            if (keys != null) {
                for (HostKey k : keys) {
                    out.add(k.getHost() + "   " + k.getType() + "   "
                            + k.getFingerPrint(j));
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    public static void forgetHostKeys(Context c) {
        try {
            File f = knownHosts(c);
            FileOutputStream o = new FileOutputStream(f, false);
            o.close();
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ connection

    public static final class Conn {
        Session session;
        public String banner = "";
        public String error;
        public boolean ok() { return session != null && session.isConnected(); }
        public void close() {
            try { if (session != null) session.disconnect(); } catch (Throwable ignored) { }
            session = null;
        }
    }

    /**
     * Open a session. Blocking — call it off the main thread.
     *
     * @param pass       password, or empty when using a key
     * @param passphrase key passphrase, or empty
     */
    public static Conn connect(Context ctx, Host h, String pass, String passphrase,
                               final Trust trust) {
        Conn conn = new Conn();
        try {
            JSch jsch = new JSch();
            jsch.setKnownHosts(knownHostsPath(ctx));

            if (h.keyPath != null && !h.keyPath.isEmpty()) {
                if (passphrase != null && !passphrase.isEmpty()) {
                    jsch.addIdentity(h.keyPath, passphrase);
                } else {
                    jsch.addIdentity(h.keyPath);
                }
            }

            Session s = jsch.getSession(h.user, h.host, h.port);
            if (pass != null && !pass.isEmpty()) s.setPassword(pass);

            Properties cfg = new Properties();
            // "ask" rather than "no". The UserInfo below turns the ask into a prompt
            // that shows the fingerprint; "no" would silently trust anything.
            cfg.put("StrictHostKeyChecking", "ask");
            cfg.put("PreferredAuthentications", "publickey,keyboard-interactive,password");
            s.setConfig(cfg);

            final String hostName = h.host;
            s.setUserInfo(new UserInfo() {
                @Override public String getPassphrase() { return null; }
                @Override public String getPassword() { return null; }
                @Override public boolean promptPassword(String m) { return false; }
                @Override public boolean promptPassphrase(String m) { return false; }
                @Override public boolean promptYesNo(String message) {
                    // JSch funnels both "unknown key" and "key has CHANGED" through
                    // here with only a message to tell them apart. The second is the
                    // one that matters, so it is detected and passed on explicitly.
                    boolean changed = message != null
                            && message.toLowerCase(java.util.Locale.US).contains("changed");
                    String fp = extract(message);
                    return trust != null && trust.accept(hostName, "", fp, changed);
                }
                @Override public void showMessage(String message) {
                    conn.banner = message == null ? "" : message;
                }
            });

            s.setServerAliveInterval(20000);
            s.connect(15000);
            conn.session = s;
        } catch (Throwable t) {
            conn.error = String.valueOf(t.getMessage());
        }
        return conn;
    }

    /** Pull the fingerprint line out of JSch's prompt text. */
    private static String extract(String message) {
        if (message == null) return "unknown";
        for (String line : message.split("\n")) {
            String l = line.trim();
            if (l.toLowerCase(java.util.Locale.US).contains("fingerprint")) {
                int i = l.indexOf("is ");
                return i >= 0 ? l.substring(i + 3).replace(".", "").trim() : l;
            }
        }
        return message.trim();
    }

    // ------------------------------------------------------------------ running

    public static final class Out {
        public String text = "";
        public int exit = -1;
        public String error;
    }

    /** Run one command and collect everything it prints. */
    public static Out exec(Conn conn, String command, int timeoutSec) {
        Out o = new Out();
        if (conn == null || !conn.ok()) { o.error = "not connected"; return o; }
        ChannelExec ch = null;
        try {
            ch = (ChannelExec) conn.session.openChannel("exec");
            ch.setCommand(command);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            ch.setOutputStream(out);
            ch.setErrStream(err);
            InputStream in = ch.getInputStream();
            ch.connect(10000);

            long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
            byte[] buf = new byte[8192];
            while (true) {
                while (in.available() > 0) {
                    int n = in.read(buf, 0, buf.length);
                    if (n < 0) break;
                    out.write(buf, 0, n);
                }
                if (ch.isClosed()) break;
                if (System.currentTimeMillis() > deadline) {
                    o.error = "timed out after " + timeoutSec + " s";
                    break;
                }
                try { Thread.sleep(60); } catch (InterruptedException ignored) { }
            }
            o.text = out.toString("UTF-8");
            String e = err.toString("UTF-8");
            if (!e.isEmpty()) o.text += (o.text.endsWith("\n") ? "" : "\n") + e;
            o.exit = ch.getExitStatus();
        } catch (Throwable t) {
            o.error = String.valueOf(t.getMessage());
        } finally {
            if (ch != null) try { ch.disconnect(); } catch (Throwable ignored) { }
        }
        return o;
    }

    // ------------------------------------------------------------------ files

    public static final class Entry {
        public String name, perms;
        public boolean dir, link;
        public long size, modified;
    }

    public static final class Listing {
        public String path = "/";
        public final List<Entry> entries = new ArrayList<>();
        public String error;
    }

    private static ChannelSftp sftp(Conn conn) throws Exception {
        ChannelSftp ch = (ChannelSftp) conn.session.openChannel("sftp");
        ch.connect(10000);
        return ch;
    }

    public static Listing list(Conn conn, String path) {
        Listing l = new Listing();
        l.path = path;
        if (conn == null || !conn.ok()) { l.error = "not connected"; return l; }
        ChannelSftp ch = null;
        try {
            ch = sftp(conn);
            if (path == null || path.isEmpty()) { path = ch.pwd(); l.path = path; }
            ch.cd(path);
            l.path = ch.pwd();
            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> v = ch.ls(l.path);
            for (ChannelSftp.LsEntry e : v) {
                String n = e.getFilename();
                if (".".equals(n) || "..".equals(n)) continue;
                SftpATTRS a = e.getAttrs();
                Entry x = new Entry();
                x.name = n;
                x.dir = a.isDir();
                x.link = a.isLink();
                x.size = a.getSize();
                x.modified = a.getMTime() * 1000L;
                x.perms = a.getPermissionsString();
                l.entries.add(x);
            }
            java.util.Collections.sort(l.entries, new java.util.Comparator<Entry>() {
                @Override public int compare(Entry a, Entry b) {
                    if (a.dir != b.dir) return a.dir ? -1 : 1;
                    return a.name.compareToIgnoreCase(b.name);
                }
            });
        } catch (Throwable t) {
            l.error = String.valueOf(t.getMessage());
        } finally {
            if (ch != null) try { ch.disconnect(); } catch (Throwable ignored) { }
        }
        return l;
    }

    /** Remote to local. Returns null on success, else the reason. */
    public static String download(Conn conn, String remote, File local) {
        if (conn == null || !conn.ok()) return "not connected";
        ChannelSftp ch = null;
        OutputStream os = null;
        try {
            ch = sftp(conn);
            os = new FileOutputStream(local);
            ch.get(remote, os);
            return null;
        } catch (Throwable t) {
            return String.valueOf(t.getMessage());
        } finally {
            if (os != null) try { os.close(); } catch (Throwable ignored) { }
            if (ch != null) try { ch.disconnect(); } catch (Throwable ignored) { }
        }
    }

    /** Local to remote. Returns null on success, else the reason. */
    public static String upload(Conn conn, File local, String remote) {
        if (conn == null || !conn.ok()) return "not connected";
        ChannelSftp ch = null;
        InputStream in = null;
        try {
            ch = sftp(conn);
            in = new FileInputStream(local);
            ch.put(in, remote);
            return null;
        } catch (Throwable t) {
            return String.valueOf(t.getMessage());
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
            if (ch != null) try { ch.disconnect(); } catch (Throwable ignored) { }
        }
    }

    public static String describe(Conn conn) {
        if (conn == null || !conn.ok()) return "not connected";
        try {
            Session s = conn.session;
            return s.getUserName() + "@" + s.getHost() + ":" + s.getPort()
                 + "   ·   " + s.getServerVersion();
        } catch (Throwable t) {
            return "connected";
        }
    }
}
