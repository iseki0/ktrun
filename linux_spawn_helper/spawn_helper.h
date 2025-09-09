#ifndef LINUX_SPAWN_HELPER_SPAWN_HELPER_H
#define LINUX_SPAWN_HELPER_SPAWN_HELPER_H

#ifdef __cplusplus
extern "C" {

#endif

int sendSpawnRequest(char *debugName, char *file, char **argv, char **envp, int envpSet, int errFd,
                     int stdinFd, int stdoutFd, int stderrFd);

#ifdef __cplusplus
}
#endif

#endif //LINUX_SPAWN_HELPER_SPAWN_HELPER_H
