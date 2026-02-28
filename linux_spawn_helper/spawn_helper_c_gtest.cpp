#include <gtest/gtest.h>

#include <array>
#include <cerrno>
#include <cstring>
#include <string>
#include <thread>

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

extern "C" {
#include "spawn_helper.h"
#include "spawn_helper_comm.h"
}

namespace {
void expectSameOpenFile(const int a, const int b) {
    struct stat sa = {};
    struct stat sb = {};
    ASSERT_EQ(0, fstat(a, &sa));
    ASSERT_EQ(0, fstat(b, &sb));
    EXPECT_EQ(sa.st_dev, sb.st_dev);
    EXPECT_EQ(sa.st_ino, sb.st_ino);
}

std::array<int, REQ_FD_LEN> recvRequestFds(const int helperSideFd) {
    std::array<int, REQ_FD_LEN> receivedFds{};
    receivedFds.fill(-1);

    char dummy = 0;
    struct iovec iov = {
        .iov_base = &dummy,
        .iov_len = sizeof(dummy),
    };
    char control[REQ_CMSG_SIZE] = {0};
    struct msghdr msg = {
        .msg_iov = &iov,
        .msg_iovlen = 1,
        .msg_control = control,
        .msg_controllen = sizeof(control),
    };

    const ssize_t n = recvmsg(helperSideFd, &msg, 0);
    if (n != 1) {
        ADD_FAILURE() << "recvmsg returned " << n;
        return receivedFds;
    }
    if ((msg.msg_flags & MSG_CTRUNC) != 0) {
        ADD_FAILURE() << "control message truncated";
        return receivedFds;
    }

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    if (cmsg == nullptr) {
        ADD_FAILURE() << "missing control message";
        return receivedFds;
    }
    if (cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS) {
        ADD_FAILURE() << "unexpected control message type";
        return receivedFds;
    }

    const size_t fdBytes = sizeof(int) * REQ_FD_LEN;
    std::memcpy(receivedFds.data(), CMSG_DATA(cmsg), fdBytes);
    return receivedFds;
}
} // namespace

TEST(SpawnHelperCTest, SendSpawnRequest_EncodesPayloadAndReturnsPid) {
    int uds[2] = {-1, -1};
    ASSERT_EQ(0, socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, uds));

    int stdinPipe[2] = {-1, -1};
    int stdoutPipe[2] = {-1, -1};
    int stderrPipe[2] = {-1, -1};
    ASSERT_EQ(0, pipe2(stdinPipe, O_CLOEXEC));
    ASSERT_EQ(0, pipe2(stdoutPipe, O_CLOEXEC));
    ASSERT_EQ(0, pipe2(stderrPipe, O_CLOEXEC));

    constexpr int kExpectedPid = 45678;
    std::thread helperEmulator([&] {
        auto reqFds = recvRequestFds(uds[1]);

        ASSERT_NE(-1, reqFds[REQ_CHILD_FD0_IDX]);
        ASSERT_NE(-1, reqFds[REQ_CHILD_FD1_IDX]);
        ASSERT_NE(-1, reqFds[REQ_CHILD_FD2_IDX]);
        expectSameOpenFile(stdinPipe[0], reqFds[REQ_CHILD_FD0_IDX]);
        expectSameOpenFile(stdoutPipe[1], reqFds[REQ_CHILD_FD1_IDX]);
        expectSameOpenFile(stderrPipe[1], reqFds[REQ_CHILD_FD2_IDX]);

        const int reqMemFd = reqFds[REQ_MEM_FD_IDX];
        ASSERT_NE(-1, reqMemFd);

        struct stat st = {};
        ASSERT_EQ(0, fstat(reqMemFd, &st));
        ASSERT_GT(st.st_size, 0);

        void *raw = mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ, MAP_SHARED, reqMemFd, 0);
        ASSERT_NE(MAP_FAILED, raw);

        SpawnProcessOption parsed = {};
        ASSERT_EQ(0, SpawnProcessOption_parse(&parsed, static_cast<char *>(raw)));
        EXPECT_STREQ("/usr/bin/env", parsed.file);
        EXPECT_STREQ("/tmp", parsed.cwd);
        ASSERT_NE(nullptr, parsed.argv);
        EXPECT_STREQ("env", parsed.argv[0]);
        EXPECT_STREQ("A=1", parsed.argv[1]);
        EXPECT_EQ(nullptr, parsed.argv[2]);
        ASSERT_NE(nullptr, parsed.envp);
        EXPECT_STREQ("PATH=/usr/bin", parsed.envp[0]);
        EXPECT_EQ(nullptr, parsed.envp[1]);

        SpawnProcessOption_free(&parsed);
        ASSERT_EQ(0, munmap(raw, static_cast<size_t>(st.st_size)));

        const ProcessCloneResult cloneResult = {
            .pid = kExpectedPid,
            ._errno = 0,
        };
        ASSERT_EQ(static_cast<ssize_t>(sizeof(cloneResult)),
                  write(reqFds[REQ_CLONE_MSG_IDX], &cloneResult, sizeof(cloneResult)));

        // No child error frame => close write end to signal success EOF.
        for (const int fd: reqFds) {
            close(fd);
        }
    });

    bool chdirFailed = false;
    char *argv[] = {const_cast<char *>("env"), const_cast<char *>("A=1"), nullptr};
    char *envp[] = {const_cast<char *>("PATH=/usr/bin"), nullptr};
    errno = 0;
    const int pid = sendSpawnRequest(
        uds[0],
        const_cast<char *>("spawn-req-ut"),
        const_cast<char *>("/usr/bin/env"),
        argv,
        envp,
        const_cast<char *>("/tmp"),
        stdinPipe[0],
        stdoutPipe[1],
        stderrPipe[1],
        &chdirFailed);

    ASSERT_EQ(kExpectedPid, pid);
    EXPECT_FALSE(chdirFailed);

    helperEmulator.join();
    close(uds[0]);
    close(uds[1]);
    close(stdinPipe[0]);
    close(stdinPipe[1]);
    close(stdoutPipe[0]);
    close(stdoutPipe[1]);
    close(stderrPipe[0]);
    close(stderrPipe[1]);
}

