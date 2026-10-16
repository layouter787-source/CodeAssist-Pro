# Android NDK: C and C++ support

CodeAssist currently provides an on-device Android/Java build pipeline. Native Android projects additionally require an NDK toolchain, CMake (or ndk-build), ABI-aware native compilation, JNI integration, and packaging of shared libraries. Those are separate from Java/Kotlin compilation; installing an NDK in the app alone does not make native builds work.

## What this change adds

- A standalone Android NDK sample under `samples/android-ndk-cpp/` using Kotlin + JNI + CMake + C++17.
- A reproducible GitHub Actions job that compiles and packages the sample with Android Gradle Plugin, SDK, NDK, and CMake.
- A documented compatibility checklist for integrating native builds into CodeAssist.

## Build the sample

Open `samples/android-ndk-cpp/` as an Android Gradle project in an IDE that supports the Android SDK/NDK, or run:

```sh
gradle -p samples/android-ndk-cpp :app:assembleDebug
```

Required components: JDK 17, Android SDK Platform 35, Android build-tools 35.0.0, NDK 27.2.12479018, and CMake 3.22.1. The sample targets `arm64-v8a` and `x86_64`; add other ABIs only after testing device storage and build time.

## Required CodeAssist implementation work

A complete *on-device IDE* NDK implementation must do more than build this sample in CI. The build engine must:

1. Detect `externalNativeBuild` and parse `externalNativeBuild.cmake.path`, `arguments`, `cFlags`, `cppFlags`, and `abiFilters`.
2. Provision a compatible NDK/CMake toolchain and licenses, and verify downloaded packages.
3. Invoke CMake/Ninja using the NDK toolchain file, with a controlled environment and cancellation support.
4. Model native tasks and inputs/outputs in the incremental task graph; invalidate native outputs when CMake, source, headers, flags, ABI, or NDK version changes.
5. Package generated `.so` files into `lib/<abi>/` and merge them with APK/AAB packaging without duplicate native libraries.
6. Support JNI declarations and native symbols in editor navigation/completion, plus useful C/C++ diagnostics.
7. Keep all compilation and extraction inside app-accessible storage, respect Android process/memory limits, and surface toolchain errors to the user.

This commit is a verified starting point and sample, **not a claim that CodeAssist's existing on-device build engine can already compile arbitrary C/C++ projects**. The sample CI job verifies the conventional Android Gradle + NDK toolchain only.
