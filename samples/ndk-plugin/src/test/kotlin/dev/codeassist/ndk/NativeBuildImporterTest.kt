package dev.codeassist.ndk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/** A filesystem that is a map of path to text, so an import is tested without touching a disk. */
private class MapFs(files: Map<String, String>) : ImportFs {
    private val files: Map<Path, String> = files.mapKeys { Paths.get(it.key).normalize() }
    override fun read(path: Path): String? = files[path.normalize()]
    override fun isFile(path: Path): Boolean = files.containsKey(path.normalize())
    override fun walk(dir: Path): List<Path> =
        files.keys.filter { it.startsWith(dir.normalize()) }.sortedBy { it.toString() }
}

class NativeBuildImporterTest {

    private fun import(files: Map<String, String>, module: String = "/m"): NativeImport =
        NativeBuildImporter.import(Paths.get(module), null, MapFs(files))

    private fun imported(r: NativeImport): NativeImport.Imported =
        r as? NativeImport.Imported ?: error("expected an import, got $r")

    @Test
    fun `the CMakeLists Android Studio generates imports with no warnings`() {
        val r = imported(
            import(
                mapOf(
                    "/m/src/main/cpp/CMakeLists.txt" to """
                        cmake_minimum_required(VERSION 3.22.1)
                        project("native-lib")
                        set(CMAKE_CXX_STANDARD 20)
                        add_library(\${CMAKE_PROJECT_NAME} SHARED native-lib.cpp util/helper.cpp)
                        find_library(log-lib log)
                        target_link_libraries(\${CMAKE_PROJECT_NAME} android \${log-lib})
                    """.trimIndent(),
                    "/m/src/main/cpp/native-lib.cpp" to "",
                    "/m/src/main/cpp/util/helper.cpp" to "",
                )
            )
        )
        assertEquals(listOf("src/main/cpp"), r.facet.sourceDirs)
        assertEquals("native-lib", r.facet.libraryName)
        assertEquals("c++20", r.facet.cppStandard)
        assertEquals(listOf("android", "log"), r.facet.linkLibraries)
        assertEquals(emptyList<String>(), r.warnings)
    }

    @Test
    fun `a GLOB, include directories and definitions come through`() {
        val r = imported(
            import(
                mapOf(
                    "/m/CMakeLists.txt" to """
                        project(game)
                        file(GLOB_RECURSE SRC \${CMAKE_CURRENT_SOURCE_DIR}/src/*.cpp)
                        add_library(game SHARED \${SRC})
                        target_include_directories(game PRIVATE include)
                        target_compile_definitions(game PRIVATE USE_GL=1 NDEBUG)
                        target_link_libraries(game GLESv3 EGL)
                    """.trimIndent(),
                    "/m/src/a.cpp" to "",
                    "/m/src/sub/b.cpp" to "",
                    "/m/src/readme.txt" to "",
                    "/m/include/a.h" to "",
                )
            )
        )
        assertEquals(listOf("src"), r.facet.sourceDirs)
        assertEquals(listOf("include"), r.facet.includeDirs)
        assertEquals(listOf("USE_GL=1", "NDEBUG"), r.facet.defines)
        assertEquals(listOf("GLESv3", "EGL"), r.facet.linkLibraries)
        assertEquals(emptyList<String>(), r.warnings)
    }

    @Test
    fun `a static library the shared one links is folded in`() {
        val r = imported(
            import(
                mapOf(
                    "/m/CMakeLists.txt" to """
                        project(app)
                        add_library(core STATIC core/core.cpp)
                        add_library(app SHARED app/app.cpp)
                        if(ANDROID)
                          target_link_libraries(app core log)
                        else()
                          target_link_libraries(app pthread)
                        endif()
                    """.trimIndent(),
                    "/m/app/app.cpp" to "",
                    "/m/core/core.cpp" to "",
                )
            )
        )
        assertEquals(listOf("app", "core"), r.facet.sourceDirs)
        // `core` is a target, so it is compiled in rather than linked; pthread is on the branch ANDROID skips.
        assertEquals(listOf("log"), r.facet.linkLibraries)
    }

    @Test
    fun `if, elseif and else pick the branch for Android`() {
        val r = imported(
            import(
                mapOf(
                    "/m/CMakeLists.txt" to """
                        project(p)
                        add_library(p SHARED src/a.cpp)
                        if(NOT ANDROID)
                          target_link_libraries(p pthread)
                        elseif(ANDROID)
                          target_link_libraries(p log)
                        else()
                          target_link_libraries(p dl)
                        endif()
                    """.trimIndent(),
                    "/m/src/a.cpp" to "",
                )
            )
        )
        assertEquals(listOf("log"), r.facet.linkLibraries)
    }

