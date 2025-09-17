#ifndef _SPAWN_HELPER_UTIL_H
#define _SPAWN_HELPER_UTIL_H 1

#include <fcntl.h>
#include <sys/epoll.h>
#include <string.h>
#include <stdlib.h>

#define PASSING_ERR(expr) ({typeof(expr) _e = (expr); if(_e == -1) return _e;})

#define MMAP_MUST_OK(expr) ({ \
    __typeof__(expr) __val = (expr); \
    if (__val == MAP_FAILED) { \
        perror(#expr); \
        exit(1); \
    } \
    __val; \
})

#define MUST_OK(expr)                          \
({                                                    \
    __typeof__(expr) __val = (expr);                  \
    if (__val == -1) {                                \
        perror(#expr);                                \
        exit(1);                                      \
    }                                                 \
    __val;                                            \
})

#define SWAP(a, b) { __typeof__(a) __tmp = (a); (a) = (b); (b) = __tmp;}

#define ON_ERR_GOTO_W(expr, target)       \
({                                      \
    __typeof__(expr) __val = (expr);    \
    if (__val == -1) {                  \
        errWhere = #expr;               \
        goto target;                    \
    }                                   \
    __val;                              \
})

#define ON_ERR_GOTO(expr, target)       \
({                                      \
    __typeof__(expr) __val = (expr);    \
    if (__val == -1) {                  \
        goto target;                    \
    }                                   \
    __val;                              \
})


#define COUNT_STRING_ARRAY(arr)                     \
({                                                  \
    size_t count = 0;                               \
    for(const char *p = *arr; p!=NULL; p++) count++;  \
    count;                                          \
})

#define fullWriteOrExit space_iseki_spawnhelper_fullWriteOrExit
void fullWriteOrExit(int fd, const void *buf, size_t count);

#endif
