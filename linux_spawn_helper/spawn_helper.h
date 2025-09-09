#ifndef LINUX_SPAWN_HELPER_SPAWN_HELPER_H
#define LINUX_SPAWN_HELPER_SPAWN_HELPER_H

#ifdef __cplusplus
extern "C" {



#endif

int pipe2(int pipefd[2], int flags);

int initHelper();

int startHelper(int *udsFd, int *helperPid);

int sendSpawnRequest(int helperFd, char *debugName, char *file, char **argv, char **envp, int envpSet, char *cwd,
                     int errFd, int stdinFd, int stdoutFd, int stderrFd);

#ifdef __cplusplus
}
#endif

#endif //LINUX_SPAWN_HELPER_SPAWN_HELPER_H
