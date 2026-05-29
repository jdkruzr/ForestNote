package com.forestnote.app.notes.caldav

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * Minimal wrapper around `ConnectivityManager.registerDefaultNetworkCallback`.
 * Fires [onAvailable] whenever the device transitions from "no usable network"
 * to "has a default network" — the signal `CalDavOutboxDrainer.drainNow()`
 * wants so queued tasks send the moment WiFi comes back.
 *
 * Defensive throughout: the constructor never throws; if the system service
 * is missing or the registration call fails, both `start` and `stop` no-op
 * and the rest of the queue still works (the periodic timer and explicit
 * Send / Retry paths still drain).
 *
 * Lifecycle (per Android docs): register in `onStart`, unregister in `onStop`.
 * Not unit-tested — the entire surface is Android Context bound; verified
 * on-device alongside the rest of the queue.
 */
class NetworkAvailabilityMonitor(
    context: Context,
    private val log: (String) -> Unit = {},
) {

    private val connectivityManager: ConnectivityManager? = try {
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    } catch (t: Throwable) {
        log("NetworkAvailabilityMonitor: getSystemService failed: $t")
        null
    }

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Register the callback. [onAvailable] runs on a system thread — hop off it if needed. */
    fun start(onAvailable: () -> Unit) {
        if (callback != null) return
        val cm = connectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                log("NetworkAvailabilityMonitor: onAvailable($network)")
                try {
                    onAvailable()
                } catch (t: Throwable) {
                    log("NetworkAvailabilityMonitor: onAvailable callback threw: $t")
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            callback = cb
        } catch (t: Throwable) {
            log("NetworkAvailabilityMonitor: register failed: $t")
        }
    }

    /** Unregister. Safe to call repeatedly / from a teardown path that wasn't started. */
    fun stop() {
        val cb = callback ?: return
        try {
            connectivityManager?.unregisterNetworkCallback(cb)
        } catch (t: Throwable) {
            log("NetworkAvailabilityMonitor: unregister failed: $t")
        }
        callback = null
    }
}
