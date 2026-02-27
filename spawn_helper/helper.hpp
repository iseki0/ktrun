#ifndef  HELPER_HPP
#define  HELPER_HPP

#ifdef __cplusplus
extern "C" {
#else
#include <stdint.h>
#include <stdbool.h>
#endif

#define COMM_FD_UDS 6
#define COMM_STDIN 0
#define COMM_STDOUT 1
#define COMM_STDERR 2
#define COMM_CHILD_ERR_REPORT 3
#define COMM_STATUS_REPORT 4
#define COMM_REQ_MEMFD 5
#define COMM_FD_COUNT 6
#define CHILD_STEP_STDIN 1
#define CHILD_STEP_STDOUT 2
#define CHILD_STEP_STDERR 3
#define CHILD_STEP_CHDIR 4
#define CHILD_STEP_EXEC 5

struct HelperCommHeader {
    bool chdir;
    int32_t argc;
    int32_t envpc;
};

extern const unsigned char _binary_spawnhelper_process_start[];
extern const unsigned char _binary_spawnhelper_process_end[];

// int helper_launch_process(int socket, char *shmName, int childStdin, int childStdout, int childStderr, int childErrFd,
//                           int statusFd, char *file, char *chdir, char **argv, char **envp);
//
// int helper_launch_helper_process();

#ifdef __cplusplus
}
#endif

#endif  /* HELPER_HPP */
