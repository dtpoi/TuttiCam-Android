package com.thinktough.tutticam.engine

import android.os.SystemClock

/** 提供含深度睡眠的单调时钟（秒），与相机帧和传感器的时间戳同一时基。 */
object MonoClock {
    fun now(): Double = SystemClock.elapsedRealtimeNanos() / 1e9

    fun nanos(): Long = SystemClock.elapsedRealtimeNanos()

    fun toNanos(seconds: Double): Long = (seconds * 1e9).toLong()

    fun toSeconds(nanos: Long): Double = nanos / 1e9
}
