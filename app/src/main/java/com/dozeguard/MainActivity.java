package com.dozeguard;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * DozeGuard UI: pick which packages may stay in the system Doze whitelist.
 * Everything is written to /data/system/dozeguard.conf through su; the hook in
 * system_server picks the change up on the next whitelist write.
 */
public final class MainActivity extends Activity {

    // Palette: resolved in applyPalette() from night mode + Material You accent.
    private int BG = 0xFFF2F3F5;
    private int CARD = 0xFFFFFFFF;
    private int PRIMARY = 0xFF1565C0;
    private int TEXT = 0xFF111827;
    private int MUTED = 0xFF6B7280;
    private int LINE = 0xFFE0E3E7;

    private static final int TAB_APPS = 0;
    private static final int TAB_WHITELIST = 1;

    private boolean whitelistMode = true;
    /** Whitelist is page 1: it is what people open the app to look at. */
    private int tab = TAB_WHITELIST;
    private String query = "";
    private boolean busy = false;
    private boolean rootOk = false;
    /** Hide packages that ship with the system image. */
    private boolean hideSystem = false;

    private final List<AppRow> all = new ArrayList<>();
    private final List<AppRow> shown = new ArrayList<>();
    private final LinkedHashSet<String> whitelist = new LinkedHashSet<>();
    /** Packages blocked by the previous save; unchecking one re-adds it to the whitelist. */
    private final Set<String> prevBlocked = new HashSet<>();

