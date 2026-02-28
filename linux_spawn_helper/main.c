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
#include <sys/signalfd.h>
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
// signalfd for SIGCHLD, CLOEXEC|NONBLOCK
int SIGCHLD_FD;

#define CLOSE_UNUSED(fd)  if(fd > 2) { MUST_OK(close(fd)); fd = 0; }

struct EpollData {
    int fd;
    pid_t pid;
};

#define EPOLL_DATA_FD(ev) (((struct EpollData *)(ev).data.ptr)->fd)
#define EPOLL_DATA_PID(ev) (((struct EpollData *)(ev).data.ptr)->pid)


int setNonBlock(const int fd) {
    const int flags = fcntl(fd, F_GETFL);
    if (flags == -1) {
        return -1;
    }
    if (flags & O_NONBLOCK) return 0;
    if (fcntl(fd, F_SETFL, flags | O_NONBLOCK) == -1) return -1;
    return 0;
}

static int clearNonBlock(const int fd) {
    const int flags = fcntl(fd, F_GETFL);
    if (flags == -1) {
        return -1;
    }
    if (!(flags & O_NONBLOCK)) return 0;
    if (fcntl(fd, F_SETFL, flags & ~O_NONBLOCK) == -1) return -1;
    return 0;
}

static int clearCloexec(const int fd) {
    const int flags = fcntl(fd, F_GETFD);
    if (flags == -1) {
        return -1;
    }
    if (!(flags & FD_CLOEXEC)) return 0;
    if (fcntl(fd, F_SETFD, flags & ~FD_CLOEXEC) == -1) return -1;
    return 0;
}

static int setCloexec(const int fd) {
    const int flags = fcntl(fd, F_GETFD);
    if (flags == -1) {
        return -1;
    }
    if (flags & FD_CLOEXEC) return 0;
    if (fcntl(fd, F_SETFD, flags | FD_CLOEXEC) == -1) return -1;
    return 0;
}


static int addToEpoll(const int epollFd, const int fd, const pid_t pid, const uint32_t events) {
    struct epoll_event ev = {0};
    ev.events = events;
    ev.data.ptr = malloc(sizeof(struct EpollData));
    if (ev.data.ptr == NULL) {
        errno = ENOMEM;
        return -1;
    }
    ((struct EpollData *) ev.data.ptr)->fd = fd;
    ((struct EpollData *) ev.data.ptr)->pid = pid;
    return epoll_ctl(epollFd, EPOLL_CTL_ADD, fd, &ev);
}

static void sendExitMsgToClient(const pid_t pid, const int wstatus) {
    struct ProcessExitMsg res = {
        .pid = pid,
        .exitCode = WIFEXITED(wstatus) ? WEXITSTATUS(wstatus) : -1,
    };
    struct msghdr msg = {0};
    struct iovec iov = {0};
    iov.iov_base = &res;
    iov.iov_len = sizeof(res);
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    MUST_OK(sendmsg(COMM_FD_UDS, &msg, 0));
}

struct FallbackPidNode {
    pid_t pid;
    struct FallbackPidNode *next;
};

static struct FallbackPidNode *FALLBACK_PID_HEAD = NULL;

static int trackFallbackPid(const pid_t pid) {
    struct FallbackPidNode *node = malloc(sizeof(struct FallbackPidNode));
    if (node == NULL) {
        errno = ENOMEM;
        return -1;
    }
    node->pid = pid;
    node->next = FALLBACK_PID_HEAD;
    FALLBACK_PID_HEAD = node;
    return 0;
}

static void untrackFallbackPid(const pid_t pid) {
    struct FallbackPidNode *prev = NULL;
    struct FallbackPidNode *cur = FALLBACK_PID_HEAD;
    while (cur != NULL) {
        if (cur->pid == pid) {
            if (prev == NULL) {
                FALLBACK_PID_HEAD = cur->next;
            } else {
                prev->next = cur->next;
            }
            free(cur);
            return;
        }
        prev = cur;
        cur = cur->next;
    }
}

static void reapTrackedFallbackChildren() {
    struct FallbackPidNode *prev = NULL;
    struct FallbackPidNode *cur = FALLBACK_PID_HEAD;
    while (cur != NULL) {
        int wstatus = 0;
        const pid_t pid = cur->pid;
        const pid_t r = waitpid(pid, &wstatus, WNOHANG);
        if (r == 0) {
            prev = cur;
            cur = cur->next;
            continue;
        }
        if (r == -1) {
            if (errno == EINTR) {
                continue;
            }
            if (errno == ECHILD) {
                struct FallbackPidNode *victim = cur;
                cur = cur->next;
                if (prev == NULL) {
                    FALLBACK_PID_HEAD = cur;
                } else {
                    prev->next = cur;
                }
                free(victim);
                continue;
            }
            prev = cur;
            cur = cur->next;
            continue;
        }
        sendExitMsgToClient(pid, wstatus);
        struct FallbackPidNode *victim = cur;
        cur = cur->next;
        if (prev == NULL) {
            FALLBACK_PID_HEAD = cur;
        } else {
            prev->next = cur;
        }
        free(victim);
    }
}

