package com.forestnote.core.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Reflection-based bridge to the Viwoods ENoteSetting API.
 *
 * All calls go through ENoteSetting.getInstance() which is a hidden
 * framework class in android.os.enote. The methods are local JNI calls
 * into libpaintworker.so (loaded by zygote), not binder IPC.
 *
 * Reference: ~/KotlinViwoodsPort/AIPAPER_INK_API_DOC.md
 */
class ENoteBridge {
    companion object {
        const val MODE_FAST = 4
        const val MODE_GL16 = 3
        const val MODE_GC = 17
    }

    /** The ENoteSetting.getInstance() singleton. Null if reflection failed. */
    var enote: Any? = null
        private set

    private var appContext: Context? = null

    /**
     * Attempt to load the ENoteSetting class via reflection.
     * Returns true if the hidden API is available on this device.
     */
    fun init(context: Context): Boolean {
        appContext = context.applicationContext
        val log = StringBuilder("=== ENoteBridge.init() ===\n")

        return try {
            val c = Class.forName("android.os.enote.ENoteSetting")
            log.append("Class loaded: ${c.name}\n")

            enote = c.getMethod("getInstance").invoke(null)
            log.append("getInstance(): ${if (enote != null) "OK" else "NULL"}\n")

            CrashLog.write("forestnote_init.txt", log.toString())
            enote != null
        } catch (e: Throwable) {
            log.append("FATAL: ${e.javaClass.simpleName}: ${e.message}\n")
            CrashLog.write("forestnote_init.txt", log.toString())
            CrashLog.log("init", e)
            false
        }
    }

    fun initWriting(): String {
        try {
            enote?.javaClass?.getMethod("setApplicationContext", Context::class.java)
                ?.invoke(enote, appContext)
        } catch (e: Throwable) {
            CrashLog.log("setApplicationContext", e)
        }
        return safeCallDescriptive("initWriting")
    }

    fun exitWriting() = safeCallVoid("exitWriting")

    fun setWritingEnabled(enable: Boolean) =
        safeCallVoid1("setWritingEnabled", Boolean::class.java, enable)

    fun onWritingStart() = safeCallVoid("onWritingStart")

    fun onWritingEnd() = safeCallVoid("onWritingEnd")

    fun setRenderWritingDelayCount(count: Int) =
        safeCallVoid1("setRenderWritingDelayCount", Int::class.java, count)

    fun setWritingJavaBitmap(bmp: Bitmap, rotation: Int, left: Int, top: Int): String {
        return try {
            enote?.javaClass?.getMethod(
                "setWritingJavaBitmap",
                Bitmap::class.java, Int::class.java, Int::class.java, Int::class.java
            )?.invoke(enote, bmp, rotation, left, top)
            "OK"
        } catch (e: Throwable) {
            CrashLog.log("setWritingJavaBitmap", e)
            errStr(e)
        }
    }

    fun renderWriting(rect: Rect): String {
        return try {
            enote?.javaClass?.getMethod("renderWriting", Rect::class.java)
                ?.invoke(enote, rect)
            "OK"
        } catch (e: Throwable) {
            CrashLog.log("renderWriting", e)
            errStr(e)
        }
    }

    fun setPictureMode(mode: Int): String {
        return try {
            val r = enote?.javaClass?.getMethod("setPictureMode", Int::class.java)
                ?.invoke(enote, mode)
            "OK (returned $r)"
        } catch (e: Throwable) {
            CrashLog.log("setPictureMode", e)
            errStr(e)
        }
    }

    private fun safeCallVoid(name: String): String = try {
        enote?.javaClass?.getMethod(name)?.invoke(enote)
        "OK"
    } catch (e: Throwable) {
        CrashLog.log(name, e)
        errStr(e)
    }

    private fun safeCallVoid1(name: String, pType: Class<*>, arg: Any): String = try {
        enote?.javaClass?.getMethod(name, pType)?.invoke(enote, arg)
        "OK"
    } catch (e: Throwable) {
        CrashLog.log(name, e)
        errStr(e)
    }

    private fun safeCallDescriptive(name: String): String = try {
        val r = enote?.javaClass?.getMethod(name)?.invoke(enote)
        "OK (returned $r)"
    } catch (e: Throwable) {
        CrashLog.log(name, e)
        errStr(e)
    }

    private fun rootCause(e: Throwable): String {
        var c = e
        while (c.cause != null) c = c.cause!!
        return "${c.javaClass.simpleName}:${c.message}"
    }

    private fun errStr(e: Throwable) = "FAIL:${rootCause(e)}"
}
