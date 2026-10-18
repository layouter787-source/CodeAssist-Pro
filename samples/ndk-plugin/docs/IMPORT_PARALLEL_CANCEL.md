# Importing a CMake / ndk-build project, parallel builds and cancelling

Three additions to the NDK plugin. None of them adds CMake or ninja: the plugin still calls clang directly and
the `[ndk]` table is still the whole configuration.

## Import C/C++ Build

Right-click a module that has a `CMakeLists.txt` (in `src/main/cpp`, `src/main/jni`, `cpp`, `jni` or the module
root) or an `Android.mk`, and choose **Import C/C++ Build**. `NativeBuildImporter` reads the script and writes the
`[ndk]` table that builds the same library.

It evaluates the subset of CMake that Android projects actually use: `set`, `list`, `option`, `file(GLOB[_RECURSE])`,
`find_library`, `add_library`, `target_*`, `include_directories`, `add_definitions`, `add_subdirectory`, `include(*.cmake)`
and `if/elseif/else` over the platform variables (`ANDROID`, `CMAKE_SYSTEM_NAME`, ...). Static libraries the shared
library links are folded into its sources, `android_native_app_glue` becomes `nativeActivity = true`, and Gradle's
`ANDROID_STL`, `abiFilters` and `minSdk` are read from the module's `build.gradle(.kts)`.

It is not CMake. What it cannot express is reported, never guessed:

- a condition it cannot decide (parentheses, `MATCHES`, version comparisons) applies the first branch and warns;
- `foreach`, `while`, `function` and `macro` bodies are skipped (the first two with a warning);
- prebuilt libraries and imported targets (`Foo::Bar`) are not linked; the warning says how to add them by hand;
- the facet builds whole directories, so a source directory holding files the target does not list is reported;
- more than one shared library in a script: the first is imported (a module has one `[ndk]` library);
- ndk-build: `Android.mk` and `Application.mk` are read the same way; only `BUILD_SHARED_LIBRARY` and
  `BUILD_STATIC_LIBRARY` modules count.

The facet gained three optional keys for this, written to the table only when they say something:

| key | meaning |
|---|---|
| `includeDirs` | `-I` directories, relative to the module or absolute |
| `defines` | `-D` definitions, `NAME` or `NAME=value` |
| `jobs` | compiles to run at once; `0` (default) decides |

## Parallel compiles, and memory

Stale sources compile in parallel. `NdkResources.planJobs` takes the core count and holds it down by free memory
(`MemAvailable`; about 450 MB per `clang++` after a 400 MB reserve), never above 8. `CompileScheduler` runs only one
job at a time while memory is tight and fails with a clear message, rather than being killed, when even one job
would not fit after 30 s. Set `jobs` in `[ndk]` to override.

## Cancelling

`NdkToolchain.run` used to read a tool's output to its end before starting its clock, so the timeout only applied to a
process that had already finished and cancelling a build waited out the compile in progress. `NdkProcess` drains the
output on its own thread and polls: the process ends, the deadline passes, or the build is cancelled. Cancelling stops
every running compile and the link, **and the processes they started** (clang runs lld as a child), and removes the
partial object or library so nothing packages it.

The process tree is found through `Process.pid()` (API 33) or the private `pid` field; where neither is reachable the
driver alone is killed. That is the one place behaviour depends on the device.

## Not done here

- **Multiple ABIs.** The toolchain ships one backend (arm64). Listing more `abis` still only builds arm64 and warns.
  Fixing that is a rebuild of the LLVM toolchain with more targets and sysroots, not a plugin change.
- **A real CMake and ninja.** They could be cross-built for Android and shipped, but that is a large asset and a
  second build system to keep in step with this one; the importer covers the projects people actually bring.
- **Rename of a native method from Java/Kotlin** (already listed as not built in the README).
