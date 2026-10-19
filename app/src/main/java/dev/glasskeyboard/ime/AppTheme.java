package dev.glasskeyboard.ime;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Guesses whether the app the keyboard is typing into uses a light or a dark theme, from the
 * theme declared in its manifest (resolved against the current day/night mode, so DayNight apps
 * follow the system). The home screen and system UI always follow the system theme.
 */
final class AppTheme {
    private final Context context;
    private final Map<String, Boolean> cache = new HashMap<>();
    private Set<String> launchers;

    AppTheme(Context context) { this.context = context; }

    static boolean systemDark(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** True if the keyboard should be dark inside {@code pkg}. */
    boolean isDark(String pkg) {
        boolean sys = systemDark(context);
        if (pkg == null || pkg.isEmpty() || pkg.equals(context.getPackageName())
                || pkg.equals("android") || pkg.equals("com.android.systemui") || isLauncher(pkg)) return sys;
        String key = pkg + (sys ? "#n" : "#d");
        Boolean cached = cache.get(key);
        if (cached != null) return cached;
        Boolean light = null;
        try {
            light = lightTheme(pkg);
        } catch (Throwable ignored) { }
        boolean result = light == null ? sys : !light;
        cache.put(key, result);
        return result;
    }

    void clear() { cache.clear(); launchers = null; }

    private boolean isLauncher(String pkg) {
        if (launchers == null) {
            launchers = new HashSet<>();
            try {
                Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                for (ResolveInfo ri : context.getPackageManager().queryIntentActivities(home, PackageManager.MATCH_ALL))
                    launchers.add(ri.activityInfo.packageName);
            } catch (Throwable ignored) { }
        }
        return launchers.contains(pkg);
    }

    private Boolean lightTheme(String pkg) throws Exception {
        PackageManager pm = context.getPackageManager();
        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
        int theme = ai.theme;
        if (theme == 0) {
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch != null && launch.getComponent() != null) {
                ActivityInfo act = pm.getActivityInfo(launch.getComponent(), 0);
                theme = act.getThemeResource();
            }
        }
        if (theme == 0) return null;
        Context pc = context.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY);
        pc.setTheme(theme);
        TypedArray a = pc.getTheme().obtainStyledAttributes(new int[]{
                android.R.attr.colorBackground, android.R.attr.windowBackground, android.R.attr.isLightTheme});
        try {
            // the actual background colour is the most reliable signal
            if (a.hasValue(0)) {
                int c = a.getColor(0, 0);
                if (Color.alpha(c) > 200) return luminance(c) > .5f;
            }
            if (a.hasValue(1)) {
                try {
                    Drawable d = a.getDrawable(1);
                    if (d instanceof ColorDrawable) {
                        int c = ((ColorDrawable) d).getColor();
                        if (Color.alpha(c) > 200) return luminance(c) > .5f;
                    }
                } catch (Throwable ignored) { }
            }
            if (a.hasValue(2)) return a.getBoolean(2, false);
            return null;
        } finally {
            a.recycle();
        }
    }

    private static float luminance(int c) {
        return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f;
    }
}
