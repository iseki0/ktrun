#ifndef LINUX_SPAWN_HELPER_MBLOCK_H
#define LINUX_SPAWN_HELPER_MBLOCK_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

size_t MBlock_SizeOfCString(const char *cstr);

void MBlock_PutCString(char *buf, const char *cstr, char **bufOut);

int MBlock_GetCString(const char *buf, char **cstrOut, char **bufOut);

#ifdef __cplusplus
}
#endif

#endif //LINUX_SPAWN_HELPER_MBLOCK_H