TEST(SpawnHelperCTest, CreateAnonymousShmFd_CreatesMappableFd) {
    const int fd = spawnHelperTest_createAnonymousShmFd("spawn-ut-shm", false);
    ASSERT_NE(-1, fd);

    constexpr size_t kSize = 4096;
    ASSERT_EQ(0, ftruncate(fd, static_cast<off_t>(kSize)));
    void *p = mmap(nullptr, kSize, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    ASSERT_NE(MAP_FAILED, p);
    std::memset(p, 0x5A, kSize);
    ASSERT_EQ(0, munmap(p, kSize));
    ASSERT_EQ(0, close(fd));
}

TEST(SpawnHelperCTest, CreateSharedPayloadMemfdFd_CreatesMemfdWhenSupported) {
    const int fd = spawnHelperTest_createSharedPayloadMemfdFd("spawn-req-ut");
    if (fd == -1 && errno == ENOSYS) {
        GTEST_SKIP() << "memfd is not supported on this environment";
    }
    ASSERT_NE(-1, fd) << "errno=" << errno;

    char linkPath[256] = {0};
    char procFdPath[64] = {0};
    std::snprintf(procFdPath, sizeof(procFdPath), "/proc/self/fd/%d", fd);
    const ssize_t n = readlink(procFdPath, linkPath, sizeof(linkPath) - 1);
    ASSERT_GT(n, 0);
    linkPath[n] = '\0';

    const std::string path(linkPath);
    EXPECT_NE(std::string::npos, path.find("memfd:")) << path;

    ASSERT_EQ(0, close(fd));
}

TEST(SpawnHelperCTest, CreateSharedPayloadShmFd_CreatesDevShmFd) {
    const int fd = spawnHelperTest_createSharedPayloadShmFd("spawn-req-ut");
    ASSERT_NE(-1, fd) << "errno=" << errno;

    char linkPath[256] = {0};
    char procFdPath[64] = {0};
    std::snprintf(procFdPath, sizeof(procFdPath), "/proc/self/fd/%d", fd);
    const ssize_t n = readlink(procFdPath, linkPath, sizeof(linkPath) - 1);
    ASSERT_GT(n, 0);
    linkPath[n] = '\0';

    const std::string path(linkPath);
    EXPECT_NE(std::string::npos, path.find("/dev/shm/")) << path;

    ASSERT_EQ(0, close(fd));
}
