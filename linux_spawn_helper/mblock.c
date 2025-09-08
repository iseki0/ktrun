#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "require_not_null.h"


#define REQUIRE_NOT_NULL(expr) if((expr) == NULL) { doPanic(#expr, " == NULL", __FILE__, __LINE__); _exit(1);}
#define REQUIRE_NULL(expr) if((expr) != NULL) { doPanic(#expr, " != NULL", __FILE__, __LINE__); _exit(1);}

size_t MBlock_SizeOfCString(const char *cstr) {
    REQUIRE_NOT_NULL(cstr);
    return strlen(cstr) + 1;
}

void MBlock_PutCString(char *buf, const char *cstr, char **bufOut) {
    REQUIRE_NOT_NULL(cstr);
    REQUIRE_NOT_NULL(buf);
    const size_t len = strlen(cstr) + 1;
    memcpy(buf, cstr, len);
    if (bufOut != NULL) *bufOut += len;
}


int MBlock_GetCString(const char *buf, char **cstrOut, char **bufOut) {
    REQUIRE_NOT_NULL(cstrOut);
    REQUIRE_NULL(*cstrOut);
    errno = 0;
    const size_t len = strlen(buf) + 1;
    *cstrOut = (char *)malloc(len);
    if (*cstrOut == NULL) {
        errno = ENOMEM;
        return -1;
    }
    memcpy(*cstrOut, buf, len);
    if (bufOut != NULL) *bufOut += len;
    return 0;
}

