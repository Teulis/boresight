package com.boresight;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSH into network devices, and move files both ways.
 *
 * Command-at-a-time rather than a terminal emulator. That is a deliberate limit and it
 * is stated on screen: a real PTY means implementing VT100 — cursor addressing, scroll
 * regions, colour, resize — and half an emulator is worse than none, because top and vi
 * would start and then draw garbage. Each command here runs in its own exec channel and
 * returns its output and exit status, which is what a survey tool is actually for:
 * checking a radio, reading a config, restarting a service.
 */
public class SshActivity extends Screen {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService pool;

    private EditText label, host, port, user, pass, keyPath, command;
    private TextView consolePlaceholder;
    private CheckBox savePass;
    private TextView state, console, remotePath;
    private ScrollView consoleScroll;
    private LinearLayout savedList, fileList;

    private Ssh.Conn conn;
    private String cwd = "";

    @Override protected String title() { return "SSH"; }

    @Override protected void content() {
        pool = Executors.newSingleThreadExecutor();

        // --- connection ------------------------------------------------------
        LinearLayout box = pane("Connection");
        state = kv(box, "Status", "not connected");

        LinearLayout grid = th.column(this);
        label   = field(grid, "name (optional)", InputType.TYPE_CLASS_TEXT);
        host    = field(grid, "host or IP", InputType.TYPE_CLASS_TEXT);
        port    = field(grid, "port (22)", InputType.TYPE_CLASS_NUMBER);
        user    = field(grid, "username", InputType.TYPE_CLASS_TEXT);
        pass    = field(grid, "password", InputType.TYPE_CLASS_TEXT
                                        | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyPath = field(grid, "private key file (optional)", InputType.TYPE_CLASS_TEXT);
        box.addView(grid, Theme.wrap());

        savePass = new CheckBox(this);
        savePass.setText("Remember password on this device");
        savePass.setTextColor(skin.inkSecondary);
        savePass.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        box.addView(savePass);

        LinearLayout crow = actionRow(box);
        smallAction(crow, "Connect", new Runnable() {
            @Override public void run() { connect(); }
        });
        smallAction(crow, "Disconnect", new Runnable() {
            @Override public void run() { disconnect(); }
        });
        smallAction(crow, "Save host", new Runnable() {
            @Override public void run() { saveHost(); }
        });
        smallAction(crow, "Host keys", new Runnable() {
            @Override public void run() { showHostKeys(); }
        });

        hint(box, Secret.available(this)
                ? "A remembered password is encrypted with a key held in this phone's "
                + "hardware keystore, which cannot be exported — not even by this app. "
                + "Leave the box unticked and nothing is written down."
                : "This device's keystore is not usable, so passwords will not be "
                + "remembered. Nothing is written in the clear as a fallback.");

        LinearLayout saved = pane("Saved hosts");
        savedList = th.column(this);
        saved.addView(savedList, Theme.wrap());

        // --- run -------------------------------------------------------------
        LinearLayout run = pane("Run a command");
        command = field(run, "uptime", InputType.TYPE_CLASS_TEXT
                                     | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout rrow = actionRow(run);
        smallAction(rrow, "Run", new Runnable() {
            @Override public void run() { runCommand(command.getText().toString()); }
        });
        for (final String[] q : new String[][]{
                {"uptime", "uptime"},
                {"disk", "df -h"},
                {"mem", "free -m || vmstat"},
                {"who", "w || who"},
                {"net", "ip -br a 2>/dev/null || ifconfig"},
        }) {
            smallAction(rrow, q[0], new Runnable() {
                @Override public void run() { runCommand(q[1]); }
            });
        }

        // Collapsed until there is something in it. A fixed 300dp box left a third of
        // the screen as an empty black void above the buttons before you had ever run
        // anything, which read as a rendering fault rather than as an empty log.
        consolePlaceholder = th.text(this, "Output appears here.", 11, skin.inkSecondary);
        run.addView(consolePlaceholder);

        consoleScroll = new ScrollView(this);
        LinearLayout.LayoutParams slp = Theme.wrap();
        slp.height = 0;
        console = th.num(this, "", 10, skin.inkPrimary);
        console.setTypeface(Typeface.MONOSPACE);
        console.setTextIsSelectable(true);
        consoleScroll.addView(console);
        run.addView(consoleScroll, slp);

        LinearLayout orow = actionRow(run);
        smallAction(orow, "Clear", new Runnable() {
            @Override public void run() { console.setText(""); sizeConsole(); }
        });
        smallAction(orow, "Copy", new Runnable() {
            @Override public void run() {
                android.content.ClipboardManager cb = (android.content.ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                cb.setPrimaryClip(android.content.ClipData.newPlainText(
                        "ssh", console.getText().toString()));
                toast("Copied");
            }
        });
        hint(run, "Each command runs in its own channel, so there is no shell state "
                + "between them: a cd does not persist. This is not a terminal "
                + "emulator — interactive programs like top or vi will not draw "
                + "correctly, so run their batch forms instead (top -b -n1).");

        // --- files -------------------------------------------------------------
        LinearLayout files = pane("Files");
        remotePath = th.num(this, "not connected", 11, skin.accent(Skin.CH_RSRP));
        remotePath.setTextIsSelectable(true);
        files.addView(remotePath);

        LinearLayout frow = actionRow(files);
        smallAction(frow, "Home", new Runnable() {
            @Override public void run() { browse(""); }
        });
        smallAction(frow, "Up", new Runnable() {
            @Override public void run() { browse(parentOf(cwd)); }
        });
        smallAction(frow, "Go to…", new Runnable() {
            @Override public void run() {
                ask("Remote path", cwd, new Consumer<String>() {
                    @Override public void accept(String p) { if (p != null) browse(p.trim()); }
                });
            }
        });
        smallAction(frow, "Upload…", new Runnable() {
            @Override public void run() { chooseUpload(); }
        });
        fileList = th.column(this);
        files.addView(fileList, Theme.wrap());
        hint(files, "Transfers use SFTP, which is what OpenSSH's own scp has used "
                  + "since version 9. Downloads land in this app's Documents folder "
                  + "and are reachable from the Files screen.");

        refreshSaved();
    }

    private EditText field(LinearLayout into, String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setTextColor(skin.inkPrimary);
        e.setHintTextColor(skin.inkSecondary);
        e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        e.setTypeface(Typeface.MONOSPACE);
        into.addView(e, Theme.wrap());
        return e;
    }

    // ------------------------------------------------------------------ hosts

    private void refreshSaved() {
        savedList.removeAllViews();
        List<Ssh.Host> list = Ssh.hosts(this);
        if (list.isEmpty()) {
            savedList.addView(th.text(this, "None saved yet.", 11, skin.inkSecondary));
            return;
        }
        for (final Ssh.Host h : list) {
            LinearLayout row = th.column(this);
            row.setPadding(0, th.dp(8), 0, th.dp(8));
            row.setBackground(th.ripple(th.solid(0x00000000, 0)));
            row.setClickable(true);
            TextView a = th.num(this, h.title(), 13, skin.accent(Skin.CH_RSRP));
            a.setTypeface(skin.face(true, Typeface.BOLD));
            row.addView(a);
            row.addView(th.text(this, h.subtitle(), 10, skin.inkSecondary));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { load(h); }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { confirmForget(h); return true; }
            });
            savedList.addView(row);
        }
    }

    private void load(Ssh.Host h) {
        label.setText(h.label);
        host.setText(h.host);
        port.setText(String.valueOf(h.port));
        user.setText(h.user);
        keyPath.setText(h.keyPath);
        savePass.setChecked(h.savePass);
        pass.setText(h.savePass ? Secret.open(h.sealedPass) : "");
        toast("Loaded " + h.title());
    }

    private Ssh.Host current() {
        Ssh.Host h = new Ssh.Host();
        h.label = label.getText().toString().trim();
        h.host = host.getText().toString().trim();
        h.user = user.getText().toString().trim();
        h.keyPath = keyPath.getText().toString().trim();
        try { h.port = Integer.parseInt(port.getText().toString().trim()); }
        catch (Exception e) { h.port = 22; }
        h.savePass = savePass.isChecked();
        return h;
    }

    private void saveHost() {
        Ssh.Host h = current();
        if (h.host.isEmpty()) { toast("Enter a host"); return; }
        if (h.savePass) {
            String sealed = Secret.seal(pass.getText().toString());
            if (sealed == null) {
                info("Not saved", "The keystore refused to encrypt the password, and "
                        + "this app will not write one in the clear as a fallback. "
                        + "The host has been saved without it.");
                h.savePass = false;
            } else {
                h.sealedPass = sealed;
            }
        }
        List<Ssh.Host> list = Ssh.hosts(this);
        for (int i = 0; i < list.size(); i++) {
            Ssh.Host e = list.get(i);
            if (e.host.equals(h.host) && e.port == h.port && e.user.equals(h.user)) {
                list.set(i, h);
                Ssh.saveHosts(this, list);
                refreshSaved();
                toast("Updated");
                return;
            }
        }
        list.add(h);
        Ssh.saveHosts(this, list);
        refreshSaved();
        toast("Saved");
    }

    private void confirmForget(final Ssh.Host h) {
        Sheet.of(this, "Forget " + h.title() + "?")
                .text("Removes the saved details, including any stored password.")
                .action("Forget", true, new Runnable() {
                    @Override public void run() {
                        List<Ssh.Host> list = Ssh.hosts(SshActivity.this);
                        for (int i = 0; i < list.size(); i++) {
                            Ssh.Host e = list.get(i);
                            if (e.host.equals(h.host) && e.port == h.port
                                    && e.user.equals(h.user)) { list.remove(i); break; }
                        }
                        Ssh.saveHosts(SshActivity.this, list);
                        refreshSaved();
                    }
                })
                .close("Cancel")
                .show();
    }

    private void showHostKeys() {
        List<String> keys = Ssh.knownHostList(this);
        StringBuilder b = new StringBuilder();
        if (keys.isEmpty()) b.append("No host keys trusted yet.");
        else for (String k : keys) b.append(k).append("\n\n");
        b.append("\nThese are the servers this app will connect to without asking "
               + "again. A key that changes raises a warning rather than connecting.");
        Sheet.of(this, "Trusted host keys")
                .mono(b.toString())
                .action("Forget all", true, new Runnable() {
                    @Override public void run() {
                        Ssh.forgetHostKeys(SshActivity.this);
                        toast("Host keys cleared");
                    }
                })
                .close("Close")
                .show();
    }

    // ------------------------------------------------------------------ session

    private void connect() {
        final Ssh.Host h = current();
        if (h.host.isEmpty() || h.user.isEmpty()) {
            toast("Host and username are needed"); return;
        }
        final String pw = pass.getText().toString();
        line("$ connecting to " + h.user + "@" + h.host + ":" + h.port);
        state.setText("connecting…");
        pool.execute(new Runnable() {
            @Override public void run() {
                final Ssh.Conn c = Ssh.connect(SshActivity.this, h, pw, "", prompt);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (c.ok()) {
                            conn = c;
                            state.setText(Ssh.describe(c));
                            line("-- connected --");
                            if (c.banner != null && !c.banner.isEmpty()) line(c.banner);
                            browse("");
                        } else {
                            state.setText("not connected");
                            line("-- failed: " + c.error + " --");
                        }
                    }
                });
            }
        });
    }

    /**
     * The host-key question, asked on the connecting thread and answered on the UI one.
     *
     * JSch calls this from inside connect(), so the background thread has to stop and
     * wait for a person. A latch is the honest way to express that; the alternative,
     * auto-accepting, is what makes an SSH client worthless against a hostile network.
     */
    private final Ssh.Trust prompt = new Ssh.Trust() {
        @Override public boolean accept(final String host, String type,
                                        final String fingerprint, final boolean changed) {
            final AtomicBoolean yes = new AtomicBoolean(false);
            final CountDownLatch latch = new CountDownLatch(1);
            ui.post(new Runnable() {
                @Override public void run() {
                    String msg = changed
                            ? "The key for " + host + " has CHANGED since you last "
                            + "connected.\n\n" + fingerprint
                            + "\n\nThis is what an interception looks like. It is also "
                            + "what a rebuilt or re-imaged device looks like. If you "
                            + "did not rebuild it, say no."
                            : host + " is offering a key this phone has not seen "
                            + "before.\n\n" + fingerprint
                            + "\n\nCompare it with the server's own fingerprint before "
                            + "accepting. Accepting remembers it, and you will not be "
                            + "asked again.";
                    Sheet.of(SshActivity.this,
                                    changed ? "Host key CHANGED" : "Unknown host key")
                            .mono(msg)
                            .sticky()
                            .action(changed ? "Accept anyway" : "Accept", true,
                                    new Runnable() {
                                @Override public void run() {
                                    yes.set(true); latch.countDown();
                                }
                            })
                            .action("Refuse", true, new Runnable() {
                                @Override public void run() {
                                    yes.set(false); latch.countDown();
                                }
                            })
                            .show();
                }
            });
            try { latch.await(); } catch (InterruptedException e) { return false; }
            return yes.get();
        }
    };

    private void disconnect() {
        if (conn != null) { conn.close(); conn = null; }
        state.setText("not connected");
        remotePath.setText("not connected");
        fileList.removeAllViews();
        line("-- disconnected --");
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (conn != null) conn.close();
    }

    private void runCommand(final String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) return;
        if (conn == null || !conn.ok()) { toast("Not connected"); return; }
        line("$ " + cmd);
        pool.execute(new Runnable() {
            @Override public void run() {
                final Ssh.Out o = Ssh.exec(conn, cmd, 30);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (o.error != null) line("-- " + o.error + " --");
                        if (o.text != null && !o.text.isEmpty()) line(o.text.trim());
                        if (o.exit > 0) line("-- exit " + o.exit + " --");
                    }
                });
            }
        });
    }

    private void line(final String s) {
        console.append(s + "\n");
        sizeConsole();
        consoleScroll.post(new Runnable() {
            @Override public void run() { consoleScroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    /** Give the log room once it has something in it, and none before. */
    private void sizeConsole() {
        boolean empty = console.getText().length() == 0;
        consolePlaceholder.setVisibility(empty ? View.VISIBLE : View.GONE);
        ViewGroup.LayoutParams lp = consoleScroll.getLayoutParams();
        int want = empty ? 0 : th.dp(300);
        if (lp.height != want) {
            lp.height = want;
            consoleScroll.setLayoutParams(lp);
        }
    }

    // ------------------------------------------------------------------ files

    private static String parentOf(String p) {
        if (p == null || p.isEmpty() || "/".equals(p)) return "/";
        String q = p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
        int i = q.lastIndexOf('/');
        return i <= 0 ? "/" : q.substring(0, i);
    }

    private void browse(final String path) {
        if (conn == null || !conn.ok()) { toast("Not connected"); return; }
        remotePath.setText("listing…");
        pool.execute(new Runnable() {
            @Override public void run() {
                final Ssh.Listing l = Ssh.list(conn, path);
                ui.post(new Runnable() {
                    @Override public void run() { paint(l); }
                });
            }
        });
    }

    private void paint(Ssh.Listing l) {
        fileList.removeAllViews();
        if (l.error != null) {
            remotePath.setText(l.path);
            fileList.addView(th.text(this, l.error, 11, skin.accent(Skin.CH_ALERT)));
            return;
        }
        cwd = l.path;
        remotePath.setText(l.path + "   ·   " + l.entries.size() + " entries");
        for (final Ssh.Entry e : l.entries) {
            LinearLayout row = th.column(this);
            row.setPadding(0, th.dp(6), 0, th.dp(6));
            row.setBackground(th.ripple(th.solid(0x00000000, 0)));
            row.setClickable(true);
            TextView a = th.num(this, (e.dir ? "[ " + e.name + " ]" : e.name), 11,
                    e.dir ? skin.accent(Skin.CH_RSRP) : skin.inkPrimary);
            row.addView(a);
            row.addView(th.text(this, e.perms + "   " + human(e.size)
                    + (e.link ? "   link" : ""), 9, skin.inkSecondary));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (e.dir) browse(join(cwd, e.name)); else fileActions(e);
                }
            });
            fileList.addView(row);
        }
    }

    private static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    private static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0);
    }

    private void fileActions(final Ssh.Entry e) {
        final String[] what = {"Download", "View as text", "Delete"};
        Sheet s = Sheet.of(this, e.name);
        for (int i = 0; i < what.length; i++) {
            final int w = i;
            s.choice(what[i], null, false, new Runnable() {
                @Override public void run() {
                    switch (w) {
                        case 0: download(e); break;
                        case 1: runCommand("cat " + shellQuote(join(cwd, e.name))); break;
                        case 2: confirmDelete(e); break;
                    }
                }
            });
        }
        s.close("Cancel").show();
    }

    /** Single quotes with the standard close-escape-reopen trick for embedded quotes. */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private void confirmDelete(final Ssh.Entry e) {
        Sheet.of(this, "Delete on the server?")
                .mono(join(cwd, e.name))
                .text("This runs on the remote machine and there is no undo.")
                .action("Delete", true, new Runnable() {
                    @Override public void run() {
                        runCommand("rm -f " + shellQuote(join(cwd, e.name)));
                        ui.postDelayed(new Runnable() {
                            @Override public void run() { browse(cwd); }
                        }, 900);
                    }
                })
                .close("Cancel")
                .show();
    }

    private void download(final Ssh.Entry e) {
        final File dir = new File(getExternalFilesDir(null), "ssh");
        dir.mkdirs();
        final File out = new File(dir, e.name);
        line("$ get " + e.name);
        pool.execute(new Runnable() {
            @Override public void run() {
                final String err = Ssh.download(conn, join(cwd, e.name), out);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (err == null) {
                            line("-- saved " + human(out.length()) + " to "
                                    + out.getAbsolutePath() + " --");
                        } else {
                            line("-- download failed: " + err + " --");
                        }
                    }
                });
            }
        });
    }

    private void chooseUpload() {
        if (conn == null || !conn.ok()) { toast("Not connected"); return; }
        final File dir = new File(getExternalFilesDir(null), "ssh");
        dir.mkdirs();
        final File[] kids = dir.listFiles();
        if (kids == null || kids.length == 0) {
            info("Nothing to upload", "Put files in\n" + dir.getAbsolutePath()
                    + "\n\nand they will be listed here. The Files screen can copy "
                    + "anything into that folder.");
            return;
        }
        final String[] names = new String[kids.length];
        for (int i = 0; i < kids.length; i++) names[i] = kids[i].getName();
        Sheet s = Sheet.of(this, "Upload to " + cwd);
        for (int i = 0; i < names.length; i++) {
            final int w = i;
            s.choice(names[i], null, false, new Runnable() {
                @Override public void run() {
                    line("$ put " + names[w]);
                    pool.execute(new Runnable() {
                        @Override public void run() {
                            final String err = Ssh.upload(conn, kids[w],
                                    join(cwd, names[w]));
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    line(err == null ? "-- uploaded --"
                                                     : "-- failed: " + err + " --");
                                    browse(cwd);
                                }
                            });
                        }
                    });
                }
            });
        }
        s.close("Cancel").show();
    }
}
