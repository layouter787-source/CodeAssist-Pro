package dev.codeassist.ndk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Paths

class NdkResourcesTest {

    @Test
    fun `MemAvailable is read when the kernel has it`() {
        val m = NdkResources.parseMeminfo("MemTotal:        7900000 kB\nMemFree:          100000 kB\nMemAvailable:    3000000 kB\n")!!
        assertEquals(3_000_000L, m.availableKb)
        assertEquals(7_900_000L, m.totalKb)
    }

    @Test
    fun `an old kernel without MemAvailable gets free plus cache`() {
        val m = NdkResources.parseMeminfo("MemTotal: 4000000 kB\nMemFree: 100000 kB\nBuffers: 50000 kB\nCached: 250000 kB\n")!!
        assertEquals(400_000L, m.availableKb)
    }

    @Test
    fun `no MemTotal means no reading`() {
        assertNull(NdkResources.parseMeminfo("nonsense"))
    }

    @Test
    fun `jobs follow the cores until memory runs short`() {
        // ~3.9 GB free: (3906 - 400) / 450 = 7 jobs worth of memory, 8 cores.
        assertEquals(7, NdkResources.planJobs(cores = 8, availableKb = 4_000_000))
        // ~976 MB free: one job's worth, whatever the core count.
        assertEquals(1, NdkResources.planJobs(cores = 8, availableKb = 1_000_000))
        // Plenty of memory: capped by the cores, and never above 8.
        assertEquals(4, NdkResources.planJobs(cores = 4, availableKb = 16_000_000))
        assertEquals(8, NdkResources.planJobs(cores = 64, availableKb = 64_000_000))
    }

    @Test
    fun `an unreadable meminfo is cautious rather than greedy`() {
        assertEquals(2, NdkResources.planJobs(cores = 8, availableKb = null))
        assertEquals(1, NdkResources.planJobs(cores = 1, availableKb = null))
    }

    @Test
    fun `a requested job count wins within bounds`() {
        assertEquals(3, NdkResources.planJobs(cores = 8, availableKb = 1_000_000, requested = 3))
        assertEquals(16, NdkResources.planJobs(cores = 8, availableKb = 1_000_000, requested = 99))
    }

    @Test
    fun `memory is tight below the reserve plus one job`() {
        assertTrue(NdkResources.isTight(NdkResources.Memory(availableKb = 700L * 1024, totalKb = 8_000_000)))
        assertFalse(NdkResources.isTight(NdkResources.Memory(availableKb = 2_000_000, totalKb = 8_000_000)))
    }
}

class NdkProcessTest {

    private val hasShell = File("/bin/sh").exists()

    @Test
    fun `output and the exit code come back`() {
        assumeTrue(hasShell)
        val r = NdkProcess.run(listOf("/bin/sh", "-c", "echo hi; exit 3"))
        assertEquals(3, r.exit)
        assertEquals("hi", r.output.trim())
    }

    @Test
    fun `cancelling stops a process that would run for half a minute`() {
        assumeTrue(hasShell)
        val started = System.nanoTime()
        var polls = 0
        val r = NdkProcess.run(listOf("/bin/sh", "-c", "exec sleep 30"), canceled = { ++polls >= 2 })
        assertTrue(r.canceled)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000, "took too long to stop")
    }

    @Test
    fun `the timeout fires while the process is still running`() {
        assumeTrue(hasShell)
        val started = System.nanoTime()
        val r = NdkProcess.run(listOf("/bin/sh", "-c", "exec sleep 30"), timeoutMillis = 300)
        assertTrue(r.timedOut)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000, "took too long to stop")
    }

    @Test
    fun `input larger than a pipe buffer does not deadlock`() {
        assumeTrue(hasShell)
        val input = "x".repeat(1_000_000)
        val r = NdkProcess.run(listOf("/bin/sh", "-c", "cat"), stdin = input, timeoutMillis = 20_000)
        assertFalse(r.timedOut)
        assertEquals(input.length, r.output.length)
    }

    @Test
    fun `the parent pid is read past a command name with spaces and parentheses`() {
        assertEquals(77, NdkProcess.parentPid("1234 (my (odd) name) S 77 1234 1234 0 -1 4194560"))
        assertNull(NdkProcess.parentPid("garbage"))
    }
}

class NdkFacetImportFieldsTest {

    @Test
    fun `a facet with none of the new fields writes none of the new keys`() {
        val keys = NdkFacetCodec.encode(NdkFacet()).keys
        assertFalse("includeDirs" in keys)
        assertFalse("defines" in keys)
        assertFalse("jobs" in keys)
    }

    @Test
    fun `the new fields survive encode and decode`() {
        val facet = NdkFacet(includeDirs = listOf("include", "../shared/include"), defines = listOf("A=1", "B"), jobs = 3)
        assertEquals(facet, NdkFacetCodec.decode(NdkFacetCodec.encode(facet)))
    }

    @Test
    fun `a negative jobs value means automatic`() {
        assertEquals(0, NdkFacetCodec.decode(mapOf("jobs" to -4)).jobs)
    }

    @Test
    fun `relative include directories resolve against the module and are dropped without one`() {
        val facet = NdkFacet(includeDirs = listOf("include", "/abs/x"))
        assertEquals(listOf("-I/m/include", "-I/abs/x"), NdkFlags.includeFlags(facet, Paths.get("/m")))
        assertEquals(listOf("-I/abs/x"), NdkFlags.includeFlags(facet, null))
    }
}
