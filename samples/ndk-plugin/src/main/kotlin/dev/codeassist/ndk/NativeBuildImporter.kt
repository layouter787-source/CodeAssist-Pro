package dev.codeassist.ndk

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

/**
 * Reads an existing project's native build (CMake or ndk-build) and says what the `[ndk]` table for it is.
 *
 * The plugin deliberately has no CMake or ninja: the facet is declarative, which is what lets a C++ build fit
 * on a phone beside the compiler. That leaves the projects people already have, which describe themselves
 * in CMakeLists.txt or Android.mk. This evaluates the part of those languages that real Android projects
 * use (variables, file(GLOB), add_library, include/define/option commands, static-library dependencies, the
 * conditions that depend on the platform) and maps it onto the facet. It is NOT CMake: whatever it cannot
 * express is reported as a warning rather than guessed at, because a silently different build is worse than
 * an import that says what it left out.
 */
interface ImportFs {
    fun read(path: Path): String?
    fun isFile(path: Path): Boolean

    /** Every regular file under [dir], recursively; empty when there is no such directory. */
    fun walk(dir: Path): List<Path>
}

object RealImportFs : ImportFs {
    override fun read(path: Path): String? = runCatching { String(Files.readAllBytes(path)) }.getOrNull()
    override fun isFile(path: Path): Boolean = Files.isRegularFile(path)
    override fun walk(dir: Path): List<Path> =
        if (!Files.isDirectory(dir)) emptyList()
        else Files.walk(dir).use { w -> w.filter { Files.isRegularFile(it) }.collect(Collectors.toList()) }
}

sealed interface NativeImport {
    data class Imported(val facet: NdkFacet, val script: Path, val warnings: List<String>) : NativeImport
    data class Failed(val reason: String, val warnings: List<String> = emptyList()) : NativeImport
}

object NativeBuildImporter {

    private val SCRIPTS = listOf(
        "src/main/cpp/CMakeLists.txt", "src/main/jni/CMakeLists.txt", "CMakeLists.txt",
        "cpp/CMakeLists.txt", "jni/CMakeLists.txt",
        "src/main/jni/Android.mk", "jni/Android.mk", "Android.mk",
    )

    /** The module's native build script, in the places Android projects keep it; null when it has none. */
    fun findScript(moduleDir: Path, fs: ImportFs = RealImportFs): Path? =
        SCRIPTS.map { moduleDir.resolve(it) }.firstOrNull { fs.isFile(it) }

    fun import(moduleDir: Path, script: Path? = null, fs: ImportFs = RealImportFs): NativeImport {
        val file = script ?: findScript(moduleDir, fs)
            ?: return NativeImport.Failed("No CMakeLists.txt or Android.mk found in ${moduleDir.fileName}.")
        val text = fs.read(file) ?: return NativeImport.Failed("Could not read $file.")
        val model = NativeModel()
        val module = moduleDir.toAbsolutePath().normalize()

        if (file.fileName.toString().equals("Android.mk", ignoreCase = true)) {
            AndroidMkReader(fs, model).read(file.toAbsolutePath().normalize(), text)
        } else {
            CMakeEvaluator(fs, model).run(file.toAbsolutePath().normalize(), text)
        }
        GradleNativeSettings.read(module, fs)?.let { model.gradle = it }
        return model.toFacet(module, file, fs)
    }
}

// ---------------------------------------------------------------------------------------------------
// What both readers produce
// ---------------------------------------------------------------------------------------------------

internal class NativeTarget(val name: String, var type: String) {
    val sources = ArrayList<Path>()
    val includes = ArrayList<Path>()
    val defines = ArrayList<String>()
    val options = ArrayList<String>()
    val links = ArrayList<String>()
    var outputName: String? = null
    var cppStandard: String? = null
    var cStandard: String? = null
}

internal class NativeModel {
    val targets = LinkedHashMap<String, NativeTarget>()
    val includes = ArrayList<Path>()
    val defines = ArrayList<String>()
    val options = ArrayList<String>()
    val warnings = ArrayList<String>()
    var cppStandard: String? = null
    var cStandard: String? = null
    var stl: String? = null
    var minSdk: Int? = null
    var gradle: GradleNativeSettings.Settings? = null

    fun warn(message: String) {
        if (message !in warnings) warnings += message
    }

    private val cExt = setOf("c", "cc", "cpp", "cxx", "c++")
    private val asmExt = setOf("s", "asm")