    @Test
    fun `a condition it cannot decide applies the first branch and says so`() {
        val r = imported(
            import(
                mapOf(
                    "/m/CMakeLists.txt" to """
                        project(x)
                        if(FOO STREQUAL "a" AND (BAR OR BAZ))
                          add_library(x SHARED src/a.cpp)
                        endif()
                    """.trimIndent(),
                    "/m/src/a.cpp" to "",
                )
            )
        )
        assertEquals("x", r.facet.libraryName)
        assertTrue(r.warnings.any { "could not evaluate" in it }, r.warnings.toString())
    }

    @Test
    fun `the NDK's native app glue becomes the nativeActivity switch`() {
        val r = imported(
            import(
                mapOf(
                    "/m/CMakeLists.txt" to """
                        project(game)
                        add_library(native_app_glue STATIC \${ANDROID_NDK}/sources/android/native_app_glue/android_native_app_glue.c)
                        add_library(game SHARED src/game.cpp)
                        target_link_libraries(game native_app_glue android log)
                    """.trimIndent(),
                    "/m/src/game.cpp" to "",
                )
            )
        )
        assertTrue(r.facet.nativeActivity)
        assertEquals(listOf("src"), r.facet.sourceDirs)
        assertEquals(listOf("android", "log"), r.facet.linkLibraries)
    }

    @Test
    fun `files in a source directory that the target does not list are reported`() {
        val r = imported(
            import(
                mapOf(
                    "/m/CMakeLists.txt" to "project(p)\nadd_library(p SHARED src/a.cpp)\n",
                    "/m/src/a.cpp" to "",
                    "/m/src/unused.cpp" to "",
                )
            )
        )
        assertTrue(r.warnings.any { "unused.cpp" in it }, r.warnings.toString())
    }

    @Test
    fun `a script with no shared library fails with the reason`() {
        val r = import(mapOf("/m/CMakeLists.txt" to "project(p)\nadd_library(p STATIC a.cpp)\n", "/m/a.cpp" to ""))
        assertTrue(r is NativeImport.Failed, r.toString())
        assertTrue((r as NativeImport.Failed).reason.contains("shared library"))
    }

    @Test
    fun `no script at all is a failure, not a crash`() {
        assertTrue(import(mapOf("/m/src/a.cpp" to "")) is NativeImport.Failed)
    }

    @Test
    fun `ndk-build Android mk and Application mk import`() {
        val r = imported(
            import(
                mapOf(
                    "/m/jni/Android.mk" to """
                        LOCAL_PATH := \$(call my-dir)
                        include \$(CLEAR_VARS)
                        LOCAL_MODULE := native-lib
                        LOCAL_SRC_FILES := native-lib.cpp \
                            util.cpp
                        LOCAL_C_INCLUDES += \$(LOCAL_PATH)/include
                        LOCAL_CFLAGS := -DFOO=1 -O3
                        LOCAL_LDLIBS := -llog -landroid
                        include \$(BUILD_SHARED_LIBRARY)
                    """.trimIndent(),
                    "/m/jni/Application.mk" to "APP_STL := c++_shared\nAPP_PLATFORM := android-28\n",
                    "/m/jni/native-lib.cpp" to "",
                    "/m/jni/util.cpp" to "",
                )
            )
        )
        assertEquals("native-lib", r.facet.libraryName)
        assertEquals(listOf("jni"), r.facet.sourceDirs)
        assertEquals(listOf("jni/include"), r.facet.includeDirs)
        assertEquals(listOf("FOO=1"), r.facet.defines)
        assertEquals("-O3", r.facet.optimization)
        assertEquals(listOf("log", "android"), r.facet.linkLibraries)
        assertEquals("c++_shared", r.facet.stl)
        assertEquals(28, r.facet.minSdk)
    }

    @Test
    fun `Gradle's ANDROID_STL, abiFilters and minSdk are read`() {
        val s = GradleNativeSettings.parse(
            """
            android { defaultConfig { minSdk = 26
              externalNativeBuild { cmake { arguments("-DANDROID_STL=c++_shared") } }
              ndk { abiFilters += listOf("arm64-v8a", "x86_64") } } }
            """.trimIndent()
        )
        assertEquals("c++_shared", s.stl)
        assertEquals(listOf("arm64-v8a", "x86_64"), s.abis)
        assertEquals(26, s.minSdk)
    }

    @Test
    fun `the CMake parser keeps quoting, skips comments and counts lines`() {
        val cmds = CMakeParser.parse("# c\nset(A \"x;y\" b) #[[ block\ncomment ]]\nadd_library(l SHARED a.cpp)\n")
        assertEquals(listOf("set", "add_library"), cmds.map { it.name })
        assertEquals(listOf(CMakeArg("A", false), CMakeArg("x;y", true), CMakeArg("b", false)), cmds[0].args)
        assertEquals(4, cmds[1].line)
    }
}
