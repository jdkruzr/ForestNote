package com.example.einkpoc;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;
import java.lang.reflect.Method;

/**
 * Reflection-based bridge to Viwoods ENoteSetting.
 * Uses ENoteSetting.getInstance() wrapper which handles the binder connection internally.
 */
public class ENoteBridge {
    private static final String TAG = "ENoteBridge";
    private Object enote; // ENoteSetting.getInstance()
    private android.content.Context appContext;

    public static final int MODE_NULL = -1;
    public static final int MODE_AUTO = 0;
    public static final int MODE_MIXED = 1;
    public static final int MODE_BROWSE = 2;
    public static final int MODE_GL16 = 3;
    public static final int MODE_FAST = 4;
    public static final int MODE_GC = 17;

    private Object binderService; // IENoteSetting extracted from wrapper's mService field

    public Object getEnote() { return enote; }

    public boolean init(android.content.Context context) {
        this.appContext = context.getApplicationContext();
        StringBuilder initLog = new StringBuilder();
        initLog.append("=== ENoteBridge.init() ===\n");
        initLog.append("Time: ").append(new java.util.Date()).append("\n\n");

        try {
            Class<?> c = Class.forName("android.os.enote.ENoteSetting");
            initLog.append("Class loaded: ").append(c.getName()).append("\n");

            Method m = c.getMethod("getInstance");
            enote = m.invoke(null);
            initLog.append("getInstance(): ").append(enote != null ? "OK" : "NULL").append("\n");

            // Dump all fields - walk class hierarchy
            try {
                Class<?> cls = enote.getClass();
                initLog.append("\n--- Runtime class: ").append(cls.getName()).append(" ---\n");
                while (cls != null && cls != Object.class) {
                    initLog.append("\n[Fields declared in ").append(cls.getName()).append("]\n");
                    for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                        f.setAccessible(true);
                        boolean isStatic = java.lang.reflect.Modifier.isStatic(f.getModifiers());
                        Object val = null;
                        try { val = f.get(isStatic ? null : enote); } catch (Throwable ignored) {}
                        String typeName = f.getType().getName();
                        String valStr;
                        try { valStr = val != null ? val.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(val)) : "null"; } catch (Throwable ignored) { valStr = "?"; }
                        initLog.append("  ").append(isStatic ? "static " : "").append(f.getName())
                                .append(" : ").append(typeName).append(" = ").append(valStr).append("\n");

                        // If this field's type name contains "IENoteSetting", grab it
                        if (typeName.contains("IENoteSetting") && val != null) {
                            binderService = val;
                            initLog.append("    ^^^ FOUND BINDER SERVICE ^^^\n");
                        }
                    }
                    cls = cls.getSuperclass();
                }
            } catch (Throwable e) {
                initLog.append("Field enumeration FAILED: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
                writeCrash("init-enumFields", e);
            }

            initLog.append("\nResult: enote=").append(enote != null).append(", binder=").append(binderService != null).append("\n");
            writeFile("einkpoc_init.txt", initLog.toString());
            return enote != null;
        } catch (Throwable e) {
            initLog.append("FATAL: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
            writeFile("einkpoc_init.txt", initLog.toString());
            writeCrash("init", e);
            return false;
        }
    }

