package dev.codeassist.ndk

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs a tool and can STOP it.
 *
 * The first version of [NdkToolchain.run] read the output to its end BEFORE it started the clock, so the
 * timeout only fired for a process that had already finished, and "cancel" meant "wait for this compile to
 * end on its own". Here the output is drained on its own thread while the caller polls: the process ends, the
 * deadline passes, or [canceled] says so, whichever comes first.
 *
 * Stopping means the whole tree. clang runs the linker as a child (`--ld-path`), and killing only the driver
 * would leave lld to finish writing a library nobody wants into the output directory.
 */
internal object NdkProcess {

    class Result(
        val exit: Int,
        val output: String,
        val canceled: Boolean = false,
        val timedOut: Boolean = false,
    )

    private const val POLL_MILLIS = 100L

    fun run(
        command: List<String>,
        env: Map<String, String> = emptyMap(),
        workingDir: File? = null,
        stdin: String? = null,
        timeoutMillis: Long = 0L,
        canceled: () -> Boolean = { false },
    ): Result {
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        workingDir?.takeIf { it.isDirectory }?.let { builder.directory(it) }
        builder.environment().putAll(env)
        val process = builder.start()

        val output = StringBuffer()
        val reader = Thread({
            runCatching {
                process.inputStream.reader().use { r ->
                    val buffer = CharArray(8192)
                    while (true) {
                        val n = r.read(buffer)
                        if (n < 0) break
                        output.append(buffer, 0, n)
                    }
                }
            }
        }, "ndk-process-output").apply { isDaemon = true; start() }

        // With the reader already draining, writing the buffer cannot deadlock against a full output pipe.
        if (stdin != null) {
            runCatching { process.outputStream.use { it.write(stdin.toByteArray()) } }
        } else {
            runCatching { process.outputStream.close() }
        }

        val started = System.nanoTime()
        var wasCanceled = false
        var timedOut = false
        while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
            if (canceled()) { wasCanceled = true; break }
            if (timeoutMillis > 0 && (System.nanoTime() - started) / 1_000_000 > timeoutMillis) {
                timedOut = true
                break
            }
        }
        val stopped = wasCanceled || timedOut
        if (stopped) terminate(process)
        // A child that outlived the driver can hold the pipe open; do not wait for it forever.
        runCatching { reader.join(if (stopped) 2_000 else 10_000) }

        val exit = runCatching { process.exitValue() }.getOrDefault(-1)
        return Result(exit, output.toString(), wasCanceled, timedOut)
    }

    // ---------------------------------------------------------------------------------------------
    // Stopping a process and everything it started
    // ---------------------------------------------------------------------------------------------

    /** SIGTERM first so clang can remove its temporary files, SIGKILL for whatever ignores it. */
    private fun terminate(process: Process) {
        val pid = pidOf(process)
        if (pid == null) {
            process.destroy()
            if (!process.waitFor(1_500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            return
        }
        // Read the tree BEFORE signalling: once the parent dies its children are re-parented and the link
        // between them is gone.
        val tree = descendants(pid)
        signal(pid, SIGTERM)
        tree.forEach { signal(it, SIGTERM) }
        if (!process.waitFor(1_500, TimeUnit.MILLISECONDS)) {
            signal(pid, SIGKILL)
            process.destroyForcibly()
        }
        (tree + descendants(pid)).distinct().forEach { signal(it, SIGKILL) }
    }

    /**
     * The process id: `Process.pid()` where it exists (API 33), the private field before that. Null when
     * neither works, in which case the caller falls back to [Process.destroyForcibly] and loses only the
     * children.
     */
    private fun pidOf(process: Process): Int? = try {
        when (val v = Process::class.java.getMethod("pid").invoke(process)) {
            is Long -> v.toInt()
            is Int -> v
            else -> null
        }
    } catch (e: Throwable) {
        runCatching {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(process)
        }.getOrNull()
    }

    /** Every descendant of [root], read from /proc (the processes of this UID are visible to it). */
    fun descendants(root: Int): List<Int> {
        val children = HashMap<Int, MutableList<Int>>()
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            val ppid = runCatching { parentPid(File(dir, "stat").readText()) }.getOrNull() ?: return@forEach
            children.getOrPut(ppid) { mutableListOf() }.add(pid)
        }
        val found = ArrayList<Int>()
        val stack = mutableListOf(root)
        while (stack.isNotEmpty()) {
            val p = stack.removeAt(stack.lastIndex)
            children[p]?.forEach { found += it; stack += it }
        }
        return found
    }

    /**
     * The parent pid out of a `/proc/<pid>/stat` line. The command name is in parentheses and may itself
     * contain spaces and parentheses, so the fields start after the LAST closing one.
     */
    fun parentPid(stat: String): Int? {
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        return stat.substring(close + 1).trim().split(' ').getOrNull(1)?.toIntOrNull()
    }

    private const val SIGTERM = 15
    private const val SIGKILL = 9

    private fun signal(pid: Int, signal: Int) {
        // Best effort: the process may already be gone, and on a JVM test there is no android.system.Os.
        try {
            android.system.Os.kill(pid, signal)
        } catch (e: Throwable) {
        }
    }
}
