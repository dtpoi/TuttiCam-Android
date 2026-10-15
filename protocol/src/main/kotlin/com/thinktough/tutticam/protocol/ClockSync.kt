package com.thinktough.tutticam.protocol

/** 估算“主机时钟 − 本机时钟”的偏差：每轮探测取往返最短的样本，主机自身传 fixedOffset = 0。 */
class ClockSync(private val now: () -> Double, fixedOffset: Double? = null) {
    data class Result(val rtt: Double, val offset: Double) {
        /** 给出误差上界，等于往返时间的一半。 */
        val error: Double get() = rtt / 2
    }

    private val lock = Any()
    private var offset: Double? = fixedOffset
    private var last: Result? = null
    private var seq = 0
    private val samples = mutableListOf<Result>()

    val isSynced: Boolean get() = synchronized(lock) { offset != null }
    val lastResult: Result? get() = synchronized(lock) { last }

    fun nextPing(): Message.Ping = synchronized(lock) {
        seq += 1
        Message.Ping(seq, now())
    }

    fun addPong(t0: Double, t1: Double, t2: Double, t3: Double) {
        val rtt = (t3 - t0) - (t2 - t1)
        val theta = ((t1 - t0) + (t2 - t3)) / 2
        if (rtt < 0) return
        synchronized(lock) { samples += Result(rtt, theta) }
    }

    /** 结束一轮探测并采用往返最短的样本；没有样本时返回 null，偏差保持不变。 */
    fun finishBurst(): Result? = synchronized(lock) {
        val best = samples.minByOrNull { it.rtt }
        samples.clear()
        best?.also { offset = it.offset; last = it }
    }

    /** 返回本轮目前最好的样本且不清空，刚连上时用它尽快开拍。 */
    fun provisional(): Result? = synchronized(lock) {
        samples.minByOrNull { it.rtt }?.also { offset = it.offset; last = it }
    }

    fun reset() = synchronized(lock) {
        offset = null
        last = null
        samples.clear()
    }

    fun hostToLocal(hostTime: Double): Double? = synchronized(lock) { offset?.let { hostTime - it } }

    fun localToHost(localTime: Double): Double? = synchronized(lock) { offset?.let { localTime + it } }

    companion object {
        /** 每轮探测数；后两项是探测间隔和收尾等待（毫秒）。 */
        const val BURST_COUNT = 30
        const val BURST_SPACING_MS = 15L
        const val BURST_TAIL_MS = 300L
        /** 首次对时发出这么多个探测后先报临时结果。 */
        const val EARLY_AFTER = 6
    }
}
