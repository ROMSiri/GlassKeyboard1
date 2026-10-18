package dev.glasskeyboard.ime;

import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Backdrop blur behind the keyboard panel.
 * <ol>
 *   <li>ColorOS: the ROM's own material ({@code com.oplus.view.ViewRootManager} +
 *       {@code OplusBlurParam}), same calls the Xiaobu keyboard makes — rounded top corners,
 *       radius 150, tint #CCE1E1E1 / #990D0D0D.</li>
 *   <li>Android 12+: cross-window background blur of the IME window.</li>
 *   <li>Otherwise: no blur, the view paints an almost opaque panel.</li>
 * </ol>
 */
final class PanelBlur {
    private static final String TAG = "PanelBlur";

    enum Mode { OPLUS, WINDOW, NONE }

    /** Panel tint: ColorOS colors (#E1E1E1 / #0D0D0D) with a user-chosen opacity (percent). */
    static int tint(boolean dark, int opacityPercent) {
        int a = Math.round(255f * Math.max(0, Math.min(100, opacityPercent)) / 100f);
        return (a << 24) | (dark ? 0x0D0D0D : 0xE1E1E1);
    }

    interface Listener { void onBlurModeChanged(Mode mode, String detail); }

    private final Listener listener;
    private Window window;
    private View target;
    private boolean dark;
    private float cornerPx;
    private Mode mode = Mode.NONE;
    private boolean enabled = true;
    private int opacity = 88;
    private String detail = "";
    private Drawable windowBackground;
    private float windowBackgroundCorner = -1f;

    // ColorOS reflection
    private boolean oplusResolved, oplusAvailable;
    private Constructor<?> managerCtor, paramsCtor;
    private Method getDrawable, setParams, setRadius, setCorners;
    private Method setType, setMaterial, setCornerType, setCornerWeight;
    private Object manager;
    private Drawable oplusDrawable;
    private Object rootSurface;

    // AOSP window blur
    private Consumer<Boolean> blurListener;
    private boolean crossWindowBlur;

    PanelBlur(Listener listener) { this.listener = listener; }

    Mode mode() { return mode; }

    void setOpacity(int percent) { opacity = percent; }

