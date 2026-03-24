package com.iflytek.ainote.handwrite;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

/**
 * Stub for the iFlytek NoteJNI class that libpaintworker.so expects.
 * The native library's JNI_OnLoad registers methods on this class.
 * Without it, initialization fails with "JNI_OnLoad 检测到异常".
 */
public class NoteJNI {
    public static final int MSG_FLAG_DRAW = 0;
    public static final int SHOWMODE_ERASER = 2;
    public static final int SHOWMODE_WRITE = 2;
    private static final String TAG = "NoteJNI";
    public static final Handler mHandler;
    public static final HandlerThread mThread;

    public static abstract class NoteJNIInputListener2 {
        public abstract void onInput(int x, int y, int pressure, float tilt,
                                     int toolType, int action, int actionButton, int buttonState);

        public void receiveWritingDataEvent(int x, int y, int pressure, float tilt,
                                            int toolType, int action, int actionButton, int buttonState) {
            mHandler.post(() -> onInput(x, y, pressure, tilt, toolType, action, actionButton, buttonState));
        }
    }

    static {
        HandlerThread handlerThread = new HandlerThread(TAG);
        mThread = handlerThread;
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
    }

    private static class Holder {
        static final NoteJNI instance = new NoteJNI();
    }

    private NoteJNI() {
        Log.d(TAG, "NoteJNI stub created");
    }

    public static ClassLoader getClassLoader() {
        return NoteJNI.class.getClassLoader();
    }

    public static NoteJNI getInstance() {
        return Holder.instance;
    }

    // Native methods registered by libpaintworker.so's JNI_OnLoad
    public static native void native_setOrientation(int rotation);
    public native int native_clear();
    public native int native_exit();
    public native int native_getOverlayStatus();
    public native int native_getVersion();
    public native int native_init();
    public native int native_init(int flags);
    public native int native_init(Rect rect);
    public native int native_is_handwriting_enable(boolean enable);
    public native int native_is_overlay_enable(boolean enable);
    public native int native_release_javaBackgroundBitmap();
    public native int native_release_javaBitmap();
    public native void native_setDelayShowRectCount(int count);
    public native void native_setJumpPointCount(int count);
    public native int native_set_input_listener(Object listener);
    public native int native_set_input_listener2(Object listener);
    public native int native_set_javaBackgroundBitmap(Bitmap bitmap, int rotation, int left, int top);
    public native int native_set_javaBitmap(Bitmap bitmap, int rotation, int left, int top);
    public native int native_show_javaBitmapRect(Rect rect);
    public native int native_show_mode(int mode);
}
