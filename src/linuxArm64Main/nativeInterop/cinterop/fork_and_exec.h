#include <stdbool.h>

int do_fork_and_exec(
    int sub_stdin_fd, bool use_sub_stdin,
    int sub_stdout_fd, bool use_sub_stdout,
    int sub_stderr_fd, bool use_sub_stderr,
    const char *working_dir,
    const char *path,
    char *const argv[],
    char *const envp[],
    int exec_error_pipe,
    char **err_step);

int pipe2(int pipefd[2], int flags);
