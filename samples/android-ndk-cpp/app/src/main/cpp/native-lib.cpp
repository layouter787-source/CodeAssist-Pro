#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_dev_layouter_codeassist_ndksample_MainActivity_nativeGreeting(
    JNIEnv* env,
    jobject /* thiz */) {
    const std::string message = "C++17 + Android NDK + JNI is working!";
    return env->NewStringUTF(message.c_str());
}
