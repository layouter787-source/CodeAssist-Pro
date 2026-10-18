package dev.codeassist.ndk

import java.nio.file.Path
import java.nio.file.Paths

/**
 * The flags a module's C and C++ are compiled with, in ONE place, because the editor and the build have to
 * agree on them.
 *
 * They are the same compiler, so a flag the build passes and the editor does not shows up as an error that
 * is only in the editor -- a NativeActivity project reports `'android_native_app_glue.h' file not found` on
 * line 2 of a file that builds perfectly, which reads as the plugin being broken rather than as a missing
 * `-I`.
 */
internal object NdkFlags {

    /**
     * What the editor asks clang with: everything that changes what PARSES, and nothing about codegen.
     *
     * [moduleDir] resolves the facet's relative `includeDirs`. The build knows it; the editor's callers do not
     * pass it, so it is looked up from the module directory diagnostics last [remember]ed for this facet.
     */
    fun editor(facet: NdkFacet, toolchain: NdkToolchain, cpp: Boolean, moduleDir: Path? = null): List<String> = buildList {
        add("-std=" + if (cpp) facet.cppStandard else facet.cStandard)
        // The glue is a header the module includes and a source it compiles, both from the packaged
        // directory: without this the editor cannot see the header the build compiles against.
        if (facet.nativeActivity) toolchain.nativeAppGlueDir?.let { add("-I$it") }
        addAll(includeFlags(facet, moduleDir ?: moduleDirOf(facet)))
        facet.defines.forEach { add("-D$it") }
        // Last, so a user's own flag wins over anything derived above.
        addAll(facet.compilerFlags)
    }

    /** What the build adds on top: position independence and the optimization level. */
    fun build(facet: NdkFacet, toolchain: NdkToolchain, cpp: Boolean, moduleDir: Path? = null): List<String> =
        listOf("-fPIC", facet.optimization) + editor(facet, toolchain, cpp, moduleDir)

    /**
     * `-I` for each of the facet's include directories. An absolute one is used as written; a relative one
     * is resolved against the module, and DROPPED when there is no module to resolve it against, because a
     * path resolved against whatever the working directory happens to be is a different header, not a
     * missing one.
     */
    internal fun includeFlags(facet: NdkFacet, moduleDir: Path?): List<String> = facet.includeDirs.mapNotNull { dir ->
        val p = runCatching { Paths.get(dir) }.getOrNull() ?: return@mapNotNull null
        val resolved = when {
            p.isAbsolute -> p
            moduleDir != null -> moduleDir.resolve(p)
            else -> return@mapNotNull null
        }
        "-I" + resolved.normalize()
    }

    // -----------------------------------------------------------------------------------------------
    // Reaching the facet from a place the host does not hand a Module
    // -----------------------------------------------------------------------------------------------

    private val byModuleDir = LinkedHashMap<String, NdkFacet>()

    /**
     * Record [facet] as the configuration for everything under [moduleDir].
     *
     * Completion reads the facet from `CompletionParams.module`, which arrived in SPI 3.1.0. On an older host
     * it has no way to ask which module its file belongs to (diagnostics, through `AnalysisTarget.module`,
     * does), and this is the seam between the two -- the diagnostics provider runs on every open C or C++
     * file, so by the time a popup is asked for in one, its module's facet has been seen.
     *
     * A miss is not a failure: [facetFor] answers null and the caller uses the defaults, which is what a
     * module carrying no facet would give anyway.
     */
    @Synchronized
    fun remember(moduleDir: String, facet: NdkFacet) {
        byModuleDir[moduleDir.trimEnd('/')] = facet
    }

    /** The facet of the module owning [file], or null when no module containing it has been seen. */
    @Synchronized
    fun facetFor(file: Path): NdkFacet? {
        val path = file.toString()
        // Longest prefix wins, so a nested module is not shadowed by the project root it sits in.
        return byModuleDir.entries
            .filter { path.startsWith(it.key + "/") }
            .maxByOrNull { it.key.length }
            ?.value
    }

    /**
     * The directory of the module [facet] was remembered for.
     *
     * The very instance first (diagnostics hands the one it just remembered), then an equal one, which is how
     * completion's freshly decoded copy finds it. When several modules share an identical facet the answer is
     * ambiguous and is null: guessing would point `-I` at another module's headers.
     */
    @Synchronized
    private fun moduleDirOf(facet: NdkFacet): Path? {
        byModuleDir.entries.firstOrNull { it.value === facet }?.let { return runCatching { Paths.get(it.key) }.getOrNull() }
        val equal = byModuleDir.entries.filter { it.value == facet }.map { it.key }.distinct()
        return equal.singleOrNull()?.let { runCatching { Paths.get(it) }.getOrNull() }
    }
}
