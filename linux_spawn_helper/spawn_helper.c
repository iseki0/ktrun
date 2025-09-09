#include "spawn_helper.h"
#include "spawn_helper_comm.h"
#include "mblock.h"
#include "require_not_null.h"
#include "util.h"

#define _GNU_SOURCE // NOLINT(*-reserved-identifier)
#include <errno.h>
#include <stdio.h>
#include <sys/socket.h>
#include <sys/mman.h>

#define MFD_CLOEXEC 0x0001U
#define SYS_memfd_create			319

extern const char *const _binary_linux_spawn_helper_bin_start;
extern const char *const _binary_linux_spawn_helper_bin_end;
extern const int _binary_linux_spawn_helper_bin_size;

int binaryMemFd = -1;

int initHelper() {
    // load binary to memfd
    const int memfd = MUST_OK(syscall(SYS_memfd_create, "spawn_helper", MFD_CLOEXEC));
    MUST_OK(ftruncate(memfd, _binary_linux_spawn_helper_bin_size));
    const void *const p = MMAP_MUST_OK(mmap(NULL, _binary_linux_spawn_helper_bin_size, PROT_READ | PROT_WRITE,
        MAP_SHARED, memfd, 0));
    memcpy((void *) p, _binary_linux_spawn_helper_bin_start, _binary_linux_spawn_helper_bin_size);
    MUST_OK(munmap((void *) p, _binary_linux_spawn_helper_bin_size));
    binaryMemFd = memfd;
    return 0;
}

int startHelper(int *udsFd, int *helperPid) {
    errno = 0;
    int fds[2] = {0};
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, fds) == -1) {
        return -1;
    }
    const int pid = fork();
    if (pid == -1) {
        const int e = errno;
        close(fds[0]);
        close(fds[1]);
        errno = e;
        return -1;
    }
    if (pid == 0) {
        // children
        if (dup2(fds[1], COMM_FD_UDS) == -1) {
            _exit(1);
        };
        char *argv[] = {"spawn_helper", NULL};
        extern char **environ;
        if (fexecve(binaryMemFd, argv, environ) == -1) perror("fexecve failed");
        _exit(1);
    }
    // parent
    close(fds[1]);
    *udsFd = fds[0];
    *helperPid = pid;
    return 0;
}

int sendSpawnRequest(int helperFd, char *debugName, char *file, char **argv, char **envp, int envpSet, char *cwd,
                     int errFd, int stdinFd, int stdoutFd, int stderrFd) {
    REQUIRE_NOT_NULL(file);
    REQUIRE_NOT_NULL(argv);
    errno = 0;
    const char *errWhere = NULL;
    int memFd = -1;
    char *buf = NULL;

    const struct SpawnProcessOption option = {
        .envpSet = envpSet,
        .file = file,
        .cwd = cwd,
        .argv = argv,
        .envp = envp,
    };
    const int bufSize = (int) SpawnProcessOption_bytesSize(&option);

    // prepare memfd, shared memory
    memFd = ON_ERR_GOTO(syscall(SYS_memfd_create, debugName, MFD_CLOEXEC), cleanup);
    buf = mmap(NULL, bufSize, PROT_READ | PROT_WRITE, MAP_SHARED, memFd, 0);
    if (buf == MAP_FAILED) goto cleanup;
    // fill the shared memory with execve data
    SpawnProcessOption_bytes(&option, buf);
    munmap(buf, bufSize);
    // prepare fd array
    int fds[REQ_FD_LEN] = {0};
    fds[REQ_CHILD_FD0_IDX] = stdinFd;
    fds[REQ_CHILD_FD1_IDX] = stdoutFd;
    fds[REQ_CHILD_FD2_IDX] = stderrFd;
    fds[REQ_ERR_FD_IDX] = errFd;
    fds[REQ_MEM_FD_IDX] = memFd;

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
    close(memFd);
    return 0;
cleanup:;
    const int savedErrno = errno;
    if (memFd != -1) {
        close(memFd);
    }
    errno = savedErrno;
    return -1;
}
