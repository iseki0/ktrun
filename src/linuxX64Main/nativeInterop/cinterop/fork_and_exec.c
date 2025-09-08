#include <unistd.h>
#include <sys/types.h>
#include <sys/syscall.h>
#include <signal.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <stdint.h>
#include <pthread.h>


struct clone_args {
  uint64_t flags;
  uint64_t pidfd;
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

/**
 * @brief Helper function for the child process to report an error and exit.
 *
 * This function is called in the child process if any setup step before exec fails
 * (e.g., dup2, chdir). It writes the error code to a pipe so the parent
 * process can know that the child failed to launch the new program.
 *
 * @param pipe_fd The write-end of the error reporting pipe.
 * @param error_code The value of `errno` from the failed system call.
 */
static void write_error_and_exit(int pipe_fd, int error_code, int location) {
    // Attempt to write the error code to the pipe. The parent will read this.
    // We can't do much if this write fails, as we are already in an error state.
    write(pipe_fd, &error_code, sizeof(error_code));

    write(pipe_fd, &location, sizeof(location));

    // Exit with the error code. While the parent primarily uses the pipe to
    // detect failure, this exit code could be useful for debugging.
    _exit(error_code);
}

/**
 * @brief Forks the process, sets up the child environment, and executes a new program.
 *
 * This function encapsulates the fork-exec pattern with robust error handling. It
 * handles redirecting standard I/O, changing the working directory, and safely

 * managing signal masks across the fork.
 *
 * @param sub_stdin_fd   File descriptor for the child's standard input.
 * @param use_sub_stdin  If true, redirect stdin to `sub_stdin_fd`.
 * @param sub_stdout_fd  File descriptor for the child's standard output.
 * @param use_sub_stdout If true, redirect stdout to `sub_stdout_fd`.
 * @param sub_stderr_fd  File descriptor for the child's standard stderr.
 * @param use_sub_stderr If true, redirect stderr to `sub_stderr_fd`.
 * @param working_dir    The working directory to set for the child process. (Can be NULL)
 * @param path           Path to the executable.
 * @param argv           Argument vector for the new program (NULL-terminated).
 * @param envp           Environment vector for the new program (NULL-terminated).
 * If NULL, the child inherits the parent's environment.
 * @param exec_error_pipe The write-end of a pipe for reporting exec errors.
 * @return On success, the PID of the child process. On failure, -1.
 */
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
    struct clone_args *ca)
{
    sigset_t new_mask, old_mask;
    pid_t pid;
    int fork_errno;

    // Block all signals to prevent signal handlers from running in the child
    // between fork() and exec(). This is a standard safety measure.
    if (sigfillset(&new_mask) == -1) {
        *err_step = "sigfillset";
        return -1;
    }
    if (pthread_sigmask(SIG_SETMASK, &new_mask, &old_mask) != 0) {
        *err_step = "pthread_sigmask";
        return -1;
    }

    pid = syscall(SYS_clone3, ca, sizeof(*ca));
    fork_errno = errno; // Save errno immediately, as other calls might change it.

    if (pid == 0) {
        // --- Child Process ---

        // Redirect stdin, stdout, and stderr as requested.
        if (use_sub_stdin && dup2(sub_stdin_fd, STDIN_FILENO) == -1) {
            write_error_and_exit(exec_error_pipe, errno, 0);
        }
        if (use_sub_stdout && dup2(sub_stdout_fd, STDOUT_FILENO) == -1) {
            write_error_and_exit(exec_error_pipe, errno, 0);
        }
        if (use_sub_stderr && dup2(sub_stderr_fd, STDERR_FILENO) == -1) {
            write_error_and_exit(exec_error_pipe, errno, 0);
        }

        // Change working directory if one was provided.
        if (working_dir != NULL && chdir(working_dir) == -1) {
            write_error_and_exit(exec_error_pipe, errno, 2);
        }

        // Reset all signal handlers to be ignored, mimicking the original code's logic.
        // This prevents the child from inheriting unintended signal handlers.
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_handler = SIG_IGN;
        for (int i = 1; i < NSIG; i++) {
            // SIGKILL and SIGSTOP cannot be caught or ignored; attempting to set
            // a handler for them will fail. We skip them to be explicit.
            if (i == SIGKILL || i == SIGSTOP) continue;
            // ignore sigaction error, not only for SIGKILL and SIGSTOP.
            // https://bugzilla.redhat.com/show_bug.cgi?id=53394
            sigaction(i, &sa, NULL);
        }

        // Restore the original signal mask before executing the new program.
        if (pthread_sigmask(SIG_SETMASK, &old_mask, NULL) != 0) {
            write_error_and_exit(exec_error_pipe, errno, 0);
        }

        // Execute the new program.
        if (envp == NULL) {
            execvp(path, argv);
        } else {
            execvpe(path, argv, envp);
        }

        // If execvp/execvpe returns, an error has occurred.
        // Report the error to the parent via the pipe and exit.
        write_error_and_exit(exec_error_pipe, errno, 1);
    }

    // --- Parent Process ---

    // Immediately restore the parent's original signal mask.
    if (pthread_sigmask(SIG_SETMASK, &old_mask, NULL) != 0) {
        // A failure here is critical and unrecoverable for the parent.
        perror("pthread_sigmask (parent)");
        abort();
        _exit(EXIT_FAILURE);
    }

    // Check if the fork() call itself failed.
    if (pid == -1) {
        errno = fork_errno;
        *err_step = "clone";
        return -1;
    }

    // Return the child's process ID to the caller.
    return pid;
}
