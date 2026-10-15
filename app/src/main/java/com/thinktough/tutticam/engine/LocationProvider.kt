package com.thinktough.tutticam.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper

/** 用系统 LocationManager 提供拍摄地点，同时听 GPS 和网络定位，超过 2 分钟的位置不用。 */
class LocationProvider(private val context: Context) : LocationListener {
    private val manager = context.getSystemService(LocationManager::class.java)
    @Volatile private var latest: Location? = null
    private var running = false

    val authorized: Boolean
        get() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** 返回最近的有效位置。 */
    val fix: Location?
        get() = latest?.takeIf { (android.os.SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) < 120_000_000_000L }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || !authorized || manager == null) return
        running = true
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)) {
                runCatching { manager.requestLocationUpdates(provider, 2000L, 1f, this, Looper.getMainLooper()) }
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.let(::onLocationChanged)
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { manager?.removeUpdates(this) }
    }

    override fun onLocationChanged(location: Location) {
        val old = latest
        // 旧位置还新鲜且新位置明显更不准时保留旧的。
        if (old != null && location.accuracy > old.accuracy * 2 &&
            location.elapsedRealtimeNanos - old.elapsedRealtimeNanos < 10_000_000_000L) return
        latest = location
    }

    @Deprecated("系统旧回调")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
}
