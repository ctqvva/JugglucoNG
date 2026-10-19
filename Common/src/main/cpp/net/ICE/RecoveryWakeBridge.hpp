#pragma once

#include "RecoveryWakeLease.hpp"
#include "logs.hpp"

#ifdef __ANDROID__
#include <jni.h>
extern JNIEnv *getenv();
namespace {
std::mutex recoveryBridgeMutex;
jclass recoveryWakeClass{};
jmethodID recoveryWakeAcquire{}, recoveryWakeRelease{};
}

// Called on the Java configuration thread, using the application's class loader.
extern "C" void initializeCloneRecoveryWake(JNIEnv *env) {
    const std::lock_guard<std::mutex> lock(recoveryBridgeMutex);
    if (recoveryWakeClass) return;
    auto cls = env->FindClass("tk/glucodata/CloneRecoveryWake");
    if (cls) {
        recoveryWakeAcquire = env->GetStaticMethodID(cls, "acquire", "()J");
        if (!env->ExceptionCheck())
            recoveryWakeRelease = env->GetStaticMethodID(cls, "release", "(J)V");
        if (!env->ExceptionCheck() && recoveryWakeAcquire && recoveryWakeRelease)
            recoveryWakeClass = static_cast<jclass>(env->NewGlobalRef(cls));
        env->DeleteLocalRef(cls);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    LOGGER("ICE recovery wake: bridge ready=%d\n", recoveryWakeClass != nullptr);
}

uint64_t acquireCloneRecoveryWake() {
    const std::lock_guard<std::mutex> lock(recoveryBridgeMutex);
    auto *env = getenv();
    if (!env || !recoveryWakeClass) return 0;
    const auto token = env->CallStaticLongMethod(recoveryWakeClass, recoveryWakeAcquire);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return 0;
    }
    LOGGER("ICE recovery wake: acquire token=%lld\n", static_cast<long long>(token));
    return static_cast<uint64_t>(token);
}

void releaseCloneRecoveryWake(uint64_t token) {
    const std::lock_guard<std::mutex> lock(recoveryBridgeMutex);
    auto *env = getenv();
    if (!env || !recoveryWakeClass) return;
    env->CallStaticVoidMethod(recoveryWakeClass, recoveryWakeRelease, static_cast<jlong>(token));
    if (env->ExceptionCheck()) env->ExceptionClear();
    LOGGER("ICE recovery wake: release token=%llu\n", static_cast<unsigned long long>(token));
}
#else
uint64_t acquireCloneRecoveryWake() { return 0; }
void releaseCloneRecoveryWake(uint64_t) {}
#endif
