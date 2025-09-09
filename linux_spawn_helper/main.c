#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <linux/sched.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <sys/types.h>


#include "util.h"
#include "spawn_helper_comm.h"



// an eventfd, used to wakeup pidfd epoll thread, CLOEXEC
int WAKEUP_FD;

// epoll fd, CLOEXEC
int EPOLL_FD;

#define CLOSE_UNUSED(fd)  if(fd > 2) { MUST_OK(close(fd)); fd = 0; }

void sendCompletedMessage(const int errNo, const long pid, const char *const errWhere) {
    struct ResMeg res = {
        .kind = RES_COMPLETED,
        .completed = {
            .errNo = errNo,
            .pid = pid,
        },
    };
    if (errWhere != NULL) {
        strncpy(res.completed.errWhere, errWhere, sizeof(res.completed.errWhere) - 1);
    }
    struct msghdr msg = {0};
    struct iovec iov = {0};
    iov.iov_base = &res;
    iov.iov_len = sizeof(res);
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    MUST_OK(sendmsg(COMM_FD_UDS, &msg, 0));
}

void handleMessage(char *p, int childFd0, int childFd1, int childFd2, const int errFd) {
    struct SpawnProcessOption option = {0};
    int pidfd = 0;

    MUST_OK(SpawnProcessOption_parse(&option, p));

    const char *errWhere = NULL;

    // prepare clone
    struct clone_args ca = {0};
    ca.flags = CLONE_PIDFD;
    ca.pidfd = (__u64) &pidfd;

    const pid_t childPid = ON_ERR_GOTO(syscall(SYS_clone3, &ca, sizeof(ca)), err);
    if (childPid == 0) {
        // -------------------------- Child Process --------------------------
        ON_ERR_GOTO(dup2(childFd0, STDIN_FILENO), childErr);
        CLOSE_UNUSED(childFd0);
        ON_ERR_GOTO(dup2(childFd1, STDOUT_FILENO), childErr);
        CLOSE_UNUSED(childFd1);
        ON_ERR_GOTO(dup2(childFd2, STDERR_FILENO), childErr);
        CLOSE_UNUSED(childFd2);
        ON_ERR_GOTO(setCloexec(errFd), childErr);

        // Reset all signal handlers to be ignored, mimicking the original code's logic.
        // This prevents the child from inheriting unintended signal handlers.
        struct sigaction sa = {0};
        sa.sa_handler = SIG_IGN;
        for (int i = 1; i < NSIG; i++) {
            // SIGKILL and SIGSTOP cannot be caught or ignored; attempting to set
            // a handler for them will fail. We skip them to be explicit.
            if (i == SIGKILL || i == SIGSTOP) continue;
            // ignore sigaction error, not only for SIGKILL and SIGSTOP.
            // https://bugzilla.redhat.com/show_bug.cgi?id=53394
            sigaction(i, &sa, NULL);
        }

        sigset_t empty = {0};
        ON_ERR_GOTO(sigemptyset(&empty), childErr);
        ON_ERR_GOTO(sigprocmask(SIG_SETMASK, &empty, NULL), childErr);

        if (option.envpSet) {
            ON_ERR_GOTO(execvpe(option.file, option.argv, option.envp), childErr);
        } else {
            ON_ERR_GOTO(execvp(option.file, option.argv), childErr);
        }
        // Should not reach here
        errWhere = "unreachable";
    childErr:;
        // reporting error
        const int savedErrno = errno;
        fullWriteOrExit(errFd, &savedErrno, sizeof(savedErrno));
        fullWriteOrExit(errFd, errWhere, strlen(errWhere) + 1);
        _exit(1);
        // -------------------------- Child Process --------------------------
    }

    // Add pidfd to epoll
    MUST_OK(addToEpoll(EPOLL_FD, pidfd, childPid, EPOLLIN | EPOLLHUP | EPOLLERR));
    // Wakeup
    MUST_OK(write(WAKEUP_FD, "", 1));
    errno = 0;
err:;
    const int savedErrno = errno;
    // cleanup
    if (savedErrno != 0)
        CLOSE_UNUSED(pidfd);
    sendCompletedMessage(savedErrno, childPid, errWhere);
}

