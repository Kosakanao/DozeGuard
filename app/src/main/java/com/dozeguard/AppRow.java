package com.dozeguard;

import android.graphics.drawable.Drawable;

/** One row in the app list. */
public final class AppRow {

    public final String pkg;
    public final String label;
    public final Drawable icon;
    /** true when the package ships with the system image (or is an update of one). */
    public final boolean system;
    public boolean checked;

    public AppRow(String pkg, String label, Drawable icon, boolean system, boolean checked) {
        this.pkg = pkg;
        this.label = label;
        this.icon = icon;
        this.system = system;
        this.checked = checked;
    }
}
