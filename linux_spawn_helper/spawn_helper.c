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


int sendSpawnRequest(char *debugName, char *file, char **argv, char **envp, int envpSet, int errFd,
                     int stdinFd, int stdoutFd, int stderrFd) {
    REQUIRE_NOT_NULL(file);
    REQUIRE_NOT_NULL(argv);
    errno = 0;
    const char *errWhere = NULL;
    int memFd = -1;
    char *buf = NULL;

    // prepare data to send
    // execve data
    const struct SpawnProcessOption option = {
        .envpSet = envpSet,
        .file = file,
        .argv = argv,
        .envp = envp,
    };
    memFd = ON_ERR_GOTO(syscall(SYS_memfd_create, debugName, MFD_CLOEXEC), cleanup);
    const int bufSize = (int) SpawnProcessOption_bytesSize(&option);
    ON_ERR_GOTO(ftruncate(memFd, bufSize), cleanup);
    buf = mmap(NULL, bufSize, PROT_READ | PROT_WRITE, MAP_SHARED, memFd, 0);
    if (buf == MAP_FAILED) goto cleanup;
    SpawnProcessOption_bytes(&option, buf);
    // fds
    int fds[REQ_FD_LEN] = {0};
    fds[REQ_CHILD_FD0_IDX] = stdinFd;
    fds[REQ_CHILD_FD1_IDX] = stdoutFd;
    fds[REQ_CHILD_FD2_IDX] = stderrFd;
    fds[REQ_ERR_FD_IDX] = errFd;
    fds[REQ_MEM_FD_IDX] = memFd;

    // send data
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
    ON_ERR_GOTO(sendmsg(COMM_FD_UDS, &msg, 0), cleanup);
    return 0;
cleanup:;
    const int savedErrno = errno;
    if (buf != NULL && buf != MAP_FAILED) {
        munmap(buf, bufSize);
    }
    if (memFd != -1) {
        MUST_OK(close(memFd));
    }
    errno = savedErrno;
    return -1;
}
