#include <stdbool.h>
#include <stdint.h>
#include <sched.h>


struct clone_args {
  uint64_t flags;
  uint64_t *pidfd;
  uint64_t child_tid;
  uint64_t parent_tid;
  uint64_t exit_signal;
  uint64_t stack;
  uint64_t stack_size;
  uint64_t tls;
  uint64_t set_tid;
  uint64_t set_tid_size;
  uint64_t cgroup;
};

int do_fork_and_exec(
    int sub_stdin_fd, bool use_sub_stdin,
    int sub_stdout_fd, bool use_sub_stdout,
    int sub_stderr_fd, bool use_sub_stderr,
    const char *working_dir,
    const char *path,
    char *const argv[],
    char *const envp[],
    int exec_error_pipe,
    char **err_step,
    struct clone_args *ca);

int pipe2(int pipefd[2], int flags);