    fun toFacet(module: Path, script: Path, fs: ImportFs): NativeImport {
        val shared = targets.values.filter { it.type == "SHARED" || it.type == "MODULE" }
        val main = shared.firstOrNull() ?: return NativeImport.Failed(
            "The script defines no shared library (add_library(<name> SHARED ...) / BUILD_SHARED_LIBRARY). " +
                "Only a shared library can go into an APK.",
            warnings,
        )
        if (shared.size > 1) {
            warn("${shared.size} shared libraries are defined; importing '${main.name}' only, because a module " +
                "has one [ndk] library. Not imported: ${shared.drop(1).joinToString { it.name }}.")
        }

        // The shared library plus the static libraries it links, transitively: the facet compiles one set of
        // sources into one library, so a static dependency has to be folded in rather than linked.
        val closure = LinkedHashSet<NativeTarget>()
        fun visit(t: NativeTarget) {
            if (!closure.add(t)) return
            for (l in t.links) {
                val dep = targets[l] ?: continue
                when (dep.type) {
                    "STATIC", "OBJECT", "INTERFACE" -> visit(dep)
                    "SHARED", "MODULE" -> warn("'${t.name}' links the project's own shared library '$l'; it is not bundled, " +
                        "so its symbols will be undefined at link time.")
                }
            }
        }
        visit(main)

        var nativeActivity = closure.any { c -> c.links.any { it == "native_app_glue" || it == "android_native_app_glue" } }
        val sources = LinkedHashSet<Path>()
        for (p in closure.flatMap { it.sources }) {
            val name = p.fileName.toString()
            val ext = name.substringAfterLast('.', "").lowercase()
            when {
                name.startsWith("android_native_app_glue") -> nativeActivity = true // the toolchain compiles it itself
                ext in cExt -> sources += p
                ext in asmExt -> warn("$name is assembly, which this plugin does not compile; it was left out.")
            }
        }
        if (sources.isEmpty()) warn("No C or C++ sources were found for '${main.name}'.")

        // Directories, as the facet wants them: the minimal set that covers every source.
        val dirs = sources.mapNotNull { it.parent }.distinct().sortedBy { it.toString() }
        val roots = dirs.filter { d -> dirs.none { o -> o != d && d.startsWith(o) } }
        val covered = HashSet<Path>(sources)
        val extra = roots.flatMap { fs.walk(it) }
            .filter { it.fileName.toString().substringAfterLast('.', "").lowercase() in cExt }
            .filter { it !in covered && !it.toString().contains("/build/") && !it.toString().contains("/.cxx/") }
            .distinct()
        if (extra.isNotEmpty()) {
            warn("${extra.size} C/C++ file(s) in the source directories are not part of '${main.name}' and will be " +
                "compiled too (the facet builds a whole directory), e.g. ${extra.take(3).joinToString { it.fileName.toString() }}.")
        }
        val sourceDirs = roots.map { rel(module, it) }.ifEmpty { listOf("src/main/cpp") }
        if (sourceDirs.contains(".")) warn("The sources sit directly in the module directory, so the whole module is scanned.")

        val includeDirs = (includes + closure.flatMap { it.includes }).distinct().map { rel(module, it) }
        val defs = LinkedHashSet<String>()
        defs.addAll(defines)
        closure.forEach { defs.addAll(it.defines) }

        // Options: fold -I/-D/-std/-O into their own fields, keep the rest as written.
        val flagTokens = options + closure.flatMap { it.options }
        val flags = ArrayList<String>()
        var std: String? = null
        var cStd: String? = null
        var optimization: String? = null
        val extraIncludes = ArrayList<String>()
        for (f in flagTokens) {
            when {
                f.startsWith("-std=") -> {
                    val v = f.removePrefix("-std=")
                    if (v.contains("++")) std = v else cStd = v
                }
                f.startsWith("-D") && f.length > 2 -> defs.add(f.substring(2))
                f.startsWith("-I") && f.length > 2 -> extraIncludes.add(rel(module, resolve(module, f.substring(2))))
                Regex("-O[0-3sz]").matches(f) -> optimization = f
                f == "-fPIC" -> Unit // always passed by the build
                else -> flags.add(f)
            }
        }

        val libs = ArrayList<String>()
        for (item in closure.flatMap { it.links }) {
            when {
                targets.containsKey(item) -> Unit
                item == "native_app_glue" || item == "android_native_app_glue" -> Unit
                item == "c++_shared" || item == "c++_static" || item == "stdc++" -> Unit
                item.startsWith("-l") && item.length > 2 -> libs.add(item.substring(2))
                item.contains("/") || item.endsWith(".so") || item.endsWith(".a") ->
                    warn("Prebuilt library '$item' is not linked; add its -L directory and name to compilerFlags.")
                item.contains("::") || item.contains("\$<") ->
                    warn("Imported target '$item' is not supported; link it by name through linkLibraries if it is a system library.")
                Regex("[A-Za-z0-9_.+-]+").matches(item) -> libs.add(item)
                else -> warn("Link item '$item' was not understood and was skipped.")
            }
        }

        val g = gradle
        val facet = NdkFacet(
            sourceDirs = sourceDirs,
            libraryName = main.outputName ?: main.name,
            abis = g?.abis?.takeIf { it.isNotEmpty() } ?: NdkFacet().abis,
            minSdk = g?.minSdk ?: minSdk ?: NdkFacet().minSdk,
            cppStandard = main.cppStandard ?: std ?: cppStandard ?: NdkFacet().cppStandard,
            cStandard = main.cStandard ?: cStd ?: cStandard ?: NdkFacet().cStandard,
            stl = g?.stl ?: stl ?: NdkFacet().stl,
            optimization = optimization ?: NdkFacet().optimization,
            compilerFlags = flags.distinct(),
            linkLibraries = libs.distinct(),
            nativeActivity = nativeActivity,
            includeDirs = (includeDirs + extraIncludes).distinct(),
            defines = defs.toList(),
        )
        val extraAbis = facet.abis.filter { it != "arm64-v8a" }
        if (extraAbis.isNotEmpty()) {
            warn("ABIs ${extraAbis.joinToString()} are listed, but the bundled toolchain only targets arm64-v8a; the others are skipped at build time.")
        }
        return NativeImport.Imported(facet, script, warnings.toList())
    }

