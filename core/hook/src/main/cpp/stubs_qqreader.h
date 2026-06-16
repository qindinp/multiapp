#pragma once
#include "common.h"

// QQ Reader StubApp interface methods
void stub_interface5(JNIEnv* env, jobject thiz, jobject app);
void stub_interface11(JNIEnv* env, jobject thiz, jint code);
jboolean stub_interface20(JNIEnv* env, jobject thiz);
void stub_interface21(JNIEnv* env, jobject thiz, jobject app);

// Fock encryption methods
jint stub_fock_it(JNIEnv* env, jobject thiz, jbyteArray data, jint len);
void stub_fock_ak(JNIEnv* env, jobject thiz, jbyteArray data, jint len, jbyteArray key);
jstring stub_fock_sn(JNIEnv* env, jobject thiz, jbyteArray data, jint len);
jstring stub_fock_urk(JNIEnv* env, jobject thiz);

// OnlineChapterDownloadTask stubs
void stub_online_run(JNIEnv* env, jobject thiz);
