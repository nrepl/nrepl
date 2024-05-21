// Native agent that nREPL uses for deeply buried JDK functionality.
// Currently it is used to bring back Thread.stop() that was lost in JDK20+.

#include <jvmti.h>
#include <stdio.h>

static jvmtiEnv* jvmti_env;

// https://docs.oracle.com/en/java/javase/21/docs/specs/jvmti.html#StopThread
JNIEXPORT void JNICALL Java_nrepl_JvmtiAgent_stopThread
(JNIEnv* env, jclass cls, jthread thread, jobject throwable) {
  jvmtiError err;
  jvmtiThreadInfo threadInfo;

  err = (*jvmti_env)->GetThreadInfo(jvmti_env, thread, &threadInfo);
  if (err != JVMTI_ERROR_NONE) {
    printf("Error getting thread info: %d\n", err);
    return;
  }

  printf("Stopping thread \"%s\" using JVMTI...\n", threadInfo.name);

  err = (*jvmti_env)->StopThread(jvmti_env, thread, throwable);
  if (err != JVMTI_ERROR_NONE) {
    printf("Error stopping thread: %d\n", err);
    return;
  }
}

JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* options, void* _) {
  printf("nREPL native agent loaded\n");

  // Initialize JVMTI environment.
  jint res = (*vm)->GetEnv(vm, (void**)&jvmti_env, JVMTI_VERSION_1_2);
  if (res != JNI_OK) {
    fprintf(stderr, "Failed to get JVMTI environment\n");
    return JNI_ERR;
  }

  // Request capabilities for StopThread
  jvmtiCapabilities capabilities = {0};
  capabilities.can_signal_thread = 1;
  (*jvmti_env)->AddCapabilities(jvmti_env, &capabilities);

  return JNI_OK;
}