    private fun resolve(module: Path, s: String): Path {
        val p = Paths.get(s)
        return (if (p.isAbsolute) p else module.resolve(p)).normalize()
    }

    private fun rel(module: Path, p: Path): String {
        val r = module.relativize(p.normalize()).toString().replace('\\', '/')
        return r.ifEmpty { "." }
    }
}

// ---------------------------------------------------------------------------------------------------
// Gradle: the settings that live outside the build script (ANDROID_STL, abiFilters, minSdk)
// ---------------------------------------------------------------------------------------------------

internal object GradleNativeSettings {
    class Settings(val stl: String?, val abis: List<String>, val minSdk: Int?)

    fun read(module: Path, fs: ImportFs): Settings? {
        val text = listOf("build.gradle.kts", "build.gradle").firstNotNullOfOrNull { fs.read(module.resolve(it)) } ?: return null
        return parse(text)
    }

    fun parse(text: String): Settings {
        val stl = Regex("ANDROID_STL=([A-Za-z0-9_+]+)").find(text)?.groupValues?.get(1)
        val abis = Regex("[^\\n]*abiFilters[^\\n]*").findAll(text)
            .flatMap { line -> Regex("[\"']([A-Za-z0-9_-]+)[\"']").findAll(line.value).map { it.groupValues[1] } }
            .filter { it in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
            .distinct().toList()
        val minSdk = Regex("minSdk(?:Version)?\\s*(?:=)?\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        return Settings(stl, abis, minSdk)
    }
}

// ---------------------------------------------------------------------------------------------------
// CMake
// ---------------------------------------------------------------------------------------------------

internal data class CMakeArg(val text: String, val quoted: Boolean)
internal data class CMakeCommand(val name: String, val args: List<CMakeArg>, val line: Int)

internal object CMakeParser {

    fun parse(text: String): List<CMakeCommand> {
        val out = ArrayList<CMakeCommand>()
        val n = text.length
        var i = 0
        var line = 1
        while (i < n) {
            val c = text[i]
            when {
                c == '\n' -> { line++; i++ }
                c.isWhitespace() -> i++
                c == '#' -> { val e = skipComment(text, i); line += newlines(text, i, e); i = e }
                c.isLetter() || c == '_' -> {
                    var j = i
                    while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                    val name = text.substring(i, j)
                    var k = j
                    while (k < n && (text[k] == ' ' || text[k] == '\t')) k++
                    if (k < n && text[k] == '(') {
                        val (args, end) = parseArgs(text, k + 1)
                        out += CMakeCommand(name.lowercase(), args, line)
                        line += newlines(text, k, end)
                        i = end
                    } else {
                        i = j
                    }
                }
                else -> i++
            }
        }
        return out
    }

    private fun parseArgs(t: String, start: Int): Pair<List<CMakeArg>, Int> {
        val n = t.length
        val args = ArrayList<CMakeArg>()
        var depth = 0
        var i = start
        while (i < n) {
            val c = t[i]
            when {
                c.isWhitespace() -> i++
                c == '#' -> i = skipComment(t, i)
                c == '(' -> { depth++; args += CMakeArg("(", false); i++ }
                c == ')' -> {
                    if (depth == 0) return args to (i + 1)
                    depth--; args += CMakeArg(")", false); i++
                }
                c == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < n && t[i] != '"') {
                        if (t[i] == '\\' && i + 1 < n) { sb.append(t[i + 1]); i += 2 } else { sb.append(t[i]); i++ }
                    }
                    i++
                    args += CMakeArg(sb.toString(), true)
                }
                else -> {
                    var j = i
                    while (j < n && !t[j].isWhitespace() && t[j] != '(' && t[j] != ')' && t[j] != '#') j++
                    args += CMakeArg(t.substring(i, j), false)
                    i = j
                }
            }
        }
        return args to n
    }

    private fun skipComment(t: String, i: Int): Int {
        if (t.startsWith("#[", i)) {
            var k = i + 2
            var eq = 0
            while (k < t.length && t[k] == '=') { eq++; k++ }
            if (k < t.length && t[k] == '[') {
                val close = "]" + "=".repeat(eq) + "]"
                val e = t.indexOf(close, k + 1)
                return if (e < 0) t.length else e + close.length
            }
        }
        var k = i
        while (k < t.length && t[k] != '\n') k++
        return k
    }

