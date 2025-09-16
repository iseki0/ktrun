#include "spawn_helper.h"
#include "spawn_helper_comm.h"
#include "mblock.h"
#include "require_not_null.h"
#include "util.h"

#define _GNU_SOURCE // NOLINT(*-reserved-identifier)
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <sys/socket.h>
#include <sys/mman.h>

#define MFD_CLOEXEC 0x0001U
#define SYS_memfd_create			319

#define CLOSE_FD_IF_NEED(expr) {if(expr > -1) {if(close(expr) == -1) {perror("close"); abort();}; expr = -1;}}

extern const unsigned char _binary_linux_spawn_helper_bin_start[];
extern const unsigned char _binary_linux_spawn_helper_bin_end[];

int binaryMemFd = -1;

int initHelper() {
    size_t sz = (size_t) (_binary_linux_spawn_helper_bin_end - _binary_linux_spawn_helper_bin_start);
    // load binary to memfd
    int memfd = -1;
    memfd = ON_ERR_GOTO(syscall(SYS_memfd_create, "spawn_helper", MFD_CLOEXEC), err);
    ON_ERR_GOTO(ftruncate(memfd, sz), err);
    const void *const p = mmap(NULL, sz, PROT_READ | PROT_WRITE, MAP_SHARED, memfd, 0);
    if (p == MAP_FAILED) {
        goto err;
    }
    memcpy((void *) p, _binary_linux_spawn_helper_bin_start, sz);
    MUST_OK(munmap((void *) p, sz));
    binaryMemFd = memfd;
    return 0;
err:;
    const int e = errno;
    if (memfd != -1) {
        MUST_OK(close(memfd));
    }
    errno = e;
    return -1;
}

struct HelperStartResult startHelper() {
    struct HelperStartResult result = {0};
    errno = 0;

    int errFd[2] = {-1, -1};
    int sv[2] = {-1, -1};
    ON_ERR_GOTO(pipe2(errFd, O_CLOEXEC), err);
    ON_ERR_GOTO(socketpair(AF_UNIX, SOCK_SEQPACKET|SOCK_CLOEXEC, 0, sv), err);

    sigset_t all = {0};
    sigset_t oldmask = {0};
    MUST_OK(sigfillset(&all));
    MUST_OK(sigprocmask(SIG_SETMASK, &all, &oldmask));
    const int pid = ON_ERR_GOTO(fork(), err);
    if (pid == 0) {
        // children
        ON_ERR_GOTO(dup2(sv[1], COMM_FD_UDS), childErr);
        // Reset all signal handlers to be ignored, mimicking the original code's logic.
        // This prevents the child from inheriting unintended signal handlers.
        struct sigaction sa = {0};
        sa.sa_handler = SIG_DFL;
        for (int i = 1; i < NSIG; i++) {
            // SIGKILL and SIGSTOP cannot be caught or ignored; attempting to set a handler for them will fail.
            // We skip them to be explicit.
            if (i == SIGKILL || i == SIGSTOP) continue;
            // ignore sigaction error, not only for SIGKILL and SIGSTOP.
            // https://bugzilla.redhat.com/show_bug.cgi?id=53394
            sigaction(i, &sa, NULL);
        }
        sigset_t empty = {0};
        ON_ERR_GOTO(sigemptyset(&empty), childErr);
        ON_ERR_GOTO(sigprocmask(SIG_SETMASK, &empty, NULL), childErr);
        // exec
        char *argv[] = {"spawn_helper", NULL};
        extern char **environ;
        fexecve(binaryMemFd, argv, environ);
    childErr:;
        const int e = errno;
        fullWriteOrExit(errFd[1], &e, sizeof(e));
        _exit(1);
    }
    MUST_OK(sigprocmask(SIG_SETMASK, &oldmask, NULL));
    // parent
    CLOSE_FD_IF_NEED(errFd[1]);
    const int childErrnoLen = readFull(errFd[0], &result.childErrno, sizeof(result.childErrno));
    if (childErrnoLen == -1) {
        // read child errno failed
        result.childErrno = 0;
        goto err;
    }
    if (childErrnoLen != 0) {
        if (childErrnoLen != sizeof(result.childErrno)) {
            // read incompleted child errno
            result.childErrno = EBADMSG;
        }
        goto err;
    }

    CLOSE_FD_IF_NEED(errFd[0]);
    CLOSE_FD_IF_NEED(sv[1]);
    result.commFd = sv[0];
    return result;

err:;
    result.commFd = -1;
    const int e = errno;
    CLOSE_FD_IF_NEED(errFd[0]);
    CLOSE_FD_IF_NEED(errFd[1]);
    CLOSE_FD_IF_NEED(sv[0]);
    CLOSE_FD_IF_NEED(sv[1]);
    errno = e;
    return result;
}

