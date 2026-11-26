package com.boresight;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Ask GitHub whether there is a newer release than the one running.
 *
 * Only ever when the person presses the button. There is no background poll, no daily
 * check and no "we've updated our terms" — this app's whole privacy claim is that
 * nothing leaves the device unless the user sends it, and a silent version ping would
 * make that claim false for the sake of a convenience nobody asked for.
 *
 * The request carries no identifiers. It is an unauthenticated GET of a public release
 * listing with a generic user agent, so what GitHub learns is that somebody looked —
 * not who, not where, and nothing about what has been surveyed. The About screen says
 * so, because a privacy claim that quietly acquired an exception is worse than one that
 * never existed.
 */
public final class Update {

    /** The public release listing. Changing owner or repo is the only knob here. */
    private static final String LATEST =
            "https://api.github.com/repos/Teulis/boresight/releases/latest";

    private static final int TIMEOUT_MS = 12_000;

    public static final class Result {
        /** The tag GitHub reports, e.g. "v2026.11.26.14". Null when the call failed. */
        public String latest;
        /** What this build calls itself. */
        public String running = Stamp.VERSION;
        /** Where a person would go to fetch it. */
        public String page;
        /** True only when latest is both known and different from running. */
        public boolean newer;
        /** Why there is no answer, when there is no answer. */
        public String problem;

        public String summary() {
            if (problem != null) return problem;
            if (latest == null) return "no release found";
            if (newer) return latest + " is available — you are on " + running;
            return "up to date — " + running;
        }
    }

    private Update() { }

    /**
     * Blocking. Call it off the main thread.
     */
    public static Result check(Context c) {
        Result r = new Result();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(LATEST).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            // Generic on purpose: GitHub asks clients to identify themselves, and the
            // app's name and version are all it is owed. No device, no id, no account.
            conn.setRequestProperty("User-Agent", "Boresight/" + Stamp.VERSION);

            int code = conn.getResponseCode();
            if (code == 404) {
                r.problem = "no releases published yet";
                return r;
            }
            if (code == 403) {
                // Unauthenticated GitHub API is rate limited by address. Saying so beats
                // "failed", because waiting actually fixes it.
                r.problem = "GitHub is rate limiting this address — try later";
                return r;
            }
            if (code != 200) {
                r.problem = "GitHub returned " + code;
                return r;
            }

            StringBuilder b = new StringBuilder();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            try {
                String line;
                while ((line = in.readLine()) != null) b.append(line);
            } finally { in.close(); }

            JSONObject o = new JSONObject(b.toString());
            r.latest = o.optString("tag_name", null);
            r.page = o.optString("html_url", null);
            r.newer = r.latest != null && !r.latest.isEmpty() && !sameVersion(r.latest, r.running);
            Logs.i("update", "latest " + r.latest + ", running " + r.running);
            return r;
        } catch (java.net.UnknownHostException e) {
            r.problem = "no network";
            Logs.why("update", "check skipped", "no network");
            return r;
        } catch (Throwable e) {
            r.problem = "could not reach GitHub";
            Logs.e("update", "check failed", e);
            return r;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
        }
    }

    /**
     * Compare a release tag with a build's version name.
     *
     * String equality after dropping a leading "v", rather than parsing the date into
     * components. The tags are already ordered by their own text and a wrong guess at
     * the scheme would quietly claim an old build is current, which is the one failure
     * worth avoiding — being told "update available" when you are current costs a tap.
     */
    static boolean sameVersion(String tag, String running) {
        if (tag == null || running == null) return false;
        return strip(tag).equals(strip(running));
    }

    private static String strip(String s) {
        String t = s.trim();
        if (t.startsWith("v") || t.startsWith("V")) t = t.substring(1);
        return t;
    }
}
