#ifndef LINUX_SPAWN_HELPER_SPAWN_HELPER_H
#define LINUX_SPAWN_HELPER_SPAWN_HELPER_H
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {



#endif

int pipe2(int pipefd[2], int flags);

extern const unsigned char _binary_linux_spawn_helper_bin_start[];
extern const unsigned char _binary_linux_spawn_helper_bin_end[];

#define sendSpawnRequest space_iseki_spawnhelper_sendSpawnRequest
int sendSpawnRequest(int helperFd, char *debugName, char *file, char **argv, char **envp, char *cwd, int stdinFd,
                     int stdoutFd, int stderrFd, bool *chdirFailed);

#ifdef SPAWN_HELPER_TESTING
int spawnHelperTest_createAnonymousShmFd(const char *prefix, bool executable);
int spawnHelperTest_createSharedPayloadMemfdFd(const char *debugName);
int spawnHelperTest_createSharedPayloadShmFd(const char *debugName);
#endif

#ifdef __cplusplus
}
#endif

#endif //LINUX_SPAWN_HELPER_SPAWN_HELPER_H