int sendSpawnRequest(int helperFd, char *debugName, char *file, char **argv, char **envp, char *cwd, int stdinFd,
                     int stdoutFd, int stderrFd, bool *chdirFailed) {
    REQUIRE_NOT_NULL(file);
    REQUIRE_NOT_NULL(argv);
    errno = 0;
    int memFd = -1;
    char *buf = NULL;
    int childErrFd[2] = {-1, -1};
    int cloneMsgFd[2] = {-1, -1};

    ON_ERR_GOTO(pipe2(childErrFd, O_CLOEXEC), cleanup);
    ON_ERR_GOTO(pipe2(cloneMsgFd, O_CLOEXEC), cleanup);

    const struct SpawnProcessOption option = {
        .file = file,
        .cwd = cwd,
        .argv = argv,
        .envp = envp,
    };
    const int bufSize = (int) SpawnProcessOption_bytesSize(&option);

    // prepare memfd, shared memory
    memFd = ON_ERR_GOTO(syscall(SYS_memfd_create, debugName, MFD_CLOEXEC), cleanup);
    ON_ERR_GOTO(ftruncate(memFd, bufSize), cleanup);
    buf = mmap(NULL, bufSize, PROT_READ | PROT_WRITE, MAP_SHARED, memFd, 0);
    if (buf == MAP_FAILED) goto cleanup;
    // fill the shared memory with execve data
    SpawnProcessOption_bytes(&option, buf);
    MUST_OK(munmap(buf, bufSize));
    buf = NULL;
    // prepare fd array
    int fds[REQ_FD_LEN] = {0};
    fds[REQ_CHILD_FD0_IDX] = stdinFd;
    fds[REQ_CHILD_FD1_IDX] = stdoutFd;
    fds[REQ_CHILD_FD2_IDX] = stderrFd;
    fds[REQ_MEM_FD_IDX] = memFd;
    fds[REQ_CHILD_ERR_IDX] = childErrFd[1];
    fds[REQ_CLONE_MSG_IDX] = cloneMsgFd[1];

    // Prepare unix socket message
    // control message buffer
    char cmsgBuf[REQ_CMSG_SIZE] = {0};
    // dummy buffer, UDS required must have at least 1 byte
    char dummy = 0;
    struct iovec iov = {
        .iov_base = &dummy,
        .iov_len = sizeof(dummy)
    };
    const struct msghdr msg = {
        .msg_iov = &iov,
        .msg_iovlen = 1,
        .msg_control = &cmsgBuf,
        .msg_controllen = sizeof(cmsgBuf),
    };
    // fill control message buffer
    struct cmsghdr *cmsghdr = REQUIRE_NOT_NULL(CMSG_FIRSTHDR(&msg));
    cmsghdr->cmsg_level = SOL_SOCKET;
    cmsghdr->cmsg_type = SCM_RIGHTS;
    cmsghdr->cmsg_len = REQ_CMSG_SIZE;
    memcpy(CMSG_DATA(cmsghdr), fds, sizeof(fds));
    // send message
    ON_ERR_GOTO(sendmsg(helperFd, &msg, 0), cleanup);
    // close unneeded fds
    CLOSE_FD_IF_NEED(memFd);
    CLOSE_FD_IF_NEED(childErrFd[1]);
    CLOSE_FD_IF_NEED(cloneMsgFd[1]);
    // handle clone result and error
    struct ProcessCloneResult cloneResult = {0};
    if (ON_ERR_GOTO(readFull(cloneMsgFd[0], &cloneResult, sizeof(cloneResult)), cleanup) != sizeof(cloneResult)) {
        cloneResult._errno = EBADMSG;
    }
    if (cloneResult._errno != 0) {
        errno = cloneResult._errno;
        goto cleanup;
    }
    // handle child error
    int childErrno[2] = {0, 0};
    const int childErrnoLen = ON_ERR_GOTO(readFull(childErrFd[0], childErrno, sizeof(childErrno)), cleanup);
    if (childErrnoLen != sizeof(childErrno) && childErrnoLen != 0) {
        childErrno[0] = EBADMSG;
    }
    if (childErrno[1] == 1) {
        *chdirFailed = true;
    }
    if (childErrno[0] != 0) {
        errno = childErrno[0];
        goto cleanup;
    }
    return cloneResult.pid;
cleanup:;
    int savedErrno = errno;
    CLOSE_FD_IF_NEED(memFd);
    if (buf != NULL) {
        if (munmap(buf, bufSize) == -1) {
            perror("munmap");
            abort();
        }
    }
    CLOSE_FD_IF_NEED(childErrFd[0]);
    CLOSE_FD_IF_NEED(childErrFd[1]);
    CLOSE_FD_IF_NEED(cloneMsgFd[0]);
    CLOSE_FD_IF_NEED(cloneMsgFd[1]);
    errno = savedErrno;
    return -1;
}