    private fun newlines(t: String, from: Int, to: Int): Int {
        var c = 0
        for (k in from until minOf(to, t.length)) if (t[k] == '\n') c++
        return c
    }
}

internal class CMakeEvaluator(private val fs: ImportFs, private val model: NativeModel) {

    private val vars = HashMap<String, String>()
    private var rootDir: Path? = null
    private var firstProject = true

    private class Frame(val parentActive: Boolean, var taken: Boolean, var active: Boolean)

    private val visibility = setOf("PUBLIC", "PRIVATE", "INTERFACE", "SYSTEM", "BEFORE", "AFTER", "LINK_PUBLIC", "LINK_PRIVATE")
    private val trueConstants = setOf("1", "ON", "YES", "TRUE", "Y")
    private val falseConstants = setOf("0", "OFF", "NO", "FALSE", "N", "IGNORE", "NOTFOUND", "")

    fun run(file: Path, text: String) {
        rootDir = file.parent
        vars["ANDROID"] = "1"
        vars["UNIX"] = "1"
        vars["CMAKE_SYSTEM_NAME"] = "Android"
        vars["ANDROID_ABI"] = "arm64-v8a"
        vars["CMAKE_ANDROID_ARCH_ABI"] = "arm64-v8a"
        vars["CMAKE_BUILD_TYPE"] = "Release"
        vars["ANDROID_PLATFORM"] = "android-26"
        vars["ANDROID_NDK"] = ""
        vars["CMAKE_SOURCE_DIR"] = file.parent.toString()
        vars["PROJECT_SOURCE_DIR"] = file.parent.toString()
        execute(CMakeParser.parse(text), file.parent, 0)

        vars["CMAKE_CXX_STANDARD"]?.takeIf { it.isNotBlank() }?.let { model.cppStandard = "c++$it" }
        vars["CMAKE_C_STANDARD"]?.takeIf { it.isNotBlank() }?.let { model.cStandard = "c$it" }
        for (key in listOf("CMAKE_C_FLAGS", "CMAKE_CXX_FLAGS")) {
            vars[key]?.let { model.options += splitFlags(it) }
        }
        model.options.removeAll { it.isBlank() }
    }

    // ----------------------------------------------------------------------- execution

    private fun execute(cmds: List<CMakeCommand>, dir: Path, depth: Int) {
        val savedDir = vars["CMAKE_CURRENT_SOURCE_DIR"]
        val savedList = vars["CMAKE_CURRENT_LIST_DIR"]
        vars["CMAKE_CURRENT_SOURCE_DIR"] = dir.toString()
        vars["CMAKE_CURRENT_LIST_DIR"] = dir.toString()

        val frames = ArrayList<Frame>()
        fun active() = frames.lastOrNull()?.active ?: true
        var idx = 0
        while (idx < cmds.size) {
            val cmd = cmds[idx]
            idx++
            when (cmd.name) {
                "function", "macro", "foreach", "while" -> {
                    if (active() && (cmd.name == "foreach" || cmd.name == "while")) {
                        model.warn("line ${cmd.line}: ${cmd.name}() is not evaluated; what it adds is missing from the import.")
                    }
                    idx = skipBlock(cmds, idx, cmd.name)
                }
                "if" -> {
                    if (!active()) {
                        frames += Frame(false, true, false)
                    } else {
                        val v = condition(cmd)
                        frames += Frame(true, v, v)
                    }
                }
                "elseif" -> frames.lastOrNull()?.let { f ->
                    if (!f.parentActive || f.taken) {
                        f.active = false
                    } else {
                        val v = condition(cmd)
                        f.active = v
                        if (v) f.taken = true
                    }
                }
                "else" -> frames.lastOrNull()?.let { f ->
                    f.active = f.parentActive && !f.taken
                    f.taken = true
                }
                "endif" -> if (frames.isNotEmpty()) frames.removeAt(frames.lastIndex)
                else -> if (active()) command(cmd, dir, depth)
            }
        }
        if (savedDir != null) vars["CMAKE_CURRENT_SOURCE_DIR"] = savedDir
        if (savedList != null) vars["CMAKE_CURRENT_LIST_DIR"] = savedList
    }

    private fun skipBlock(cmds: List<CMakeCommand>, from: Int, name: String): Int {
        var depth = 1
        var i = from
        while (i < cmds.size) {
            if (cmds[i].name == name) depth++
            if (cmds[i].name == "end$name") { depth--; if (depth == 0) return i + 1 }
            i++
        }
        return cmds.size
    }

