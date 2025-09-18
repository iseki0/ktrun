#ifndef LINUX_SPAWN_HELPER_REQUIRE_NOT_NULL_H
#define LINUX_SPAWN_HELPER_REQUIRE_NOT_NULL_H
#include <stdlib.h>

#ifndef __FILE_NAME__
#define __FILE_NAME__ "__FILE_NAME__"
#endif

#include <unistd.h>
#define REQUIRE_NOT_NULL(expr) ({ \
    __typeof__(expr) v = (expr);\
    if(v == NULL) { space_iseki_spawnhelper_doPanic(#expr, " == NULL", __FILE_NAME__, __LINE__); _exit(1);}\
    (v); \
})
#define REQUIRE_NULL(expr) if((expr) != NULL) { space_iseki_spawnhelper_doPanic(#expr, " != NULL", __FILE_NAME__, __LINE__); _exit(1);}

#ifdef __cplusplus
extern "C" {


#endif

static void doPanic_abort() {
    abort();
}

static void doPanic_writeString(const char *msg) {
    size_t len = 0;
    while (msg[len] != '\0') {
        len++;
    }
    while (len > 0) {
        const size_t n = write(STDERR_FILENO, msg, len);
        if (n < 1) doPanic_abort();
        len -= n;
        msg += n;
    }
}

static void space_iseki_spawnhelper_doPanic(const char *const msg, const char *const msg2, const char *const file,
                                     const int line) {
    doPanic_writeString("Panic: ");
    doPanic_writeString(msg);
    doPanic_writeString(msg2);
    doPanic_writeString(" at ");
    doPanic_writeString(file);
    doPanic_writeString(":");
    // write line number
    unsigned int n = line;
    char buf[20];
    buf[sizeof(buf) - 1] = '\0';
    buf[sizeof(buf) - 2] = '\n';
    char *p = buf + sizeof(buf) - 3;
    do {
        const int digit = n % 10;
        n /= 10;
        *p-- = '0' + digit;
        if (p < buf) {
            // unreachable
            doPanic_abort();
        }
    } while (n != 0);
    doPanic_writeString(p);
    doPanic_abort();
}

#ifdef __cplusplus
}
#endif


#endif //LINUX_SPAWN_HELPER_REQUIRE_NOT_NULL_H
