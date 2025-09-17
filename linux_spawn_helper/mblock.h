#ifndef LINUX_SPAWN_HELPER_MBLOCK_H
#define LINUX_SPAWN_HELPER_MBLOCK_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define MBLOCK_NAME(name) space_iseki_spawnhelper_mblock_##name

#define MBlock_SizeOfCString MBLOCK_NAME(SizeOfCString)
size_t MBlock_SizeOfCString(const char *cstr);

#define MBlock_PutCString MBLOCK_NAME(PutCString)
void MBlock_PutCString(char *buf, const char *cstr, char **bufOut);

#define MBlock_GetCString MBLOCK_NAME(GetCString)
int MBlock_GetCString(const char *buf, char **cstrOut, char **bufOut);

#ifdef __cplusplus
}
#endif

#endif //LINUX_SPAWN_HELPER_MBLOCK_H
