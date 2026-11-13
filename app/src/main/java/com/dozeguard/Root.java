package com.dozeguard;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** Thin wrapper around su, with a timeout so a hung prompt can never freeze the UI. */
public final class Root {

    public static final class Result {
        public final int code;
        public final String out;

        Result(int code, String out) {
            this.code = code;
            this.out = out;
        }

        public String trim() {
            return out == null ? "" : out.trim();
        }

        @Override
        public String toString() {
            return "code=" + code + " out=" + trim();
        }
    }

    private static final String[] SU = {"/system/bin/su", "su", "/sbin/su", "/su/bin/su"};

    private Root() {
    }

    public static Result run(String cmd) {
        return run(cmd, 20000);
    }

    public static Result run(String cmd, int timeoutMs) {
        for (String su : SU) {
            Result r = exec(su, cmd, timeoutMs);
            if (r != null) return r;
        }
        return new Result(-1, "su not found");
    }

    private static Result exec(String su, String cmd, int timeoutMs) {
        final Process p;
        try {
            p = new ProcessBuilder(su, "-c", cmd).redirectErrorStream(true).start();
        } catch (Exception e) {
            return null; // binary missing, try the next candidate
        }
        final StringBuilder sb = new StringBuilder();
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try (InputStream in = p.getInputStream();
                     BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                } catch (Exception ignored) {
                }
            }
        });
        reader.setDaemon(true);
        reader.start();
        try {
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroy();
                return new Result(-2, "timeout: " + sb);
            }
            reader.join(2000);
            return new Result(p.exitValue(), sb.toString());
        } catch (InterruptedException e) {
            p.destroy();
            return new Result(-3, "interrupted");
        }
    }

    public static boolean available() {
        Result r = run("id", 8000);
        return r.code == 0 && r.trim().contains("uid=0");
    }

    /** Writes text to a root-only path without any shell quoting problems. */
    public static Result writeFile(String path, String text) {
        String b64 = android.util.Base64.encodeToString(
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP);
        String cmd = "echo '" + b64 + "' | base64 -d > '" + path + "'"
                + " && chmod 644 '" + path + "' && ls -l '" + path + "'";
        return run(cmd);
    }

    public static String readFile(String path) {
        Result r = run("cat '" + path + "' 2>/dev/null");
        return r.code == 0 ? r.out : null;
    }
}
