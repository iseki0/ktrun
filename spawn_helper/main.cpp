#include <iostream>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <csignal>
#include <linux/sched.h>
#include <fcntl.h>
#include <sched.h>
#include <unistd.h>
#include <memory>
#include <cstring>

#include "helper.hpp"
// #include "helper_comm.hpp"


namespace {
    int epoll_fd;
    int event_fd;

    class EpollData {
    public:
        int fd;
        uint64_t pid;
        int commFd;

        explicit EpollData(const int fd) {
            this->fd = fd;
            this->pid = -1;
            this->commFd = -1;
        }

        EpollData(const int fd, const uint64_t pid, const int commFd) {
            this->fd = fd;
            this->pid = pid;
            this->commFd = commFd;
        }
    };

    bool setCloexec(int fd) {
        if (fcntl(fd, F_SETFD, FD_CLOEXEC) == -1) {
            std::cerr << "setCloexec failed: " << fd << std::endl;
            return false;
        }
        return true;
    }

    bool fullWrite(const int fd, const void *buf, const size_t size) {
        size_t totalWritten = 0;
        while (totalWritten < size) {
            const ssize_t written = write(fd, static_cast<const char *>(buf) + totalWritten, size - totalWritten);
            if (written == -1) {
                if (errno == EINTR) {
                    continue;
                }
                return false;
            }
            if (written == 0) {
                return false;
            }
            totalWritten += written;
        }
        return true;
    }


    class MappedFile final {
    public:
        void *addr;
        size_t size;

        explicit MappedFile(const int fd) {
            struct stat st = {0};
            if (fstat(fd, &st) == -1) {
                perror("fstat");
                addr = nullptr;
                size = 0;
                return;
            }
            addr = mmap(nullptr, st.st_size, PROT_READ, MAP_SHARED, fd, 0);
            if (addr == MAP_FAILED) {
                perror("mmap");
                addr = nullptr;
                size = 0;
                return;
            }
            size = st.st_size;
        }
        ~MappedFile() {
            if (addr != nullptr && addr != MAP_FAILED) {
                munmap(addr, size);
                addr = nullptr;
            }
        }
    };

    class MessageHandleException final : public std::exception {
    public:
        const char *msg;
        int32_t err;

        MessageHandleException(const char *msg, const int32_t err) {
            this->msg = msg;
            this->err = err;
        }
    };


    int64_t handleMessage0(int fds[COMM_FD_COUNT], int *pidfd) {
        for (int i = 0; i < COMM_FD_COUNT; i++) {
            if (!setCloexec(fds[i])) {
                throw MessageHandleException("set cloexec", errno);
            }
        }

        const auto mappedFile = MappedFile(fds[COMM_REQ_MEMFD]);
        if (mappedFile.addr == nullptr) {
            throw MessageHandleException("map memory", errno);
        }

        const auto header = static_cast<HelperCommHeader *>(mappedFile.addr);
        std::unique_ptr<char *[]> argv;
        std::unique_ptr<char *[]> envp;
        try {
            argv = std::make_unique<char *[]>(header->argc + 1);
            envp = std::make_unique<char *[]>(header->envpc + 1);
        } catch (const std::bad_alloc &_) {
            throw MessageHandleException("allocate string array", errno);
        }
        const auto file = reinterpret_cast<char *>(header + 1);
        char *p = file + strlen(file) + 1;
        const auto cwd = header->chdir ? p : nullptr;
        if (cwd != nullptr) {
            p = cwd + strlen(cwd) + 1;
        }
        for (int i = 0; i < header->argc; i++) {
            argv[i] = p;
            p += strlen(p) + 1;
        }
        argv[header->argc] = nullptr;
        for (int i = 0; i < header->envpc; i++) {
            envp[i] = p;
            p += strlen(p) + 1;
        }
        envp[header->envpc] = nullptr;

        clone_args ca = {
            .flags = CLONE_PIDFD,
            .pidfd = reinterpret_cast<unsigned long long>(&pidfd),
            .exit_signal = SIGCHLD
        };
        const auto pid = syscall(SYS_clone3, &ca, sizeof(ca));;
        if (pid == -1) {
            throw MessageHandleException("fork", errno);
        }
        if (pid == 0) {
            // child process
            int step = CHILD_STEP_STDIN;
            if (dup2(fds[COMM_STDIN], STDIN_FILENO) == -1) goto childErr;
            step = CHILD_STEP_STDOUT;
            if (dup2(fds[COMM_STDOUT], STDOUT_FILENO) == -1) goto childErr;
            step = CHILD_STEP_STDERR;
            if (dup2(fds[COMM_STDERR], STDERR_FILENO) == -1) goto childErr;
            if (cwd != nullptr) {
                step = CHILD_STEP_CHDIR;
                if (chdir(cwd) == -1) goto childErr;
            }
            step = CHILD_STEP_EXEC;
            if (envp[0] != nullptr) {
                execvpe(file, argv.get(), envp.get());
            } else {
                execv(file, argv.get());
            }
        childErr:
            const auto err = errno;
            write(fds[COMM_CHILD_ERR_REPORT], &err, sizeof(err));
            write(fds[COMM_CHILD_ERR_REPORT], &step, sizeof(step));
            _exit(1);
        }
        return pid;
    }