    private RowAdapter adapter;
    private TextView statusView;
    private TextView hintView;
    private TextView tabApps;
    private TextView tabWl;
    private TextView btnBlack;
    private TextView btnWhite;
    private EditText searchView;
    private Button saveBtn;
    private TextView filterChip;
    private TextView countView;
    private LinearLayout headerBar;
    private LinearLayout footerBar;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyPalette();
        hideSystem = getPreferences(MODE_PRIVATE).getBoolean("hide_system", false);
        setContentView(buildUi());
        reload(true);
    }

    /** Night mode + Material You: the accent comes from the wallpaper on Android 12+. */
    private void applyPalette() {
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        int accent = 0;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                accent = getResources().getColor(night
                        ? android.R.color.system_accent1_200
                        : android.R.color.system_accent1_600, getTheme());
            } catch (Throwable ignored) {
                accent = 0;
            }
        }

        if (night) {
            BG = 0xFF101216;
            CARD = 0xFF1B1E24;
            TEXT = 0xFFE8EAED;
            MUTED = 0xFF9AA0A6;
            LINE = 0xFF2C313A;
            PRIMARY = accent != 0 ? scale(accent, 0.45f) : 0xFF102A43;
        } else {
            BG = 0xFFF2F3F5;
            CARD = 0xFFFFFFFF;
            TEXT = 0xFF111827;
            MUTED = 0xFF6B7280;
            LINE = 0xFFE0E3E7;
            PRIMARY = accent != 0 ? accent : 0xFF1565C0;
        }
    }

    private static int scale(int color, float factor) {
        int r = (int) (((color >> 16) & 0xFF) * factor);
        int g = (int) (((color >> 8) & 0xFF) * factor);
        int b = (int) ((color & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------------- build

    private int d(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics());
    }

    private GradientDrawable rounded(int fill, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(d(radiusDp));
        return g;
    }

    private GradientDrawable roundedStroke(int fill, int stroke, float radiusDp) {
        GradientDrawable g = rounded(fill, radiusDp);
        g.setStroke(d(1), stroke);
        return g;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // ---- header
        LinearLayout header = new LinearLayout(this);
        headerBar = header;
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(PRIMARY);
        header.setPadding(d(18), d(16), d(18), d(16));
        TextView title = text("DozeGuard", 21f, 0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView sub = text("Doze 白名单控制器 · system_server 层拦截", 12f, 0xB3FFFFFF);
        header.addView(title);
        header.addView(sub, matchWrap());
        root.addView(header, matchWrap());

        // ---- body
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(d(12), d(12), d(12), 0);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0);
        bodyLp.weight = 1f;
        root.addView(body, bodyLp);

        // mode switch
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);

        btnBlack = text("黑名单模式", 14f, MUTED);
        btnBlack.setGravity(Gravity.CENTER);
        btnBlack.setPadding(0, d(11), 0, d(11));
        btnBlack.setOnClickListener(v -> switchMode(false));

        btnWhite = text("白名单模式", 14f, MUTED);
        btnWhite.setGravity(Gravity.CENTER);
        btnWhite.setPadding(0, d(11), 0, d(11));
        btnWhite.setOnClickListener(v -> switchMode(true));

        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        // Whitelist is the default mode, so it sits on the left.
        modeRow.addView(btnWhite, segLp);
        LinearLayout.LayoutParams segLp2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        segLp2.leftMargin = d(8);
        modeRow.addView(btnBlack, segLp2);
        body.addView(modeRow, matchWrap());

        hintView = text("", 12f, MUTED);
        hintView.setPadding(d(2), d(10), d(2), 0);
        body.addView(hintView, matchWrap());

        // tabs
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tabRowLp = matchWrap();
        tabRowLp.topMargin = d(14);
        body.addView(tabRow, tabRowLp);

        tabApps = text("应用", 14f, TEXT);
        tabApps.setGravity(Gravity.CENTER);
        tabApps.setPadding(0, d(10), 0, d(10));
        tabApps.setBackground(rounded(CARD, 10));
        tabApps.setOnClickListener(v -> switchTab(TAB_APPS));

        tabWl = text("当前白名单", 14f, MUTED);
        tabWl.setGravity(Gravity.CENTER);
        tabWl.setPadding(0, d(10), 0, d(10));
        tabWl.setBackground(rounded(CARD, 10));
        tabWl.setOnClickListener(v -> switchTab(TAB_WHITELIST));

        // Whitelist lives on the left: it is the page you open the app for.
        tabRow.addView(tabWl, segmentLp(0));
        tabRow.addView(tabApps, segmentLp(8));

        // search
        searchView = new EditText(this);
        searchView.setHint("搜索应用名或包名");
        searchView.setTextSize(14f);
        searchView.setSingleLine(true);
        searchView.setTextColor(TEXT);
        searchView.setHintTextColor(MUTED);
        searchView.setBackground(rounded(CARD, 10));
        searchView.setPadding(d(12), d(10), d(12), d(10));
        LinearLayout.LayoutParams searchLp = matchWrap();
        searchLp.topMargin = d(10);
        body.addView(searchView, searchLp);
        searchView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });

        // filter row: how many rows are shown + system-app toggle
        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams filterLp = matchWrap();
        filterLp.topMargin = d(8);
        body.addView(filterRow, filterLp);

        countView = text("", 11f, MUTED);
        filterRow.addView(countView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        filterChip = text("", 12f, MUTED);
        filterChip.setGravity(Gravity.CENTER);
        filterChip.setPadding(d(12), d(7), d(12), d(7));
        filterChip.setOnClickListener(v -> {
            hideSystem = !hideSystem;
            getPreferences(MODE_PRIVATE).edit().putBoolean("hide_system", hideSystem).apply();
            applyFilter();
            updateChrome();
        });
        filterRow.addView(filterChip, wrap());

        // list
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(d(8));
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.setPadding(0, d(10), 0, d(10));
        list.setBackgroundColor(BG);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= shown.size()) return;
            AppRow row = shown.get(position);
            row.checked = !row.checked;
            adapter.notifyDataSetChanged();
            updateChrome();
        });
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0);
        listLp.weight = 1f;
        listLp.topMargin = d(4);
        body.addView(list, listLp);

        // footer
        LinearLayout footer = new LinearLayout(this);
        footerBar = footer;
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setBackgroundColor(CARD);
        footer.setPadding(d(14), d(10), d(12), d(10));
        footer.setElevation(d(6));

        statusView = text("", 11f, MUTED);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        footer.addView(statusView, statusLp);

        saveBtn = new Button(this);
        saveBtn.setText("保存并应用");
        saveBtn.setAllCaps(false);
        saveBtn.setTextSize(14f);
        saveBtn.setOnClickListener(v -> save());
        footer.addView(saveBtn, wrap());
        root.addView(footer, matchWrap());

        // targetSdk 36 => the window is edge-to-edge, so the header would sit under the
        // status bar. Pad it by the real inset instead of hard-coding a bar height.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = 0, bottom = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars =
                        insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            headerBar.setPadding(d(18), d(16) + top, d(18), d(16));
            footerBar.setPadding(d(14), d(10), d(12), d(10) + bottom);
            return insets;
        });
        root.requestApplyInsets();

        return root;
    }

    private LinearLayout.LayoutParams segmentLp(int leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = d(leftMargin);
        return lp;
    }

    // ------------------------------------------------------------------ state

    private void switchMode(boolean white) {
        if (whitelistMode == white) return;
        whitelistMode = white;
        if (white) {
            // "Only these may stay": seed the ticks from what is exempt right now, so the
            // page opens with everything that is currently allowed already ticked.
            Set<String> wl = whitelistSnapshot();
            for (AppRow r : all) r.checked = wl.contains(r.pkg);
        } else {
            // "Block those": seed from the last saved block list, everything else clear.
            for (AppRow r : all) r.checked = prevBlocked.contains(r.pkg);
        }
        applyFilter();
        updateChrome();
    }

    private void switchTab(int which) {
        if (tab == which) return;
        tab = which;
        applyFilter();
        updateChrome();
    }

    private void applyFilter() {
        boolean wlTab = (tab == TAB_WHITELIST);
        shown.clear();
        for (AppRow r : all) {
            if (wlTab) {
                boolean inWl;
                synchronized (whitelist) {
                    inWl = whitelist.contains(r.pkg);
                }
                if (!inWl) continue;
            }
            // The system-app filter is a picker aid: the whitelist page always shows
            // everything that is exempt, otherwise it looks like packages vanished.
            if (tab == TAB_APPS && hideSystem && r.system) continue;
            if (query.isEmpty()
                    || r.pkg.toLowerCase(Locale.ROOT).contains(query)
                    || r.label.toLowerCase(Locale.ROOT).contains(query)) {
                shown.add(r);
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void updateChrome() {
        styleSegment(btnBlack, !whitelistMode);
        styleSegment(btnWhite, whitelistMode);

        tabApps.setText("应用");
        tabWl.setText("当前白名单 " + whitelist.size());
        styleTab(tabApps, tab == TAB_APPS);
        styleTab(tabWl, tab == TAB_WHITELIST);

        hintView.setText(whitelistMode
                ? "白名单模式 · 勾选的留在 Doze 白名单，取消勾选的会被移除（android / com.android.* 除外）"
                : "黑名单模式 · 勾选的应用会被踢出 Doze 白名单，云控再次写入也会被拦截");

        if (filterChip != null) {
            boolean picker = (tab == TAB_APPS);
            filterChip.setVisibility(picker ? View.VISIBLE : View.GONE);
            filterChip.setText(hideSystem ? "已隐藏系统应用 ✓" : "隐藏系统应用");
            filterChip.setBackground(hideSystem
                    ? rounded(PRIMARY, 16)
                    : roundedStroke(CARD, LINE, 16));
            filterChip.setTextColor(hideSystem ? 0xFFFFFFFF : MUTED);
            filterChip.setTypeface(hideSystem ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        if (countView != null) {
            int ticked = 0;
            for (AppRow r : shown) if (r.checked) ticked++;
            countView.setText(tab == TAB_WHITELIST
                    ? "已勾选 " + ticked + " / 白名单 " + whitelist.size()
                    : "已勾选 " + ticked + " / " + shown.size() + " 个应用");
        }

        int checked = 0;
        for (AppRow r : all) if (r.checked) checked++;
        statusView.setText("勾选 " + checked + " · 白名单 " + whitelist.size()
                + " · root " + (rootOk ? "✓" : "✗"));
    }

    private void styleSegment(TextView v, boolean selected) {
        if (selected) {
            v.setBackground(rounded(PRIMARY, 10));
            v.setTextColor(0xFFFFFFFF);
            v.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            v.setBackground(roundedStroke(CARD, LINE, 10));
            v.setTextColor(MUTED);
            v.setTypeface(Typeface.DEFAULT);
        }
    }

    private void styleTab(TextView v, boolean selected) {
        v.setTextColor(selected ? PRIMARY : MUTED);
        v.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        v.setBackground(rounded(CARD, 10));
    }

    private void setBusy(boolean b) {
        busy = b;
        if (saveBtn != null) saveBtn.setEnabled(!b);
    }

    private void ui(Runnable r) {
        runOnUiThread(r);
    }

    // ------------------------------------------------------------------- data

    private void reload(final boolean withWhitelist) {
        setBusy(true);
        statusView.setText("读取中…");
        new Thread(() -> {
            boolean root = Root.available();
            if (withWhitelist) {
                Set<String> wl = readWhitelist();
                synchronized (whitelist) {
                    whitelist.clear();
                    whitelist.addAll(wl);
                }
            }
            String raw = Root.readFile(DwConfig.PATH);
            boolean hasConf = raw != null && raw.contains("mode=");
            DwConfig cfg = DwConfig.parse(raw);

            // v0.9: whitelist is the default mode — "what is exempt now" is the selection,
            // everything ticked. One-time migration so an old blacklist conf does not
            // leave the app opening in the opposite mode.
            boolean migrate = !getPreferences(MODE_PRIVATE).getBoolean("wl_default_v9", false);
            boolean white;
            Set<String> sel = new LinkedHashSet<>();
            if (migrate || !hasConf) {
                white = true;
                sel.addAll(whitelistSnapshot());
                if (migrate) {
                    DwConfig seed = new DwConfig();
                    seed.whitelistMode = true;
                    seed.pkgs.addAll(sel);
                    Root.writeFile(DwConfig.PATH, seed.serialize());   // conf only, whitelist untouched
                    getPreferences(MODE_PRIVATE).edit().putBoolean("wl_default_v9", true).apply();
                }
            } else {
                white = cfg.whitelistMode;
                sel.addAll(cfg.pkgs);
            }
            final boolean whiteF = white;
            final Set<String> selF = sel;
            final List<AppRow> rows = buildRows(selF);
            ui(() -> {
                rootOk = root;
                whitelistMode = whiteF;
                prevBlocked.clear();
                if (!whiteF) prevBlocked.addAll(selF);
                all.clear();
                all.addAll(rows);
                applyFilter();
                updateChrome();
                setBusy(false);
            });
        }).start();
    }

    private List<AppRow> buildRows(Set<String> sel) {
        PackageManager pm = getPackageManager();
        LinkedHashSet<String> pkgs = new LinkedHashSet<>();
        int appCount = 0;

        try {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                if (ri.activityInfo != null && ri.activityInfo.packageName != null) {
                    pkgs.add(ri.activityInfo.packageName);
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("DozeGuardUI", "queryIntentActivities failed", t);
        }

        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if (ai.packageName != null) pkgs.add(ai.packageName);
                appCount++;
            }
        } catch (Throwable t) {
            android.util.Log.w("DozeGuardUI", "getInstalledApplications failed", t);
        }

        // Always show what matters even if package visibility is restricted.
        pkgs.addAll(sel);
        pkgs.addAll(whitelist);
        android.util.Log.i("DozeGuardUI",
                "candidates=" + pkgs.size() + " installed=" + appCount
                        + " wl=" + whitelist.size() + " sel=" + sel.size());

        List<AppRow> out = new ArrayList<>();
        for (String pkg : pkgs) {
            if (pkg == null || pkg.isEmpty()) continue;
            if (pkg.equals(getPackageName())) continue;

            CharSequence label = null;
            Drawable icon = null;
            boolean system = false;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                label = pm.getApplicationLabel(ai);
                icon = pm.getApplicationIcon(ai);
                system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            } catch (Throwable ignored) {
                // not visible / uninstalled: keep the package name so it stays selectable
            }
            out.add(new AppRow(pkg, label == null ? pkg : label.toString(), icon, system,
                    sel.contains(pkg)));
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        android.util.Log.i("DozeGuardUI", "rows=" + out.size());
        return out;
    }

    private Set<String> whitelistSnapshot() {
        synchronized (whitelist) {
            return new LinkedHashSet<>(whitelist);
        }
    }

    /** Only the mutable "user" section of the whitelist; system entries are left alone. */
    private Set<String> readWhitelist() {
        Set<String> out = new LinkedHashSet<>();
        Root.Result r = Root.run("dumpsys deviceidle whitelist");
        for (String raw : r.out.split("\n")) {
            String line = raw.trim();
            if (!line.startsWith("user,")) continue;
            String[] parts = line.split(",");
            if (parts.length >= 2 && !parts[1].isEmpty()) out.add(parts[1]);
        }
        return out;
    }

    // ------------------------------------------------------------------- save

    private void save() {
        final Set<String> sel = new HashSet<>();
        for (AppRow r : all) if (r.checked) sel.add(r.pkg);

        final DwConfig cfg = new DwConfig();
        cfg.whitelistMode = whitelistMode;
        cfg.pkgs.addAll(sel);

        setBusy(true);
        statusView.setText("写入中…");
        new Thread(() -> {
            if (!Root.available()) {
                ui(() -> {
                    setBusy(false);
                    statusView.setText("未获得 root 权限");
                    Toast.makeText(this, "未获得 root 权限", Toast.LENGTH_LONG).show();
                });
                return;
            }
            Root.Result w = Root.writeFile(DwConfig.PATH, cfg.serialize());

            Set<String> cur = readWhitelist();
            Set<String> desired = new LinkedHashSet<>();
            if (whitelistMode) {
                desired.addAll(sel);
            } else {
                desired.addAll(cur);
                desired.addAll(prevBlocked);   // unchecking re-adds what an earlier save removed
                desired.removeAll(sel);
            }

            int removed = 0, added = 0;
            for (String pkg : cur) {
                if (desired.contains(pkg)) continue;
                Root.run("dumpsys deviceidle whitelist -" + pkg);
                removed++;
            }
            for (String pkg : desired) {
                if (cur.contains(pkg)) continue;
                Root.run("dumpsys deviceidle whitelist +" + pkg);
                added++;
            }

            final Set<String> after = readWhitelist();
            final int rm = removed, ad = added;
            final boolean writeOk = w.code == 0;
            ui(() -> {
                if (!cfg.whitelistMode) {
                    prevBlocked.clear();
                    prevBlocked.addAll(sel);
                }
                synchronized (whitelist) {
                    whitelist.clear();
                    whitelist.addAll(after);
                }
                setBusy(false);
                updateChrome();
                statusView.setText("已应用 · 移除 " + rm + " · 加入 " + ad
                        + " · 白名单 " + after.size() + (writeOk ? "" : " · 配置写入失败"));
                Toast.makeText(this,
                        writeOk ? ("已保存，当前白名单 " + after.size() + " 个")
                                : ("配置写入失败：" + w.trim()),
                        Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    // ------------------------------------------------------------------ rows

    private static final class Holder {
        final ImageView icon;
        final TextView label;
        final TextView pkg;
        final CheckBox check;
        Holder(ImageView i, TextView l, TextView p, CheckBox c) {
            icon = i; label = l; pkg = p; check = c;
        }
    }

    private final class RowAdapter extends BaseAdapter {

        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            Holder h;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                h = (Holder) row.getTag();
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(d(12), d(10), d(12), d(10));
                row.setBackground(rounded(CARD, 10));

                ImageView icon = new ImageView(MainActivity.this);
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(d(38), d(38));
                icon.setLayoutParams(iconLp);
                icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                textsLp.leftMargin = d(12);
                textsLp.rightMargin = d(8);

                TextView label = text("", 15f, TEXT);
                label.setSingleLine(true);
                TextView pkg = text("", 11f, MUTED);
                pkg.setSingleLine(true);
                texts.addView(label);
                texts.addView(pkg);

                CheckBox check = new CheckBox(MainActivity.this);
                check.setClickable(false);
                check.setFocusable(false);

                row.addView(icon);
                row.addView(texts, textsLp);
                row.addView(check, wrap());
                row.setTag(new Holder(icon, label, pkg, check));
                h = (Holder) row.getTag();
            }

            AppRow r = shown.get(position);
            h.icon.setImageDrawable(r.icon);
            h.label.setText(r.label);
            h.pkg.setText(r.pkg);
            h.check.setVisibility(View.VISIBLE);
            h.check.setChecked(r.checked);
            return row;
        }
    }
}