    private fun command(cmd: CMakeCommand, dir: Path, depth: Int) {
        val a = args(cmd)
        when (cmd.name) {
            "project" -> a.firstOrNull()?.let {
                vars["PROJECT_NAME"] = it
                if (firstProject) vars["CMAKE_PROJECT_NAME"] = it
                firstProject = false
                vars["PROJECT_SOURCE_DIR"] = dir.toString()
            }
            "set" -> set(cmd)
            "unset" -> a.firstOrNull()?.let { vars.remove(it) }
            "option" -> if (a.isNotEmpty() && a[0] !in vars) vars[a[0]] = a.getOrNull(2) ?: "OFF"
            "list" -> list(a)
            "file" -> file(a, dir)
            "find_library" -> findLibrary(a)
            "include_directories" -> a.filter { it !in visibility }.forEach { model.includes += path(it, dir) }
            "add_definitions" -> a.forEach { d ->
                if (d.startsWith("-D")) {
                    model.defines += d.substring(2)
                } else {
                    model.options += d
                }
            }
            "add_compile_definitions" -> model.defines += a
            "add_compile_options" -> model.options += a.filterNot { it.contains("\$<") }
            "add_library" -> addLibrary(a, dir)
            "add_executable" -> a.firstOrNull()?.let {
                model.warn("Executable '$it' is not built: an Android app loads shared libraries, not programs.")
            }
            "target_sources" -> target(a.firstOrNull())?.sources?.addAll(a.drop(1).filter { it !in visibility }.map { path(it, dir) })
            "target_include_directories" -> target(a.firstOrNull())?.includes?.addAll(
                a.drop(1).filter { it !in visibility }.map { path(it, dir) })
            "target_compile_definitions" -> target(a.firstOrNull())?.defines?.addAll(
                a.drop(1).filter { it !in visibility && !it.contains("\$<") }.map { it.removePrefix("-D") })
            "target_compile_options" -> target(a.firstOrNull())?.options?.addAll(
                a.drop(1).filter { it !in visibility && !it.contains("\$<") })
            "target_compile_features" -> target(a.firstOrNull())?.let { t ->
                a.drop(1).forEach { f ->
                    Regex("cxx_std_(\\d+)").matchEntire(f)?.let { t.cppStandard = "c++" + it.groupValues[1] }
                    Regex("c_std_(\\d+)").matchEntire(f)?.let { t.cStandard = "c" + it.groupValues[1] }
                }
            }
            "target_link_libraries" -> targetLink(a)
            "set_target_properties" -> setTargetProperties(a)
            "add_subdirectory" -> subdirectory(a, dir, depth)
            "include" -> include(a, dir, depth)
            else -> Unit // message, install, enable_language, add_custom_*, ... do not change what is built
        }
    }

    private fun set(cmd: CMakeCommand) {
        if (cmd.args.isEmpty()) return
        val name = expand(cmd.args[0].text)
        if (name.startsWith("ENV{")) return
        val values = ArrayList<String>()
        var sawQuoted = false
        var i = 1
        while (i < cmd.args.size) {
            val raw = cmd.args[i]
            if (!raw.quoted && (raw.text == "CACHE" || raw.text == "PARENT_SCOPE")) break
            val e = expand(raw.text)
            if (raw.quoted) { sawQuoted = true; values += e } else values += e.split(';').filter { it.isNotEmpty() }
            i++
        }
        if (values.isEmpty() && !sawQuoted) vars.remove(name) else vars[name] = values.joinToString(";")
    }

    private fun list(a: List<String>) {
        if (a.size < 2) return
        val name = a[1]
        val current = vars[name]?.split(';')?.filter { it.isNotEmpty() }.orEmpty()
        when (a[0]) {
            "APPEND" -> vars[name] = (current + a.drop(2)).joinToString(";")
            "PREPEND" -> vars[name] = (a.drop(2) + current).joinToString(";")
            "REMOVE_ITEM" -> vars[name] = current.filter { it !in a.drop(2) }.joinToString(";")
            "REMOVE_DUPLICATES" -> vars[name] = current.distinct().joinToString(";")
        }
    }

    private fun file(a: List<String>, dir: Path) {
        val mode = a.firstOrNull() ?: return
        if ((mode != "GLOB" && mode != "GLOB_RECURSE") || a.size < 2) return
        val recursive = mode == "GLOB_RECURSE"
        val out = a[1]
        var relativeTo: Path? = null
        val patterns = ArrayList<String>()
        var i = 2
        while (i < a.size) {
            when (a[i]) {
                "RELATIVE" -> { relativeTo = a.getOrNull(i + 1)?.let { path(it, dir) }; i++ }
                "LIST_DIRECTORIES" -> i++
                "CONFIGURE_DEPENDS" -> Unit
                else -> patterns += a[i]
            }
            i++
        }
        val found = LinkedHashSet<String>()
        for (pattern in patterns) {
            val abs = path(pattern, dir).toString().replace('\\', '/')
            val firstWild = abs.indexOfFirst { it == '*' || it == '?' }
            val base = if (firstWild < 0) abs else abs.substring(0, abs.lastIndexOf('/', firstWild).coerceAtLeast(0))
            val regex = globRegex(abs, recursive)
            for (f in fs.walk(Paths.get(base.ifEmpty { "/" }))) {
                val s = f.toString().replace('\\', '/')
                if (regex.matches(s)) found += if (relativeTo != null) relativeTo.relativize(f).toString() else s
            }
        }
        vars[out] = found.sorted().joinToString(";")
    }

