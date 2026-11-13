package com.dozeguard;

import java.util.LinkedHashSet;

/**
 * Contents of /data/system/dozeguard.conf.
 *
 * <pre>
 * mode=blacklist|whitelist
 * pkg=com.example.one
 * </pre>
 *
 * The pkg list always means "the user's selection". In blacklist mode a selected
 * package is stripped from the Doze whitelist; in whitelist mode only selected
 * packages may stay in it.
 */
public final class DwConfig {

    public static final String PATH = "/data/system/dozeguard.conf";

    public boolean whitelistMode = false;
    public final LinkedHashSet<String> pkgs = new LinkedHashSet<>();

    public static final String[] DEFAULT_BLACKLIST = {
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.taobao.taobao",
            "com.jingdong.app.mall"
    };

    public static DwConfig defaults() {
        DwConfig c = new DwConfig();
        for (String p : DEFAULT_BLACKLIST) c.pkgs.add(p);
        return c;
    }

    public static DwConfig parse(String text) {
        if (text == null) return defaults();
        DwConfig c = new DwConfig();
        int count = 0;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim().toLowerCase();
            String v = line.substring(eq + 1).trim();
            if (k.equals("mode")) {
                c.whitelistMode = v.equalsIgnoreCase("whitelist");
                count++;
            } else if (k.equals("pkg") && !v.isEmpty()) {
                c.pkgs.add(v);
                count++;
            }
        }
        return count == 0 ? defaults() : c;
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append("# DozeGuard — written by the app UI, read by the system_server hook\n");
        sb.append("# mode=blacklist -> pkg lines are BLOCKED; mode=whitelist -> pkg lines are ALLOWED\n");
        sb.append("mode=").append(whitelistMode ? "whitelist" : "blacklist").append('\n');
        for (String p : pkgs) sb.append("pkg=").append(p).append('\n');
        return sb.toString();
    }

    /** true = this package must be stripped from the Doze whitelist. */
    public boolean blocks(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (whitelistMode) {
            // Never starve the framework itself; everything else needs an explicit opt-in.
            if (pkg.equals("android") || pkg.startsWith("com.android.")) return false;
            return !pkgs.contains(pkg);
        }
        return pkgs.contains(pkg);
    }
}
