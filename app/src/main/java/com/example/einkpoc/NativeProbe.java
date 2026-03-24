package com.example.einkpoc;

import android.util.Log;
import java.lang.reflect.Method;

/**
 * Carefully probes libpaintworker.so native methods one at a time.
 * Each step is isolated so a crash in one doesn't prevent logging the others.
 */
public class NativeProbe {
    private static final String TAG = "NativeProbe";
    private final StringBuilder log = new StringBuilder();
    private Object enoteWriting; // ENoteWriting.getInstance()
    private boolean libLoaded = false;

    public String run() {
        log.append("=== NativeProbe ===\n");
        log.append("Time: ").append(new java.util.Date()).append("\n\n");

        // Step 1: Try loading the library
        log.append("--- Step 1: Load libpaintworker.so ---\n");
        libLoaded = tryLoadLib();
        log.append("Library loaded: ").append(libLoaded).append("\n\n");

        if (!libLoaded) {
            save();
            return log.toString();
        }

        // Step 2: Get ENoteWriting instance
        log.append("--- Step 2: Get ENoteWriting instance ---\n");
        try {
            Class<?> c = Class.forName("android.os.enote.ENoteWriting");
            Method getInstance = c.getMethod("getInstance");
            enoteWriting = getInstance.invoke(null);
            log.append("ENoteWriting instance: ").append(enoteWriting != null ? "OK" : "NULL").append("\n\n");
        } catch (Throwable e) {
            log.append("FAILED: ").append(rootCause(e)).append("\n\n");
            save();
            return log.toString();
        }

        // Step 3: Try getVersion (read-only, should be safe)
        log.append("--- Step 3: getPaintWorkerVersion() ---\n");
        probeMethod("getPaintWorkerVersion");

        // Step 4: Try init
        log.append("--- Step 4: init() ---\n");
        probeMethod("init");

        // Step 5: Check overlay status
        log.append("--- Step 5: getOverlayStatus() ---\n");
        probeIntMethod("native_getOverlayStatus");

        // Step 6: Try isEnable
        log.append("--- Step 6: isEnable() ---\n");
        probeMethod("isEnable");

        // Step 7: Try setEnabled(true)
        log.append("--- Step 7: setEnabled(true) ---\n");
        probeVoidMethod("setEnabled", new Class[]{boolean.class}, true);

        // Step 8: Try onStart (overlay enable)
        log.append("--- Step 8: onStart() ---\n");
        probeVoidMethod("onStart", new Class[]{});

        // Step 9: Check enable state again
        log.append("--- Step 9: isEnable() after start ---\n");
        probeMethod("isEnable");

        // Step 10: Try onEnd (overlay disable) - clean up
        log.append("--- Step 10: onEnd() ---\n");
        probeVoidMethod("onEnd", new Class[]{});

        // Step 11: Try setEnabled(false) - clean up
        log.append("--- Step 11: setEnabled(false) ---\n");
        probeVoidMethod("setEnabled", new Class[]{boolean.class}, false);

        // List all methods on ENoteWriting for reference
        log.append("\n--- All ENoteWriting methods ---\n");
        try {
            for (Method m : enoteWriting.getClass().getDeclaredMethods()) {
                log.append("  ").append(m.getName()).append("(");
                Class<?>[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) log.append(", ");
                    log.append(params[i].getSimpleName());
                }
                log.append(") -> ").append(m.getReturnType().getSimpleName()).append("\n");
            }
        } catch (Throwable e) {
            log.append("Method listing failed: ").append(rootCause(e)).append("\n");
        }

        save();
        return log.toString();
    }

    private boolean tryLoadLib() {
        // Try System.loadLibrary first (uses standard library path)
        try {
            System.loadLibrary("paintworker");
            log.append("System.loadLibrary('paintworker'): OK\n");
            return true;
        } catch (Throwable e) {
            log.append("System.loadLibrary('paintworker'): FAIL - ").append(rootCause(e)).append("\n");
        }

        // Try absolute path
        try {
            System.load("/system/lib64/libpaintworker.so");
            log.append("System.load('/system/lib64/libpaintworker.so'): OK\n");
            return true;
        } catch (Throwable e) {
            log.append("System.load('/system/lib64/libpaintworker.so'): FAIL - ").append(rootCause(e)).append("\n");
        }

        return false;
    }

    private void probeMethod(String name) {
        try {
            Method m = enoteWriting.getClass().getMethod(name);
            Object result = m.invoke(enoteWriting);
            log.append(name).append("() = ").append(result).append("\n\n");
        } catch (Throwable e) {
            log.append(name).append("() FAILED: ").append(rootCause(e)).append("\n\n");
            writeCrash("probe-" + name, e);
        }
    }

    private void probeIntMethod(String name) {
        // For static native methods on ENoteWriting class
        try {
            Class<?> c = Class.forName("android.os.enote.ENoteWriting");
            Method m = c.getDeclaredMethod(name);
            m.setAccessible(true);
            Object result = m.invoke(null);
            log.append(name).append("() = ").append(result).append("\n\n");
        } catch (Throwable e) {
            log.append(name).append("() FAILED: ").append(rootCause(e)).append("\n\n");
        }
    }

    private void probeVoidMethod(String name, Class<?>[] paramTypes, Object... args) {
        try {
            Method m = enoteWriting.getClass().getMethod(name, paramTypes);
            m.invoke(enoteWriting, args);
            log.append(name).append("(...) = OK\n\n");
        } catch (Throwable e) {
            log.append(name).append("(...) FAILED: ").append(rootCause(e)).append("\n\n");
            writeCrash("probe-" + name, e);
        }
    }

    private String rootCause(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null) c = c.getCause();
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    private void writeCrash(String context, Throwable e) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter("/sdcard/Download/einkpoc_crash.txt", true);
            java.io.PrintWriter pw = new java.io.PrintWriter(fw);
            pw.println("=== " + context + " at " + new java.util.Date() + " ===");
            e.printStackTrace(pw);
            pw.println();
            pw.close();
        } catch (Throwable ignored) {}
    }

    private void save() {
        try {
            java.io.FileWriter fw = new java.io.FileWriter("/sdcard/Download/einkpoc_nativeprobe.txt");
            fw.write(log.toString());
            fw.close();
        } catch (Throwable ignored) {
            Log.e(TAG, "Could not save probe log");
        }
    }
}
