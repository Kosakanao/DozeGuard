package com.dozeguard;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Runs inside system_server (scope = system).
 *
 * ColorOS/OPPO re-applies /data/oplus/os/battery/doze_wl_local.xml to the system
 * Doze whitelist every time the device enters or leaves light doze, through
 * DeviceIdleController.addPowerSaveWhitelistAppsInternal(List). We filter that
 * list (and the single-package entry points) before the framework applies it,
 * using the selection stored in /data/system/dozeguard.conf.
 */
public final class DozeGuard extends XposedModule {

    private static final String TAG = "DozeGuard";
    private static final String LOG = "/data/system/dozeguard.log";

    private static final String[] CLASS_CANDIDATES = {
            "com.android.server.DeviceIdleController",
            "com.android.server.deviceidle.DeviceIdleController"
    };

    private static volatile DwConfig sConfig = DwConfig.defaults();
    private static long sMtime = -1L;
    private static long sLastCheck = 0L;

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        try {
            ClassLoader cl = param.getClassLoader();
            Class<?> controller = null;
            for (String name : CLASS_CANDIDATES) {
                try {
                    controller = Class.forName(name, false, cl);
                    break;
                } catch (Throwable ignored) {
                }
            }
            if (controller == null) {
                L("ERROR DeviceIdleController not found");
                return;
            }
            L("### controller = " + controller.getName());
            reload(true);

            int installed = 0;
            for (Method m : controller.getDeclaredMethods()) {
                if (Modifier.isAbstract(m.getModifiers())) continue;
                String name = m.getName();
                if (name.startsWith("-")) continue;             // synthetic accessors
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.startsWith("add") || !lower.contains("powersavewhitelist")) continue;

                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 1) continue;
                final boolean bulk = List.class.isAssignableFrom(ps[0]);
                final boolean single = ps[0] == String.class;
                if (!bulk && !single) continue;
                final Class<?> ret = m.getReturnType();

                try {
                    hook(m).intercept(chain -> {
                        try {
                            reload(false);
                            return bulk ? onBulk(chain) : onSingle(chain, ret);
                        } catch (Throwable t) {
                            L("ERROR in hook " + m.getName() + ": " + t);
                            return chain.proceed();
                        }
                    });
                    installed++;
                    L("hooked: " + name + "(" + ps[0].getSimpleName() + ") -> " + ret.getSimpleName());
                } catch (Throwable t) {
                    L("ERROR hooking " + name + ": " + t);
                }
            }
            L("### installed on " + installed + " method(s); mode="
                    + (sConfig.whitelistMode ? "whitelist" : "blacklist")
                    + " pkgs=" + sConfig.pkgs.size());
        } catch (Throwable t) {
            L("FATAL onSystemServerStarting: " + t);
        }
    }

    /** A whole XML worth of packages arrives here as an (immutable) List. */
    private Object onBulk(XposedInterface.Chain chain) throws Throwable {
        Object arg = chain.getArg(0);
        if (!(arg instanceof List)) return chain.proceed();

        List<?> incoming = (List<?>) arg;
        List<Object> kept = new ArrayList<>(incoming.size());
        List<String> blocked = new ArrayList<>(0);
        for (Object o : incoming) {
            if (o instanceof String && sConfig.blocks((String) o)) blocked.add((String) o);
            else kept.add(o);
        }
        if (blocked.isEmpty()) return chain.proceed();

        L("BLOCKED bulk " + blocked + " kept=" + kept.size() + "/" + incoming.size());
        return chain.proceed(new Object[]{ kept });
    }

    /** Single-package path (dumpsys, package-added recalc, ...). */
    private Object onSingle(XposedInterface.Chain chain, Class<?> ret) throws Throwable {
        Object arg = chain.getArg(0);
        if (arg instanceof String && sConfig.blocks((String) arg)) {
            L("BLOCKED single [" + arg + "]");
            if (ret == boolean.class) return Boolean.FALSE;
            if (ret == int.class) return 0;
            return null;
        }
        return chain.proceed();
    }

    private static void reload(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - sLastCheck < 1500L) return;
        sLastCheck = now;
        try {
            File f = new File(DwConfig.PATH);
            if (!f.exists()) {
                if (force) {
                    sConfig = DwConfig.defaults();
                    L("config missing, defaults: " + sConfig.pkgs);
                }
                return;
            }
            long mt = f.lastModified();
            if (!force && mt == sMtime) return;
            sMtime = mt;
            String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            sConfig = DwConfig.parse(text);
            L("config loaded mode=" + (sConfig.whitelistMode ? "whitelist" : "blacklist")
                    + " pkgs=" + sConfig.pkgs);
        } catch (Throwable t) {
            L("config read failed: " + t);
        }
    }

    private static void L(String msg) {
        Log.i(TAG, msg);
        try {
            File f = new File(LOG);
            if (f.exists() && f.length() > 512 * 1024) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            String stamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            String line = stamp + " pid=" + android.os.Process.myPid() + " " + msg + "\n";
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }
}