    void handleMessage(int fds[COMM_FD_COUNT]) {
        const auto commFd = fds[COMM_STATUS_REPORT];
        try {
            int pidfd = 0;
            const uint64_t pid = handleMessage0(fds, &pidfd);
            fullWrite(commFd, &pid, sizeof(pid));
            epoll_event ev = {};
            ev.events = EPOLLIN | EPOLLHUP | EPOLLERR;
            ev.data.ptr = new EpollData(pidfd, pid, commFd);
            if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, pidfd, &ev) == -1) {
                perror("epoll_ctl(ADD, pidfd)");
                exit(1);
            }
        } catch (const MessageHandleException &e) {
            constexpr uint64_t negPid = -1L;
            fullWrite(commFd, &negPid, sizeof(negPid));
            fullWrite(commFd, &e.err, sizeof(e.err));
            fullWrite(commFd, e.msg, strlen(e.msg) + 1);
            close(commFd);
        }
        for (int i = 0; i < COMM_FD_COUNT; i++) {
            if (fds[i] == commFd) continue;
            close(fds[i]);
        }
    }
}


int main() {
    epoll_fd = epoll_create1(EPOLL_CLOEXEC);
    if (epoll_fd == -1) {
        perror("epoll_create1");
        return 1;
    }

    event_fd = eventfd(0, EFD_CLOEXEC);
    if (event_fd == -1) {
        perror("eventfd");
        return 1;
    }

    epoll_event ev = {};
    ev.events = EPOLLIN | EPOLLERR;
    ev.data.ptr = new EpollData(event_fd);
    if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, event_fd, &ev) == -1) {
        perror("epoll_ctl(event_fd)");
        return 1;
    }
    ev.events = EPOLLIN | EPOLLHUP | EPOLLERR;
    ev.data.ptr = new EpollData(epoll_fd);
    if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, COMM_FD_UDS, &ev) == -1) {
        perror("epoll_ctl(COMM_FD_UDS)");
        return 1;
    }

    while (true) {
        const int n = epoll_wait(epoll_fd, &ev, 1, -1);
        if (n == -1) {
            perror("epoll_wait");
            return 1;
        }
        if (n == 0) {
            continue;
        }
        const auto data = static_cast<EpollData *>(ev.data.ptr);
        if (data->fd == event_fd) {
            long long i;
            if (read(event_fd, &i, sizeof(i)) == -1) {
                perror("read(event_fd)");
                return 1;
            }
            continue;
        }
        if (data->fd == COMM_FD_UDS) {
            if (ev.events & EPOLLERR) {
                perror("EPOLLERR on COMM_FD_UDS");
                return 1;
            }
            if (ev.events & EPOLLHUP) {
                return 0;
            }
            char buf; // dummy
            iovec iov = {};
            iov.iov_base = &buf;
            iov.iov_len = sizeof(buf);
            msghdr msg = {};
            msg.msg_iov = &iov;
            msg.msg_iovlen = 1;
            char cmsgBuf[CMSG_SPACE(sizeof(int) * COMM_FD_COUNT)] = {};
            msg.msg_control = cmsgBuf;
            msg.msg_controllen = sizeof(cmsgBuf);
            const ssize_t r = recvmsg(COMM_FD_UDS, &msg, MSG_CMSG_CLOEXEC);
            if (r == -1) {
                if (errno == EINTR) {
                    continue;
                }
                perror("recvmsg");
                return 1;
            }
            if (r == 0) {
                return 0;
            }
            cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
            if (cmsg == nullptr || cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS ||
                cmsg->cmsg_len != CMSG_LEN(sizeof(int) * 6)) {
                std::cerr << "Invalid control message" << std::endl;
                return 1;
            }
            int fds[6];
            memcpy(fds, CMSG_DATA(cmsg), sizeof(fds));
            handleMessage(fds);
            continue;
        }
        int status = 0;
        if (const auto r = waitpid(data->pid, &status, WNOHANG); r == -1) {
            perror("waitpid");
            return 1;
        }
        int32_t exitCode = WIFSIGNALED(status) ? WTERMSIG(status) + 128 : WEXITSTATUS(status);
        fullWrite(data->commFd, &exitCode, sizeof(exitCode));
        fullWrite(data->commFd, &status, sizeof(status));
        close(data->commFd);
        if (epoll_ctl(epoll_fd, EPOLL_CTL_DEL, data->fd, nullptr) == -1) {
            perror("epoll_ctl(DEL, data->fd)");
            return 1;
        }
        close(data->fd);
    }
}
