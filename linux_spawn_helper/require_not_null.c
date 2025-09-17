#include "require_not_null.h"

#include <stdlib.h>
#include <unistd.h>

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

void space_iseki_spawnhelper_doPanic(const char *const msg, const char *const msg2, const char *const file, const int line) {
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