void recvMessage() {
    // dummy buffer, UDS required must have at least 1 byte
    char buf = 0;
    // message header
    struct msghdr msg = {0};
    // io vector
    struct iovec iov = {0};
    // control message buffer
    char cmsgBuf[REQ_CMSG_SIZE] = {0};
    iov.iov_base = &buf;
    iov.iov_len = sizeof(buf);
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsgBuf;
    msg.msg_controllen = sizeof(cmsgBuf);
    if (recvmsg(COMM_FD_UDS, &msg, MSG_CMSG_CLOEXEC) == -1) {
        const int e = errno;
        if (e == EINTR) {
            return;
        }
        perror("recvmsg");
        exit(1);
    }
    const struct cmsghdr *cmsghdr = CMSG_FIRSTHDR(&msg);
    if (cmsghdr == NULL || cmsghdr->cmsg_level != SOL_SOCKET || cmsghdr->cmsg_type != SCM_RIGHTS) {
        fprintf(stderr, "No SCM_RIGHTS in message\n");
        exit(1);
    }
    if (cmsghdr->cmsg_len != REQ_CMSG_SIZE) {
        fprintf(stderr, "Invalid control message length: %d\n", cmsghdr->cmsg_len);
        exit(1);
    }
    // fd receiving
    int fds[REQ_FD_LEN] = {0};
    memcpy(fds, CMSG_DATA(cmsghdr), sizeof(fds));
    for (int i = 0; i < sizeof(fds) / sizeof(fds[0]); i++) {
        if (fds[i] == 0) {
            fprintf(stderr, "Invalid fd in control message, index: %d\n", i);
            exit(1);
        }
    }
    const int memFd = fds[REQ_MEM_FD_IDX];
    const int errFd = fds[REQ_ERR_FD_IDX];
    const int childFd0 = fds[REQ_CHILD_FD0_IDX];
    const int childFd1 = fds[REQ_CHILD_FD1_IDX];
    const int childFd2 = fds[REQ_CHILD_FD2_IDX];
    // read stat
    struct stat st;
    MUST_OK(fstat(memFd, &st));
    // mmap
    void *p = MMAP_MUST_OK(mmap(NULL, st.st_size, PROT_READ, MAP_SHARED, memFd, 0));
    // handle message
    handleMessage(p, childFd0, childFd1, childFd2, errFd);
    // clean up
    MUST_OK(munmap(p, st.st_size));
    MUST_OK(close(memFd));
    MUST_OK(close(errFd));
    MUST_OK(close(childFd0));
    MUST_OK(close(childFd1));
    MUST_OK(close(childFd2));
}


int main() {
    // Ensure stdio ready
    MUST_OK(clearCloexec(STDIN_FILENO));
    MUST_OK(clearCloexec(STDOUT_FILENO));
    MUST_OK(clearCloexec(STDERR_FILENO));
    MUST_OK(clearNonBlock(STDIN_FILENO));
    MUST_OK(clearNonBlock(STDOUT_FILENO));
    MUST_OK(clearNonBlock(STDERR_FILENO));

    // Setup epoll
    EPOLL_FD = MUST_OK(epoll_create1(EPOLL_CLOEXEC));

    // Setup eventfd, used to wakeup epoll immediately
    WAKEUP_FD = MUST_OK(eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK));
    MUST_OK(addToEpoll(EPOLL_FD, WAKEUP_FD, 0, EPOLLIN));

    // Setup Comm UDS
    MUST_OK(setNonBlock(COMM_FD_UDS));
    MUST_OK(setCloexec(COMM_FD_UDS));
    MUST_OK(addToEpoll(EPOLL_FD, COMM_FD_UDS, 0, EPOLLIN|EPOLLHUP|EPOLLERR));

    // Wait events
    struct epoll_event events[16];
    while (1) {
        const int r = epoll_wait(EPOLL_FD, events, sizeof(events) / sizeof(events[0]), -1);
        if (r == -1) {
            if (errno == EINTR) {
                continue;
            }
            perror("epoll_wait");
        }
        for (int i = 0; i < r; i++) {
            const struct epoll_event ev = events[i];
            const int fd = EPOLL_DATA_FD(ev);
            if (fd == WAKEUP_FD) {
                char v = 0;
                MUST_OK(read(WAKEUP_FD, &v, sizeof(v)));
                continue;
            }
            if (fd == COMM_FD_UDS) {
                if (ev.events & (EPOLLHUP | EPOLLERR)) {
                    // COMM_FD_UDS closed, exit
                    exit(0);
                }
                recvMessage();
                continue;
            }
            // pidfd event
            if (ev.events & EPOLLERR) {
                const pid_t pid = EPOLL_DATA_PID(ev);
                fprintf(stderr, "pidfd error, pid: %d\n", pid);
                _exit(1);
            }
            if (ev.events & EPOLLHUP) {
                errno = 0;
                int wstatus = 0;
                waitpid(EPOLL_DATA_PID(ev), &wstatus, WNOHANG);
                if (errno == EINTR) {
                    continue;
                }
                if (errno != 0) {
                    perror("waitpid");
                    _exit(1);
                }
                struct ResMeg res = {
                    .kind = RES_EXITED,
                    .exited = {
                        .pid = EPOLL_DATA_PID(ev),
                        .exitCode = WIFEXITED(wstatus) ? WEXITSTATUS(wstatus) : -1,
                    },
                };
                struct msghdr msg = {0};
                struct iovec iov = {0};
                iov.iov_base = &res;
                iov.iov_len = sizeof(res);
                msg.msg_iov = &iov;
                msg.msg_iovlen = 1;
                MUST_OK(sendmsg(COMM_FD_UDS, &msg, 0));
                // clean up
                MUST_OK(epoll_ctl(EPOLL_FD, EPOLL_CTL_DEL, fd, NULL));
                free(ev.data.ptr);
            }
        }
    }
}

