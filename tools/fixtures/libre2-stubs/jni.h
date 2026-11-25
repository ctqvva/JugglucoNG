// Only what libre2_imp/backend.cpp names; the JNI entry points are not called in the test.
#pragma once
#include <cstdint>
using jint = int32_t; using jbyte = int8_t; using jboolean = uint8_t;
using jobject = void *; using jbyteArray = void *;
#define JNI_TRUE 1
#define JNI_FALSE 0
struct JNIEnv {
    jint GetArrayLength(jbyteArray) { return 0; }
    void GetByteArrayRegion(jbyteArray, jint, jint, jbyte *) {}
    void SetByteArrayRegion(jbyteArray, jint, jint, const jbyte *) {}
    jbyteArray NewByteArray(jint) { return nullptr; }
    bool ExceptionCheck() { return false; }
};