    private fun globRegex(pattern: String, recursive: Boolean): Regex {
        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> { sb.append(".*"); i++ }
                c == '*' -> sb.append(if (recursive) ".*" else "[^/]*")
                c == '?' -> sb.append("[^/]")
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(sb.toString())
    }

    private fun findLibrary(a: List<String>) {
        if (a.size < 2) return
        val skip = setOf("NAMES", "PATHS", "HINTS", "REQUIRED", "NO_CMAKE_FIND_ROOT_PATH", "PATH_SUFFIXES", "DOC")
        val name = a.drop(1).firstOrNull { it !in skip && !it.startsWith("\${") } ?: return
        vars[a[0]] = name // a system library is linked by name: `-l<name>`
    }

    private fun addLibrary(a: List<String>, dir: Path) {
        val name = a.firstOrNull() ?: return
        var type = "STATIC"
        val sources = ArrayList<Path>()
        var imported = false
        for (arg in a.drop(1)) when (arg) {
            "STATIC", "SHARED", "MODULE", "OBJECT", "INTERFACE" -> type = arg
            "IMPORTED" -> imported = true
            "ALIAS" -> return
            "EXCLUDE_FROM_ALL", "GLOBAL" -> Unit
            else -> if (!arg.contains("\$<")) sources += path(arg, dir)
        }
        val t = NativeTarget(name, if (imported) "IMPORTED" else type)
        t.sources += sources
        model.targets[name] = t
    }

    private fun target(name: String?): NativeTarget? {
        if (name == null) return null
        return model.targets[name] ?: run { model.warn("'$name' is not a target defined by this script."); null }
    }

    private fun targetLink(a: List<String>) {
        val t = target(a.firstOrNull()) ?: return
        var i = 1
        while (i < a.size) {
            val item = a[i]
            when {
                item in visibility -> Unit
                item == "optimized" || item == "general" -> Unit
                item == "debug" -> i++ // the debug-only library: this build is the release one
                else -> t.links += item
            }
            i++
        }
    }

    private fun setTargetProperties(a: List<String>) {
        val p = a.indexOf("PROPERTIES")
        if (p < 1) return
        val targets = a.subList(0, p).mapNotNull { model.targets[it] }
        var i = p + 1
        while (i + 1 < a.size) {
            val key = a[i]
            val value = a[i + 1]
            for (t in targets) when (key) {
                "CXX_STANDARD" -> t.cppStandard = "c++$value"
                "C_STANDARD" -> t.cStandard = "c$value"
                "OUTPUT_NAME" -> t.outputName = value
            }
            i += 2
        }
    }

    private fun subdirectory(a: List<String>, dir: Path, depth: Int) {
        val sub = a.firstOrNull() ?: return
        if (depth >= 8) return
        val d = path(sub, dir)
        val file = d.resolve("CMakeLists.txt")
        val text = fs.read(file)
        if (text == null) { model.warn("add_subdirectory($sub): ${file.fileName} not found."); return }
        execute(CMakeParser.parse(text), d, depth + 1)
    }

    private fun include(a: List<String>, dir: Path, depth: Int) {
        val f = a.firstOrNull() ?: return
        if (!f.endsWith(".cmake") || depth >= 8) return // modules such as GNUInstallDirs change nothing here
        val file = path(f, dir)
        val text = fs.read(file) ?: return
        execute(CMakeParser.parse(text), file.parent ?: dir, depth + 1)
    }

    // ----------------------------------------------------------------------- conditions

    /**
     * The value of an `if`/`elseif`. A condition this evaluator cannot decide (parentheses, MATCHES, version
     * comparisons) takes the FIRST branch and says so: the alternative of guessing silently is a different
     * build with no sign of it.
     */
    private fun condition(cmd: CMakeCommand): Boolean {
        val result = try {
            Cond(cmd.args).or()
        } catch (e: IndexOutOfBoundsException) {
            null
        }
        if (result == null) {
            model.warn("line ${cmd.line}: could not evaluate if(${cmd.args.joinToString(" ") { it.text }}); the first branch was applied.")
        }
        return result ?: true
    }

    private val binary = setOf(
        "STREQUAL", "EQUAL", "LESS", "GREATER", "LESS_EQUAL", "GREATER_EQUAL", "MATCHES", "IN_LIST",
        "VERSION_LESS", "VERSION_GREATER", "VERSION_EQUAL", "VERSION_LESS_EQUAL", "VERSION_GREATER_EQUAL",
        "STRLESS", "STRGREATER",
    )