static void handleMessage(char *p, int childFd0, int childFd1, int childFd2, int errFd, int cloneMsgFd) {
    struct SpawnProcessOption option = {0};
    int pidfd = 0;
    int hasPidfd = 1;
    const char *errWhere = NULL;
    pid_t childPid = -1;

    // Parse memfd payload into heap-allocated SpawnProcessOption.
    ON_ERR_GOTO_W(SpawnProcessOption_parse(&option, p), cleanup);
    ON_ERR_GOTO_W(setCloexec(errFd), cleanup);
    ON_ERR_GOTO_W(setCloexec(cloneMsgFd), cleanup);

    // clone3 + CLONE_PIDFD lets helper track child via pidfd in epoll.
    struct clone_args ca = {0};
    ca.flags = CLONE_PIDFD;
    ca.pidfd = (__u64) &pidfd;
    ca.exit_signal = SIGCHLD;

    childPid = syscall(SYS_clone3, &ca, sizeof(ca));
    if (childPid == -1 && (errno == ENOSYS || errno == EPERM)) {
        hasPidfd = 0;
        childPid = fork();
    }
    if (childPid == -1) {
        goto cleanup;
    }
    if (childPid == 0) {
        // Child process: install stdio, optional cwd, then exec.
        int chdirFailed = 0;
        ON_ERR_GOTO_W(dup2(childFd0, STDIN_FILENO), childErr);
        CLOSE_UNUSED(childFd0);
        ON_ERR_GOTO_W(dup2(childFd1, STDOUT_FILENO), childErr);
        CLOSE_UNUSED(childFd1);
        ON_ERR_GOTO_W(dup2(childFd2, STDERR_FILENO), childErr);
        CLOSE_UNUSED(childFd2);
        if (option.cwd != NULL) {
            chdirFailed = 1;
            ON_ERR_GOTO_W(chdir(option.cwd), childErr);
            chdirFailed = 0;
        }
        if (option.envp != NULL) {
            ON_ERR_GOTO_W(execvpe(option.file, option.argv, option.envp), childErr);
        } else {
            ON_ERR_GOTO_W(execvp(option.file, option.argv), childErr);
        }
        // Should not reach here
        errWhere = "unreachable";
    childErr:;
        // Report [errno, chdirFailed] back to client and exit immediately.
        const int savedErrno = errno;
        fullWriteOrExit(errFd, &savedErrno, sizeof(savedErrno));
        fullWriteOrExit(errFd, &chdirFailed, sizeof(chdirFailed));
        _exit(1);
        // -------------------------- Child Process --------------------------
    }

    if (hasPidfd) {
        // Parent side with pidfd: register in epoll and wake epoll loop.
        ON_ERR_GOTO_W(addToEpoll(EPOLL_FD, pidfd, childPid, EPOLLIN | EPOLLHUP | EPOLLERR), cleanup);
        const int64_t dummy = 1;
        ON_ERR_GOTO_W(write(WAKEUP_FD, &dummy, sizeof(dummy)), cleanup);
    } else {
        // Fallback path: no pidfd available, track pid and reap via signalfd(SIGCHLD).
        if (trackFallbackPid(childPid) == -1) {
            const int e = errno;
            kill(childPid, SIGKILL);
            while (waitpid(childPid, NULL, 0) == -1 && errno == EINTR) {
            }
            errno = e;
            goto cleanup;
        }
    }
    errno = 0;
    errWhere = NULL;
cleanup:;
    // Always reply with clone outcome so client can finish request path.
    const struct ProcessCloneResult res = {
        .pid = childPid,
        ._errno = errno,
    };
    fullWriteOrExit(cloneMsgFd, &res, sizeof(res));
    if (res._errno != 0 && hasPidfd)
        CLOSE_UNUSED(pidfd);
}

