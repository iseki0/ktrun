#ifndef LINUX_SPAWN_HELPER_SPAWN_HELPER_H
#define LINUX_SPAWN_HELPER_SPAWN_HELPER_H
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {



#endif

int pipe2(int pipefd[2], int flags);

int initHelper();

struct HelperStartResult {
    int commFd;
    int childErrno;
};

struct HelperStartResult startHelper();

int sendSpawnRequest(int helperFd, char *debugName, char *file, char **argv, char **envp,
                     int envpSet, char *cwd,
                     int errFd, int stdinFd, int stdoutFd, int stderrFd);


#ifdef __cplusplus
}
#endif

#endif //LINUX_SPAWN_HELPER_SPAWN_HELPER_H