    private inner class Cond(private val t: List<CMakeArg>) {
        private var p = 0
        private val unsupported = t.any { it.text == "(" || it.text == ")" }

        fun or(): Boolean? {
            if (unsupported) return null
            var l = and()
            while (p < t.size && t[p].text == "OR") { p++; val r = and(); l = kor(l, r) }
            return l
        }

        private fun and(): Boolean? {
            var l = unary()
            while (p < t.size && t[p].text == "AND") { p++; val r = unary(); l = kand(l, r) }
            return l
        }

        private fun unary(): Boolean? {
            if (p >= t.size) return null
            val a = t[p]
            if (!a.quoted && a.text == "NOT") { p++; return unary()?.not() }
            if (!a.quoted && a.text == "DEFINED" && p + 1 < t.size) { val n = t[p + 1].text; p += 2; return n in vars }
            if (!a.quoted && a.text == "EXISTS" && p + 1 < t.size) {
                val f = t[p + 1].text; p += 2
                return fs.isFile(path(expand(f), rootDir ?: Paths.get("/")))
            }
            p++
            if (p < t.size && !t[p].quoted && t[p].text in binary) {
                val op = t[p].text
                p++
                if (p >= t.size) return null
                val b = t[p]
                p++
                return compare(op, a, b)
            }
            return truthy(a)
        }

        private fun deref(a: CMakeArg): String = if (!a.quoted && a.text in vars) vars.getValue(a.text) else a.text

        private fun compare(op: String, a: CMakeArg, b: CMakeArg): Boolean? {
            val x = deref(a)
            val y = deref(b)
            return when (op) {
                "STREQUAL" -> x == y
                "EQUAL", "LESS", "GREATER", "LESS_EQUAL", "GREATER_EQUAL" -> {
                    val dx = x.toDoubleOrNull() ?: return null
                    val dy = y.toDoubleOrNull() ?: return null
                    when (op) {
                        "EQUAL" -> dx == dy
                        "LESS" -> dx < dy
                        "GREATER" -> dx > dy
                        "LESS_EQUAL" -> dx <= dy
                        else -> dx >= dy
                    }
                }
                else -> null
            }
        }

        private fun truthy(a: CMakeArg): Boolean {
            if (!a.quoted) {
                vars[a.text]?.let { return truthyValue(it) }
                val u = a.text.uppercase()
                if (u in trueConstants) return true
                if (u in falseConstants) return false
                a.text.toDoubleOrNull()?.let { return it != 0.0 }
                return false // a name nothing defined
            }
            return truthyValue(a.text)
        }

        private fun truthyValue(s: String): Boolean {
            val u = s.uppercase()
            if (u in falseConstants || u.endsWith("-NOTFOUND")) return false
            return s.toDoubleOrNull()?.let { it != 0.0 } ?: true
        }

        private fun kand(a: Boolean?, b: Boolean?): Boolean? =
            if (a == false || b == false) false else if (a == null || b == null) null else true

        private fun kor(a: Boolean?, b: Boolean?): Boolean? =
            if (a == true || b == true) true else if (a == null || b == null) null else false
    }

    // ----------------------------------------------------------------------- values

    private fun args(cmd: CMakeCommand): List<String> {
        val out = ArrayList<String>()
        for (a in cmd.args) {
            if (a.text == "(" || a.text == ")") continue
            val e = expand(a.text)
            if (a.quoted) out += e else if (e.isNotEmpty()) out += e.split(';').filter { it.isNotEmpty() }
        }
        return out
    }

    /** `${NAME}` references, innermost first; an undefined name is the empty string, as in CMake. */
    private fun expand(s: String): String {
        var cur = s
        var guard = 0
        while (guard++ < 32) {
            val start = cur.lastIndexOf("\${")
            if (start < 0) break
            val end = cur.indexOf('}', start)
            if (end < 0) break
            cur = cur.substring(0, start) + (vars[cur.substring(start + 2, end)] ?: "") + cur.substring(end + 1)
        }
        return cur
    }

    private fun path(s: String, dir: Path): Path {
        val p = Paths.get(s)
        return (if (p.isAbsolute) p else dir.resolve(p)).normalize()
    }

    private fun splitFlags(s: String): List<String> = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
}

// ---------------------------------------------------------------------------------------------------
// ndk-build
// ---------------------------------------------------------------------------------------------------

internal class AndroidMkReader(private val fs: ImportFs, private val model: NativeModel) {

    private val vars = HashMap<String, String>()
    private val assignment = Regex("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(:=|\\+=|\\?=|=)\\s*(.*)$")

    fun read(file: Path, text: String) {
        val dir = file.parent
        vars["LOCAL_PATH"] = dir.toString()
        // Application.mk, next to it, carries the STL and the app-wide flags.
        fs.read(dir.resolve("Application.mk"))?.let { application(it) }

        for (line in logicalLines(text)) {
            val l = line.substringBefore('#').trim()
            if (l.isEmpty()) continue
            if (l.startsWith("include ") || l.startsWith("-include ")) {
                include(l.substringAfter("include").trim(), dir)
                continue
            }
            val m = assignment.matchEntire(l) ?: continue
            val key = m.groupValues[1]
            val value = expand(m.groupValues[3].trim(), dir)
            vars[key] = when (m.groupValues[2]) {
                "+=" -> (vars[key]?.let { "$it " } ?: "") + value
                "?=" -> vars[key] ?: value
                else -> value
            }
        }
    }

