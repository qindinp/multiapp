#pragma once
#include "common.h"

// JNI string conversion
std::string jstring_to_string(JNIEnv* env, jstring jstr);

// JNI field access helpers
jobject get_object_field(JNIEnv* env, jobject obj, const char* field_name, const char* field_sig);
jint get_int_field(JNIEnv* env, jobject obj, const char* field_name);
