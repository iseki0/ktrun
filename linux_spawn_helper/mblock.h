#ifndef LINUX_SPAWN_HELPER_MBLOCK_H
#define LINUX_SPAWN_HELPER_MBLOCK_H

#include <stddef.h>
#include "require_not_null.h"
#include <string.h>
#include <errno.h>

#ifdef __cplusplus
extern "C" {

#endif

#define MBLOCK_NAME(name) space_iseki_spawnhelper_mblock_##name

#define MBlock_SizeOfCString MBLOCK_NAME(SizeOfCString)

#define MBlock_PutCString MBLOCK_NAME(PutCString)

#define MBlock_GetCString MBLOCK_NAME(GetCString)

static void MBlock_PutCString(char *buf, const char *cstr, char **bufOut) {
    REQUIRE_NOT_NULL(cstr);
    REQUIRE_NOT_NULL(buf);
    const size_t len = strlen(cstr) + 1;
    memcpy(buf, cstr, len);
    if (bufOut != NULL) *bufOut += len;
}

static size_t MBlock_SizeOfCString(const char *cstr) {
    REQUIRE_NOT_NULL(cstr);
    return strlen(cstr) + 1;
}


static int MBlock_GetCString(const char *buf, char **cstrOut, char **bufOut) {
    REQUIRE_NOT_NULL(cstrOut);
    REQUIRE_NULL(*cstrOut);
    errno = 0;
    const size_t len = strlen(buf) + 1;
    *cstrOut = (char *) malloc(len);
    if (*cstrOut == NULL) {
        errno = ENOMEM;
        return -1;
    }
    memcpy(*cstrOut, buf, len);
    if (bufOut != NULL) *bufOut += len;
    return 0;
}


#ifdef __cplusplus
}
#endif

#endif //LINUX_SPAWN_HELPER_MBLOCK_H
