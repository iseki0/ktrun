#ifndef LINUX_SPAWN_HELPER_SPAWN_HELPER_H
#define LINUX_SPAWN_HELPER_SPAWN_HELPER_H
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {



#endif

int pipe2(int pipefd[2], int flags);

#define initHelper space_iseki_spawnhelper_initHelper
int initHelper();

#define HelperStartResult space_iseki_spawnhelper_HelperStartResult

struct HelperStartResult {
    int commFd;
    int childErrno;
    int helperPid;
};

#define startHelper space_iseki_spawnhelper_startHelper
struct HelperStartResult startHelper();

#define sendSpawnRequest space_iseki_spawnhelper_sendSpawnRequest
int sendSpawnRequest(int helperFd, char *debugName, char *file, char **argv, char **envp, char *cwd, int stdinFd,
                     int stdoutFd, int stderrFd, bool *chdirFailed);


#ifdef __cplusplus
}
#endif

#endif //LINUX_SPAWN_HELPER_SPAWN_HELPER_H