static void recvMessage() {
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
        fprintf(stderr, "Invalid control message length: %lu\n", cmsghdr->cmsg_len);
        exit(1);
    }
    // Receive fds in strict REQ_* order.
    int fds[REQ_FD_LEN] = {0};
    memcpy(fds, CMSG_DATA(cmsghdr), sizeof(fds));
    for (int i = 0; i < sizeof(fds) / sizeof(fds[0]); i++) {
        if (fds[i] == 0) {
            fprintf(stderr, "Invalid fd in control message, index: %d\n", i);
            exit(1);
        }
    }
    const int memFd = fds[REQ_MEM_FD_IDX];
    const int errFd = fds[REQ_CHILD_ERR_IDX];
    const int cloneMsgFd = fds[REQ_CLONE_MSG_IDX];
    const int childFd0 = fds[REQ_CHILD_FD0_IDX];
    const int childFd1 = fds[REQ_CHILD_FD1_IDX];
    const int childFd2 = fds[REQ_CHILD_FD2_IDX];
    // mmap request payload and dispatch.
    struct stat st;
    MUST_OK(fstat(memFd, &st));
    // mmap
    void *p = MMAP_MUST_OK(mmap(NULL, st.st_size, PROT_READ, MAP_SHARED, memFd, 0));
    // handle message
    handleMessage(p, childFd0, childFd1, childFd2, errFd, cloneMsgFd);
    // clean up
    MUST_OK(munmap(p, st.st_size));
    MUST_OK(close(memFd));
    MUST_OK(close(errFd));
    MUST_OK(close(childFd0));
    MUST_OK(close(childFd1));
    MUST_OK(close(childFd2));
    MUST_OK(close(cloneMsgFd));
}


int main() {
    // Ensure stdio ready
    MUST_OK(clearCloexec(STDIN_FILENO));
    MUST_OK(clearCloexec(STDOUT_FILENO));
    MUST_OK(clearCloexec(STDERR_FILENO));
    MUST_OK(clearNonBlock(STDIN_FILENO));
    MUST_OK(clearNonBlock(STDOUT_FILENO));
    MUST_OK(clearNonBlock(STDERR_FILENO));

    // Single-threaded event loop:
    // - COMM_FD_UDS events: new spawn requests
    // - pidfd events: child exit notifications
    // - WAKEUP_FD: self-wakeup after modifying epoll set
    // Setup epoll
    EPOLL_FD = MUST_OK(epoll_create1(EPOLL_CLOEXEC));
    // Block SIGCHLD in process, then consume child-exit notifications via signalfd.
    sigset_t sigchldMask = {0};
    MUST_OK(sigemptyset(&sigchldMask));
    MUST_OK(sigaddset(&sigchldMask, SIGCHLD));
    MUST_OK(sigprocmask(SIG_BLOCK, &sigchldMask, NULL));
    SIGCHLD_FD = MUST_OK(signalfd(-1, &sigchldMask, SFD_CLOEXEC | SFD_NONBLOCK));

    // Setup eventfd, used to wakeup epoll immediately
    WAKEUP_FD = MUST_OK(eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK));
    MUST_OK(addToEpoll(EPOLL_FD, WAKEUP_FD, 0, EPOLLIN));

    // Setup Comm UDS
    MUST_OK(setNonBlock(COMM_FD_UDS));
    MUST_OK(setCloexec(COMM_FD_UDS));
    MUST_OK(addToEpoll(EPOLL_FD, COMM_FD_UDS, 0, EPOLLIN|EPOLLHUP|EPOLLERR));
    MUST_OK(addToEpoll(EPOLL_FD, SIGCHLD_FD, 0, EPOLLIN));

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
                int64_t dummy = 0;
                MUST_OK(read(WAKEUP_FD, &dummy, sizeof(dummy)));
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
            if (fd == SIGCHLD_FD) {
                while (1) {
                    struct signalfd_siginfo si = {0};
                    const ssize_t n = read(SIGCHLD_FD, &si, sizeof(si));
                    if (n == -1 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
                        break;
                    }
                    if (n == -1 && errno == EINTR) {
                        continue;
                    }
                    if (n == -1) {
                        perror("read(signalfd)");
                        _exit(1);
                    }
                    if (n != sizeof(si)) {
                        fprintf(stderr, "short read from signalfd\n");
                        _exit(1);
                    }
                }
                reapTrackedFallbackChildren();
                continue;
            }
            // pidfd event: child terminated, report ProcessExitMsg to client.
            if (ev.events & EPOLLERR) {
                const pid_t pid = EPOLL_DATA_PID(ev);
                fprintf(stderr, "pidfd error, pid: %d\n", pid);
                _exit(1);
            }
            if (ev.events & EPOLLIN) {
                errno = 0;
                int wstatus = 0;
                waitpid(EPOLL_DATA_PID(ev), &wstatus, 0);
                if (errno == EINTR) {
                    continue;
                }
                if (errno != 0) {
                    perror("waitpid");
                    _exit(1);
                }
                sendExitMsgToClient(EPOLL_DATA_PID(ev), wstatus);
                // clean up
                MUST_OK(epoll_ctl(EPOLL_FD, EPOLL_CTL_DEL, fd, NULL));
                free(ev.data.ptr);
                continue;
            }
            fprintf(stderr, "Unknown event in epoll, fd: %d\n", fd);
            _exit(1);
        }
    }
}

