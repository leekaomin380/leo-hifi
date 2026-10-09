#ifndef HOSTMOCK_LOG_H
#define HOSTMOCK_LOG_H
#include <stdio.h>
extern int leo_log_count[6];
#define LEO_LOG_V 0
#define LEO_LOG_D 1
#define LEO_LOG_I 2
#define LEO_LOG_W 3
#define LEO_LOG_E 4
#define LEO__LOG(lvl,tag,...) do { leo_log_count[lvl]++; \
    fprintf(stderr, "[%s/%s] ", tag, #lvl); fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#ifndef LOG_TAG
#define LOG_TAG "leo"
#endif
#define ALOGV(...) LEO__LOG(LEO_LOG_V, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) LEO__LOG(LEO_LOG_D, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) LEO__LOG(LEO_LOG_I, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) LEO__LOG(LEO_LOG_W, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) LEO__LOG(LEO_LOG_E, LOG_TAG, __VA_ARGS__)
#endif