    void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        apply();
    }

    /** Bind to the IME window and the keyboard root view. Safe to call repeatedly. */
    void bind(Window window, View target, boolean dark, float cornerPx) {
        if (this.window != window) {
            unregisterWindowListener();
            this.window = window;
            registerWindowListener();
        }
        if (this.target != target) {
            releaseOplus();
            this.target = target;
        }
        this.dark = dark;
        this.cornerPx = cornerPx;
        apply();
    }

    /** Re-check after layout / visibility changes; the renderer may drop the material. */
    void refresh() {
        if (mode == Mode.OPLUS && target != null) {
            boolean lost = target.getBackground() != oplusDrawable
                    || (Build.VERSION.SDK_INT >= 31 && rootSurface != target.getRootSurfaceControl());
            if (lost) releaseOplus();
        }
        apply();
    }

    void release() {
        releaseOplus();
        unregisterWindowListener();
        if (window != null && Build.VERSION.SDK_INT >= 31) {
            try { window.setBackgroundBlurRadius(0); } catch (Throwable ignored) { }
        }
        window = null;
        target = null;
        setMode(Mode.NONE);
    }

    private void apply() {
        if (window == null || target == null) return;
        installWindowBackground();
        if (!enabled) {
            releaseOplus();
            setWindowBlur(0);
            setMode(Mode.NONE);
            return;
        }
        if (applyOplus()) {
            setWindowBlur(0);
            setMode(Mode.OPLUS);
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 && crossWindowBlur) {
            float density = target.getResources().getDisplayMetrics().density;
            setWindowBlur(Math.round(50f * density));
            setMode(Mode.WINDOW);
            return;
        }
        setWindowBlur(0);
        setMode(Mode.NONE);
    }

    /**
     * Transparent window background with a rounded outline. Android's window blur takes its
     * corner radius from this drawable's outline, so the blurred area gets rounded corners.
     * Set only when it changes: replacing it triggers a relayout, and we refresh on layout.
     */
    private void installWindowBackground() {
        if (windowBackground == null || windowBackgroundCorner != cornerPx) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.TRANSPARENT);
            bg.setCornerRadius(cornerPx);
            windowBackground = bg;
            windowBackgroundCorner = cornerPx;
            window.setFormat(PixelFormat.TRANSLUCENT);
        }
        if (window.getDecorView().getBackground() != windowBackground) {
            window.setBackgroundDrawable(windowBackground);
        }
    }

    private String reportedDetail = null;

    private void setMode(Mode value) {
        if (mode == value && detail.equals(reportedDetail)) return;
        mode = value;
        reportedDetail = detail;
        Log.i(TAG, "blur mode = " + value + " " + detail);
        if (listener != null) listener.onBlurModeChanged(value, detail);
    }

    // ---------------------------------------------------------------- ColorOS

    private boolean resolveOplus() {
        if (oplusResolved) return oplusAvailable;
        oplusResolved = true;
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            String rom = (String) props.getMethod("get", String.class, String.class)
                    .invoke(null, "ro.build.version.oplusrom", "");
            if (rom == null || rom.isEmpty()) { detail = "не ColorOS"; return oplusAvailable = false; }
            ClassLoader loader = PanelBlur.class.getClassLoader();
            Class<?> managerType = Class.forName("com.oplus.view.ViewRootManager", false, loader);
            Class<?> paramsType = Class.forName("com.oplus.graphics.OplusBlurParam", false, loader);
            managerCtor = managerType.getConstructor(View.class);
            paramsCtor = paramsType.getConstructor();
            getDrawable = managerType.getMethod("getBackgroundBlurDrawable");
            setParams = managerType.getMethod("setBlurParams", paramsType);
            setRadius = managerType.getMethod("setBlurRadius", int.class);
            setCorners = managerType.getMethod("setCornerRadius",
                    float.class, float.class, float.class, float.class);
            setType = paramsType.getMethod("setBlurType", int.class);
            setMaterial = paramsType.getMethod("setMaterialParams", int.class, float[].class, float[].class);
            setCornerType = paramsType.getMethod("setSmoothCornerType", int.class);
            setCornerWeight = paramsType.getMethod("setSmoothCornerWeight", float.class);
            Log.i(TAG, "ColorOS material API found, oplusrom=" + rom);
            return oplusAvailable = true;
        } catch (Throwable error) {
            detail = "API ColorOS недоступен: " + error.getClass().getSimpleName() + " " + error.getMessage();
            Log.i(TAG, "ColorOS material API not available: " + error);
            return oplusAvailable = false;
        }
    }

    private boolean applyOplus() {
        if (!resolveOplus()) return false;
        View view = target;
        if (!view.isAttachedToWindow() || !view.isHardwareAccelerated()) return false;
        try {
            if (manager == null) {
                manager = managerCtor.newInstance(view);
                oplusDrawable = (Drawable) getDrawable.invoke(manager);
                if (oplusDrawable == null) { manager = null; detail = "ColorOS не вернул drawable"; return false; }
                view.setBackground(oplusDrawable);
                if (Build.VERSION.SDK_INT >= 31) rootSurface = view.getRootSurfaceControl();
            }
            int tint = tint(dark, opacity);
            Object params = paramsCtor.newInstance();
            setType.invoke(params, 2);
            setMaterial.invoke(params, 1, new float[]{1f, 1f, 1f, 1f},
                    new float[]{Color.red(tint) / 255f, Color.green(tint) / 255f,
                            Color.blue(tint) / 255f, Color.alpha(tint) / 255f});
            setCornerType.invoke(params, 1);
            setCornerWeight.invoke(params, 3f);
            // Re-submit every time: window/surface changes can reset the renderer state.
            setParams.invoke(manager, params);
            setCorners.invoke(manager, cornerPx, cornerPx, 0f, 0f);
            setRadius.invoke(manager, 150);
            detail = "";
            return true;
        } catch (Throwable error) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            detail = "ошибка материала ColorOS: " + cause.getClass().getSimpleName() + " " + cause.getMessage();
            Log.w(TAG, "ColorOS material failed, falling back", error);
            releaseOplus();
            oplusAvailable = false;
            return false;
        }
    }

    private void releaseOplus() {
        if (manager != null) {
            try { setRadius.invoke(manager, 0); } catch (Throwable ignored) { }
        }
        if (target != null && oplusDrawable != null && target.getBackground() == oplusDrawable) {
            target.setBackground(null);
        }
        manager = null;
        oplusDrawable = null;
        rootSurface = null;
    }

    // ---------------------------------------------------------------- AOSP

    private void registerWindowListener() {
        if (Build.VERSION.SDK_INT < 31 || window == null) return;
        WindowManager wm = window.getWindowManager();
        if (wm == null) return;
        try {
            crossWindowBlur = wm.isCrossWindowBlurEnabled();
            blurListener = on -> {
                crossWindowBlur = on;
                apply();
                if (target != null) target.invalidate();
            };
            wm.addCrossWindowBlurEnabledListener(blurListener);
        } catch (Throwable error) {
            crossWindowBlur = false;
            blurListener = null;
        }
    }

    private void unregisterWindowListener() {
        if (Build.VERSION.SDK_INT < 31 || window == null || blurListener == null) return;
        try {
            WindowManager wm = window.getWindowManager();
            if (wm != null) wm.removeCrossWindowBlurEnabledListener(blurListener);
        } catch (Throwable ignored) { }
        blurListener = null;
    }

    private void setWindowBlur(int radius) {
        if (Build.VERSION.SDK_INT < 31 || window == null) return;
        try { window.setBackgroundBlurRadius(radius); } catch (Throwable ignored) { }
    }
}
