package dev.codeassist.ndk

import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * How many compiles to run at once, decided from the memory the device has left.
 *
 * A single `clang++` over the libc++ headers takes a few hundred megabytes, and on a phone the IDE, the
 * editor's own clang and the Android runtime are already sharing what is free. Running one job per core is
 * how a build gets the whole app killed by the low-memory daemon, so the count is capped by memory and the
 * scheduler backs off while the device is short.
 */
internal object NdkResources {

    data class Memory(val availableKb: Long, val totalKb: Long)

    /** Rough resident size of one compile, in MB. Conservative: a template-heavy file can exceed it. */
    const val MB_PER_JOB = 450

    /** Left for the IDE, the editor's clang and the system. */
    const val RESERVE_MB = 400

    /** Absolute floor: below this the device is about to start killing processes. */
    const val HARD_FLOOR_KB = 160L * 1024

    fun readMemory(meminfo: File = File("/proc/meminfo")): Memory? =
        runCatching { parseMeminfo(meminfo.readText()) }.getOrNull()

    /** `MemAvailable` is the kernel's own estimate; very old kernels lack it and get free + cache instead. */
    fun parseMeminfo(text: String): Memory? {
        fun kb(key: String): Long? = text.lineSequence()
            .firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()

        val total = kb("MemTotal") ?: return null
        val available = kb("MemAvailable")
            ?: ((kb("MemFree") ?: return null) + (kb("Buffers") ?: 0L) + (kb("Cached") ?: 0L))
        return Memory(available, total)
    }

    /**
     * Compile jobs to run. [requested] > 0 is the module's own choice (`jobs` in `[ndk]`) and wins, within
     * sane bounds; 0 means decide: the core count, held down by free memory, never above 8 (the link and the
     * storage are the bottleneck well before that).
     */
    fun planJobs(cores: Int, availableKb: Long?, requested: Int = 0): Int {
        if (requested > 0) return requested.coerceIn(1, 16)
        val cpu = cores.coerceIn(1, 8)
        if (availableKb == null) return minOf(cpu, 2)
        val usableMb = availableKb / 1024 - RESERVE_MB
        val byMemory = (usableMb / MB_PER_JOB).toInt().coerceAtLeast(1)
        return minOf(cpu, byMemory)
    }

    /** Short enough on memory that starting another compile risks the process. */
    fun isTight(m: Memory): Boolean =
        m.availableKb < (RESERVE_MB + MB_PER_JOB) * 1024L || m.availableKb < m.totalKb / 20
}

class NdkLowMemoryException(val availableKb: Long) :
    RuntimeException("only ${availableKb / 1024} MB of memory is free")

/**
 * Admits compile jobs: up to [maxJobs] at once, but only ONE while memory is tight, and none (an error, after
 * [patienceMillis]) when even a single job would not fit.
 *
 * It throttles rather than aborts: a build that slows down finishes, one that is killed does not.
 */
internal class CompileScheduler(
    private val maxJobs: Int,
    private val memory: () -> NdkResources.Memory?,
    private val patienceMillis: Long = 30_000,
    private val pollMillis: Long = 100,
) {
    private val running = AtomicInteger(0)

    suspend fun <T> run(block: suspend () -> T): T {
        var waited = 0L
        while (true) {
            val m = memory()
            val allowed = if (m != null && NdkResources.isTight(m)) 1 else maxJobs
            val now = running.get()
            if (now < allowed) {
                val hardShort = m != null && m.availableKb < NdkResources.HARD_FLOOR_KB
                if (now == 0 && hardShort) {
                    // Nothing else is running and a single job still would not fit: wait for the system to
                    // free something, then give up with a message instead of being killed mid-compile.
                    if (waited >= patienceMillis) throw NdkLowMemoryException(m!!.availableKb)
                } else if (running.compareAndSet(now, now + 1)) {
                    break
                } else {
                    continue
                }
            }
            delay(pollMillis)
            waited += pollMillis
        }
        try {
            return block()
        } finally {
            running.decrementAndGet()
        }
    }

    /** For tests and diagnostics. */
    val active: Int get() = running.get()
}