    private void writeFile(String filename, String content) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter("/sdcard/Download/" + filename);
            fw.write(content);
            fw.close();
        } catch (Throwable ignored) {}
    }

    public String getInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ENoteSetting Status ===\n");
        sb.append("Wrapper: ").append(enote != null ? "OK" : "MISSING").append("\n");
        sb.append("Binder: ").append(binderService != null ? "OK" : "MISSING").append("\n\n");

        sb.append("PictureMode: ").append(safeInt("getPictureMode")).append("\n");
        sb.append("GammaIndex: ").append(safeInt("getGammaIndex")).append("\n");
        sb.append("CurrShouldMode: ").append(safeInt("getCurrShouldMode")).append("\n");
        sb.append("CurrRealEpdMode: ").append(safeInt("getCurrRealEpdMode")).append("\n");
        sb.append("IsDebug: ").append(safeBool("isDebug")).append("\n");
        sb.append("T1000Version: ").append(safeString("getT1000Version")).append("\n");
        sb.append("WaveVersion: ").append(safeString("getWaveVersion")).append("\n");
        sb.append("WacomVersion: ").append(safeString("getWacomVersion")).append("\n");
        sb.append("VcomVoltage: ").append(safeString("getVcomVoltage")).append("\n");
        sb.append("Temperature: ").append(safeString("getTemperature")).append("\n");
        sb.append("PowerStatus: ").append(safeBool("getPowerStatus")).append("\n");
        sb.append("DefaultEpdMode: ").append(safeStaticInt("getDefaultEpdMode")).append("\n");

        return sb.toString();
    }

    /**
     * Try to call ENoteWriting.setAutoDrawRects() directly via JNI.
     * This sets the native rects in libpaintworker.so which updateAutoDrawRegion() fails to do.
     */
    public String setNativeAutoDrawRects(int screenW, int screenH) {
        try {
            Class<?> writingClass = Class.forName("android.os.enote.ENoteWriting");
            Method getInstance = writingClass.getMethod("getInstance");
            Object writing = getInstance.invoke(null);

            // Create a list with one full-screen rect
            java.util.List<Rect> rects = new java.util.ArrayList<>();
            rects.add(new Rect(0, 0, screenW, screenH));
            // Also try physical coords
            rects.add(new Rect(0, 0, 1920, 1440));

            Method setRects = writingClass.getMethod("setAutoDrawRects", java.util.List.class);
            setRects.invoke(writing, rects);
            return "OK (native rects set)";
        } catch (Throwable e) {
            writeCrash("setNativeAutoDrawRects", e);
            Throwable c = e;
            while (c.getCause() != null) c = c.getCause();
            return "FAIL:" + c.getClass().getSimpleName() + ":" + c.getMessage();
        }
    }

    // === Writing system ===

    public String initWriting() {
        // Wschedule/WiNote call this from their app process via NoteView constructor.
        // Do NOT call System.loadLibrary first — the framework handles it internally.
        // Call setApplicationContext first, like NoteView does.
        try {
            Method setCtx = enote.getClass().getMethod("setApplicationContext", android.content.Context.class);
            setCtx.invoke(enote, appContext);
        } catch (Throwable e) {
            writeCrash("setApplicationContext", e);
        }
        return safeCallDescriptive("initWriting");
    }

    public String exitWriting() {
        return safeCallVoid("exitWriting");
    }

    public String onWritingStart() {
        return safeCallVoid("onWritingStart");
    }

    public String onWritingEnd() {
        return safeCallVoid("onWritingEnd");
    }

    public String setWritingEnabled(boolean enable) {
        return safeCallVoid1("setWritingEnabled", boolean.class, enable);
    }

    public String setRenderWritingDelayCount(int count) {
        return safeCallVoid1("setRenderWritingDelayCount", int.class, count);
    }

    public String setWritingInputJumpPointCount(int count) {
        return safeCallVoid1("setWritingInputJumpPointCount", int.class, count);
    }

    public String setWritingJavaBackgroundBitmap(Bitmap bmp, int rot, int left, int top) {
        try {
            Method m = enote.getClass().getMethod("setWritingJavaBackgroundBitmap",
                    Bitmap.class, int.class, int.class, int.class);
            m.invoke(enote, bmp, rot, left, top);
            return "OK";
        } catch (Throwable e) {
            writeCrash("setWritingJavaBackgroundBitmap", e);
            return errStr(e);
        }
    }

    public String setWritingJavaBitmap(Bitmap bmp, int rot, int left, int top) {
        try {
            Method m = enote.getClass().getMethod("setWritingJavaBitmap",
                    Bitmap.class, int.class, int.class, int.class);
            m.invoke(enote, bmp, rot, left, top);
            return "OK";
        } catch (Throwable e) {
            writeCrash("setWritingJavaBitmap", e);
            return errStr(e);
        }
    }

    public String renderWriting(Rect rect) {
        try {
            Method m = enote.getClass().getMethod("renderWriting", Rect.class);
            m.invoke(enote, rect);
            return "OK";
        } catch (Throwable e) {
            writeCrash("renderWriting", e);
            return errStr(e);
        }
    }

    // === Display mode ===

    public String setPictureMode(int mode) {
        try {
            Method m = enote.getClass().getMethod("setPictureMode", int.class);
            Object r = m.invoke(enote, mode);
            return "OK (returned " + r + ")";
        } catch (Throwable e) {
            writeCrash("setPictureMode", e);
            return errStr(e);
        }
    }

    public int getPictureMode() {
        try {
            Method m = enote.getClass().getMethod("getPictureMode");
            return (int) m.invoke(enote);
        } catch (Throwable e) { return -999; }
    }

    // === AutoDraw (system overlay path) ===

    // Try direct transact first (correct PID), fall back to shell
    public String setAutoDrawEnabled(boolean enable) {
        String r = directTransact(20, enable ? 1 : 0);
        if (r != null) return r;
        return binderCall1("setT1000AutoDrawEnable", boolean.class, enable);
    }

    public String setAutoDrawToolType(int type) {
        // Try wrapper first, fall back to binder
        String r = safeCallVoid1("setAutoDrawToolType", int.class, type);
        if (r.startsWith("FAIL")) return binderCall1("setAutoDrawToolType", int.class, type);
        return r;
    }

    public String setAutoDrawPenWidthRange(int min, int max) {
        try {
            Method m = enote.getClass().getMethod("setAutoDrawPenWidthRange", int.class, int.class);
            m.invoke(enote, min, max);
            return "OK";
        } catch (Throwable e) {
            // Fall back to binder
            try {
                Method m = binderService.getClass().getMethod("setAutoDrawPenWidthRange", int.class, int.class);
                m.invoke(binderService, min, max);
                return "OK (binder)";
            } catch (Throwable e2) {
                writeCrash("setAutoDrawPenWidthRange", e2);
                return errStr(e2);
            }
        }
    }

    public String addAutoDrawRect(Rect rect) {
        // MUST call from our app process so the rect is registered to our PID
        String r = directTransactRect(24, rect);
        if (r != null) return r;
        return binderCall1("addAutoDrawRect", Rect.class, rect);
    }

    public String setAllRegionUnAutoDraw(boolean enable) {
        String r = directTransact(28, enable ? 1 : 0);
        if (r != null) return r;
        return binderCall1("setAllRegionUnAutoDraw", boolean.class, enable);
    }

    public String onAppRenderBrush() {
        String r = safeCallVoid("onAppRenderBrush");
        if (r.startsWith("FAIL")) return binderCallVoid("onAppRenderBrush");
        return r;
    }

    public String stopHandwriteInterceptMipi() {
        String r = safeCallVoid("stopHandwriteInterceptMipi");
        if (r.startsWith("FAIL")) return binderCallVoid("stopHandwriteInterceptMipi");
        return r;
    }

    /**
     * Direct IBinder.transact() from our process — ensures correct calling PID.
     * Returns result string on success, null on failure.
     */
    private String directTransact(int txnCode, int intArg) {
        try {
            // Get the raw IBinder for ENoteSetting service
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            android.os.IBinder binder = (android.os.IBinder) getService.invoke(null, "ENoteSetting");
            if (binder == null) return null;

            android.os.Parcel data = android.os.Parcel.obtain();
            android.os.Parcel reply = android.os.Parcel.obtain();
            try {
                data.writeInterfaceToken("android.os.enote.IENoteSetting");
                data.writeInt(intArg);
                binder.transact(txnCode, data, reply, 0);
                reply.readException();
                return "OK (direct transact, PID=" + android.os.Process.myPid() + ")";
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable e) {
            writeCrash("directTransact-" + txnCode, e);
            return null;
        }
    }

    private String directTransactRect(int txnCode, Rect rect) {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            android.os.IBinder binder = (android.os.IBinder) getService.invoke(null, "ENoteSetting");
            if (binder == null) return null;

            android.os.Parcel data = android.os.Parcel.obtain();
            android.os.Parcel reply = android.os.Parcel.obtain();
            try {
                data.writeInterfaceToken("android.os.enote.IENoteSetting");
                // writeTypedObject for Rect: non-null marker then Parcelable data
                data.writeInt(1); // non-null
                rect.writeToParcel(data, 0);
                binder.transact(txnCode, data, reply, 0);
                reply.readException();
                return "OK (direct transact rect, PID=" + android.os.Process.myPid() + ")";
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable e) {
            writeCrash("directTransactRect-" + txnCode, e);
            return null;
        }
    }

    /**
     * Call T1000 command with int array via direct binder transact.
     * Transaction 7 = callT1000CmdIIsI(int type, int[] values)
     */
    public String callT1000Cmd(int type, int[] values) {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            android.os.IBinder binder = (android.os.IBinder) getService.invoke(null, "ENoteSetting");
            if (binder == null) return "FAIL:no binder";

            android.os.Parcel data = android.os.Parcel.obtain();
            android.os.Parcel reply = android.os.Parcel.obtain();
            try {
                data.writeInterfaceToken("android.os.enote.IENoteSetting");
                data.writeInt(type);
                data.writeIntArray(values);
                binder.transact(7, data, reply, 0);
                reply.readException();
                int result = reply.readInt();
                return "OK (result=" + result + ")";
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable e) {
            writeCrash("callT1000Cmd-" + type, e);
            return errStr(e);
        }
    }

    // Binder service call helpers - try reflection first, fall back to shell `service call`
    private String binderCall1(String name, Class<?> pType, Object arg) {
        // Try reflection on extracted binder
        if (binderService != null) {
            try {
                Method m = binderService.getClass().getMethod(name, pType);
                m.invoke(binderService, arg);
                return "OK (binder)";
            } catch (Throwable e) {
                writeCrash("binder-" + name, e);
            }
        }
        // Fall back to shell service call
        return serviceCallShell(name, pType, arg);
    }

    private String binderCallVoid(String name) {
        if (binderService != null) {
            try {
                Method m = binderService.getClass().getMethod(name);
                m.invoke(binderService);
                return "OK (binder)";
            } catch (Throwable e) {
                writeCrash("binder-" + name, e);
            }
        }
        // Transaction codes for known methods
        int txn = getTransactionCode(name);
        if (txn > 0) {
            return shellCmd("service call ENoteSetting " + txn);
        }
        return "FAIL:no binder and no txn code for " + name;
    }

    /**
     * Call ENoteSetting service methods via shell `service call`.
     * This bypasses the hidden API restrictions.
     */
    private String serviceCallShell(String name, Class<?> pType, Object arg) {
        int txn = getTransactionCode(name);
        if (txn <= 0) return "FAIL:unknown transaction code for " + name;

        String cmd;
        if (pType == boolean.class) {
            cmd = "service call ENoteSetting " + txn + " i32 " + ((boolean)arg ? 1 : 0);
        } else if (pType == int.class) {
            cmd = "service call ENoteSetting " + txn + " i32 " + (int)arg;
        } else if (pType == android.graphics.Rect.class) {
            Rect r = (Rect) arg;
            // Rect via writeTypedObject: non-null marker (1), then left, top, right, bottom
            cmd = "service call ENoteSetting " + txn + " i32 1 i32 " + r.left + " i32 " + r.top + " i32 " + r.right + " i32 " + r.bottom;
        } else {
            return "FAIL:unsupported param type " + pType.getSimpleName();
        }

        return shellCmd(cmd);
    }

    private String shellCmd(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            java.io.BufferedReader er = new java.io.BufferedReader(new java.io.InputStreamReader(p.getErrorStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) out.append(line);
            while ((line = er.readLine()) != null) out.append("ERR:").append(line);
            p.waitFor();
            return "OK (shell:" + p.exitValue() + ") " + out;
        } catch (Throwable e) {
            writeCrash("shellCmd-" + cmd, e);
            return errStr(e);
        }
    }

    private int getTransactionCode(String name) {
        // From IENoteSetting.Stub decompilation
        switch (name) {
            case "setT1000AutoDrawEnable": return 20;
            case "setAutoDrawToolType": return 21;
            case "getAutoDrawToolType": return 22;
            case "setAutoDrawPenWidthRange": return 23;
            case "addAutoDrawRect": return 24;
            case "removeAutoDrawRect": return 25;
            case "addUnAutoDrawRect": return 26;
            case "removeUnAutoDrawRect": return 27;
            case "setAllRegionUnAutoDraw": return 28;
            case "stopHandwriteInterceptMipi": return 29;
            case "onAppRenderBrush": return 33;
            case "onAppStopHandwriteInterceptMipi": return 34;
            case "setPictureMode": return 13;
            case "getPictureMode": return 12;
            default: return -1;
        }
    }

    // === Helpers ===

    private String safeInt(String name) {
        try {
            Method m = enote.getClass().getMethod(name);
            return String.valueOf(m.invoke(enote));
        } catch (Throwable e) {
            writeCrash(name, e);
            return "ERR:" + rootCause(e);
        }
    }

    private String safeStaticInt(String name) {
        try {
            Class<?> c = Class.forName("android.os.enote.ENoteSetting");
            Method m = c.getMethod(name);
            return String.valueOf(m.invoke(null));
        } catch (Throwable e) {
            return "ERR:" + rootCause(e);
        }
    }

    private String safeBool(String name) {
        try {
            Method m = enote.getClass().getMethod(name);
            return String.valueOf(m.invoke(enote));
        } catch (Throwable e) {
            writeCrash(name, e);
            return "ERR:" + rootCause(e);
        }
    }

    private String safeString(String name) {
        try {
            Method m = enote.getClass().getMethod(name);
            Object r = m.invoke(enote);
            return r != null ? r.toString() : "null";
        } catch (Throwable e) {
            writeCrash(name, e);
            return "ERR:" + rootCause(e);
        }
    }

    private String safeCallVoid(String name) {
        try {
            Method m = enote.getClass().getMethod(name);
            m.invoke(enote);
            return "OK";
        } catch (Throwable e) {
            writeCrash(name, e);
            return errStr(e);
        }
    }

    private String safeCallVoid1(String name, Class<?> pType, Object arg) {
        try {
            Method m = enote.getClass().getMethod(name, pType);
            m.invoke(enote, arg);
            return "OK";
        } catch (Throwable e) {
            writeCrash(name, e);
            return errStr(e);
        }
    }

    private String safeCallDescriptive(String name) {
        try {
            Method m = enote.getClass().getMethod(name);
            Object r = m.invoke(enote);
            return "OK (returned " + r + ")";
        } catch (Throwable e) {
            writeCrash(name, e);
            return errStr(e);
        }
    }

    private String rootCause(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null) c = c.getCause();
        return c.getClass().getSimpleName() + ":" + c.getMessage();
    }

    private String errStr(Throwable e) {
        return "FAIL:" + rootCause(e);
    }

    void writeCrash(String context, Throwable e) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter("/sdcard/Download/einkpoc_crash.txt", true);
            java.io.PrintWriter pw = new java.io.PrintWriter(fw);
            pw.println("=== " + context + " at " + new java.util.Date() + " ===");
            e.printStackTrace(pw);
            pw.println();
            pw.close();
        } catch (Throwable ignored) {
            Log.e(TAG, "Could not write crash file for " + context, e);
        }
    }
}