    private fun application(text: String) {
        for (line in logicalLines(text)) {
            val m = assignment.matchEntire(line.substringBefore('#').trim()) ?: continue
            val v = m.groupValues[3].trim()
            when (m.groupValues[1]) {
                "APP_STL" -> model.stl = when {
                    v.startsWith("c++_shared") -> "c++_shared"
                    v.startsWith("c++_static") -> "c++_static"
                    v == "none" -> "none"
                    else -> { model.warn("APP_STL=$v is no longer supported by the NDK; c++_static was used."); "c++_static" }
                }
                "APP_CPPFLAGS", "APP_CFLAGS", "APP_CXXFLAGS" -> model.options += v.split(Regex("\\s+")).filter { it.isNotEmpty() }
                "APP_PLATFORM" -> Regex("android-(\\d+)").find(v)?.groupValues?.get(1)?.toIntOrNull()?.let { model.minSdk = it }
            }
        }
    }

    private fun include(what: String, dir: Path) {
        val kind = what.replace(" ", "")
        val type = when {
            kind.contains("BUILD_SHARED_LIBRARY") -> "SHARED"
            kind.contains("BUILD_STATIC_LIBRARY") -> "STATIC"
            kind.contains("BUILD_EXECUTABLE") -> { model.warn("An ndk-build executable module is not built."); reset(); return }
            kind.contains("CLEAR_VARS") -> { reset(); return }
            else -> return // PREBUILT_*, other makefiles
        }
        val name = vars["LOCAL_MODULE"]?.trim() ?: return
        val t = NativeTarget(name, type)
        val local = Paths.get(vars["LOCAL_PATH"] ?: dir.toString())
        for (s in words(vars["LOCAL_SRC_FILES"])) {
            t.sources += (if (Paths.get(s).isAbsolute) Paths.get(s) else local.resolve(s)).normalize()
        }
        for (i in words(vars["LOCAL_C_INCLUDES"])) {
            t.includes += (if (Paths.get(i).isAbsolute) Paths.get(i) else local.resolve(i)).normalize()
        }
        t.options += words(vars["LOCAL_CFLAGS"]) + words(vars["LOCAL_CPPFLAGS"]) + words(vars["LOCAL_CXXFLAGS"])
        t.links += words(vars["LOCAL_LDLIBS"])
        val statics = words(vars["LOCAL_STATIC_LIBRARIES"]) + words(vars["LOCAL_WHOLE_STATIC_LIBRARIES"])
        t.links += statics.map { if (it == "android_native_app_glue") "native_app_glue" else it }
        if (words(vars["LOCAL_SHARED_LIBRARIES"]).isNotEmpty()) {
            model.warn("LOCAL_SHARED_LIBRARIES of '$name' is not linked: ${words(vars["LOCAL_SHARED_LIBRARIES"]).joinToString()}.")
        }
        model.targets[name] = t
        reset()
    }

    private fun reset() {
        vars.keys.filter { it.startsWith("LOCAL_") && it != "LOCAL_PATH" }.forEach { vars.remove(it) }
    }

    private fun words(s: String?): List<String> = s.orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** `$(VAR)`, `$(call my-dir)` and `$(wildcard ...)`; anything else expands to nothing. */
    private fun expand(s: String, dir: Path): String {
        var cur = s
        var guard = 0
        while (guard++ < 32) {
            val start = cur.lastIndexOf("$(")
            if (start < 0) break
            val end = cur.indexOf(')', start)
            if (end < 0) break
            val inner = cur.substring(start + 2, end).trim()
            val value = when {
                inner == "call my-dir" -> dir.toString()
                inner.startsWith("wildcard ") -> wildcard(inner.removePrefix("wildcard ").trim(), dir)
                inner.startsWith("call import-module") -> "" // android/native_app_glue is handled by the toolchain
                else -> vars[inner] ?: ""
            }
            cur = cur.substring(0, start) + value + cur.substring(end + 1)
        }
        return cur
    }

    private fun wildcard(patterns: String, dir: Path): String {
        val out = ArrayList<String>()
        for (p in patterns.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val abs = (if (Paths.get(p).isAbsolute) Paths.get(p) else dir.resolve(p)).normalize().toString().replace('\\', '/')
            val slash = abs.lastIndexOf('/')
            val base = abs.substring(0, slash.coerceAtLeast(0)).ifEmpty { "/" }
            val regex = Regex(abs.split("*").joinToString("[^/]*") { Regex.escape(it) })
            fs.walk(Paths.get(base)).map { it.toString().replace('\\', '/') }
                .filter { regex.matches(it) && it.substringBeforeLast('/') == base }
                .sorted().forEach { out += it }
        }
        return out.joinToString(" ")
    }

    private fun logicalLines(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            if (line.endsWith("\\")) {
                cur.append(line.dropLast(1)).append(' ')
            } else {
                cur.append(line)
                out += cur.toString()
                cur.clear()
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }
}
