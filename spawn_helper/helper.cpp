#include "helper.hpp"

#include <cerrno>
#include <cstring>
#include <exception>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <iostream>
#include <memory>
#include <csignal>
#include <sys/stat.h>
#include <spawn.h>
#include "helper_comm.hpp"



namespace {
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

    void fullWriteOrThrow(const int fd, const void *buf, const size_t size) {
        if (!fullWrite(fd, buf, size))
            throw std::system_error(errno, std::system_category());
    }


    struct NonCopyable {
        NonCopyable() = default;

        ~NonCopyable() = default;

        NonCopyable(const NonCopyable &) = delete;

        NonCopyable &operator=(const NonCopyable &) = delete;
    };

    class FdHolder : NonCopyable {
        int _fd;

    public:
        int fd() const {
            return _fd;
        }

        explicit FdHolder(const int fd) {
            _fd = fd;
        }

        void ftruncate(const size_t size) const {
            if (::ftruncate(_fd, size) == -1) {
                throw std::system_error(errno, std::system_category(), "ftruncate");
            }
        }

        virtual ~FdHolder() {
            if (close(fd()) != -1) {
                std::cerr << "close failed: " << errno << std::endl;
                abort();
            }
        }
    };

    class Memfd final : public FdHolder {
        static int c(const char *debugName) {
            const auto fd = memfd_create(debugName, MFD_CLOEXEC);
            if (fd == -1) {
                throw std::system_error(errno, std::system_category(), "memfd_create");
            }
            if (fchmod(fd, 0700) == -1) {
                close(fd);
                throw std::system_error(errno, std::system_category(), "fchmod");
            }
            return fd;
        }

    public:
        explicit Memfd(const char *debugName) : FdHolder(c(debugName)) {
        }
    };

    class Shmfd final : public FdHolder {
        static int c(const char *filename) {
            const auto fd = shm_open(filename, O_CREAT | O_RDWR | O_CLOEXEC, 0700);
            if (fd == -1) {
                throw std::system_error(errno, std::system_category(), "shm_open");
            }
            if (shm_unlink(filename) == -1) {
                close(fd);
                throw std::system_error(errno, std::system_category(), "shm_unlink");
            }
            return fd;
        }

    public:
        explicit Shmfd(const char *filename) : FdHolder(c(filename)) {
        }
    };

    class TmpFile final : public FdHolder {
        struct H {
            std::unique_ptr<char[]> filename;
            int fd;
        };

        static H c(const char *pattern) {
            auto filename = std::make_unique<char[]>(strlen(pattern) + 1);
            strcpy(filename.get(), pattern);
            const auto fd = mkstemp(filename.get());
            if (fd == -1) {
                throw std::system_error(errno, std::system_category(), "mkstemp");
            }
            try {
                if (unlink(filename.get()) == -1) {
                    throw std::system_error(errno, std::system_category(), "unlink");
                }
                if (fchmod(fd, 0700) == -1) {
                    throw std::system_error(errno, std::system_category(), "fchmod");
                }
            } catch (const std::exception &e) {
                close(fd);
                throw;
            }
            return {std::move(filename), fd};
        }

        std::unique_ptr<char[]> filename;

        explicit TmpFile(H h) : FdHolder(h.fd), filename(std::move(h.filename)) {
        }

    public:
        explicit TmpFile(const char *pattern) : TmpFile(c(pattern)) {
        }
    };
}

int helper_launch_helper_process() {
    try {
        std::unique_ptr<FdHolder> holder;
        try {
            holder = std::make_unique<Memfd>("spawnhelper_process");
        } catch (const std::system_error &e) {
            if (e.code().value() != ENOSYS) throw;
            holder = std::make_unique<TmpFile>("/tmp/spawnhelperXXXXXX");
        }
        const auto fsize = _binary_spawnhelper_process_end - _binary_spawnhelper_process_start;
        holder->ftruncate(fsize);
        if (!fullWrite(holder->fd(), _binary_spawnhelper_process_start, fsize)) {
            throw std::system_error(errno, std::system_category());
        }
        int sv[2] = {-1, -1};
        if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, sv) == -1) {
            throw std::system_error(errno, std::system_category(), "socketpair");
        }

        constexpr char *const argv[] = {const_cast<char *>("foo"), nullptr};
        char *const *envp = environ;

        // prepare mask
        sigset_t all = {};
        sigset_t oldmask = {};
        if (sigfillset(&all) == -1) {
            perror("sigfillset");
            exit(EXIT_FAILURE);
        }
        if (sigprocmask(SIG_SETMASK, &all, &oldmask) == -1) {
            perror("sigprocmask");
            exit(EXIT_FAILURE);
        }

        // fork
        const auto pid = fork();
        if (pid == -1) {
            throw std::system_error(errno, std::system_category(), "fork");
        }
        if (pid == 0) {
            // child process
            // Reset all signal handlers to be ignored, mimicking the original code's logic.
            // This prevents the child from inheriting unintended signal handlers.
            struct sigaction sa = {};
            sa.sa_handler = SIG_DFL;
            for (int i = 1; i < NSIG; i++) {
                // SIGKILL and SIGSTOP cannot be caught or ignored; attempting to set a handler for them will fail.
                // We skip them to be explicit.
                if (i == SIGKILL || i == SIGSTOP) continue;
                // ignore sigaction error, not only for SIGKILL and SIGSTOP.
                // https://bugzilla.redhat.com/show_bug.cgi?id=53394
                sigaction(i, &sa, nullptr);
            }
            sigset_t empty = {0};
            if (sigemptyset(&empty) == -1) {
                perror("sigemptyset");
                exit(EXIT_FAILURE);
            }
            if (sigprocmask(SIG_SETMASK, &empty, nullptr) == -1) {
                perror("sigprocmask");
                exit(EXIT_FAILURE);
            }
            if (dup2(sv[1], COMM_FD_UDS) == -1) {
                perror("dup2");
                exit(EXIT_FAILURE);
            }
            fexecve(holder->fd(), argv, envp);
            perror("fexecve");
            _exit(EXIT_FAILURE);
        }
        if (sigprocmask(SIG_SETMASK, &oldmask, nullptr) == -1) {
            perror("sigprocmask");
            exit(EXIT_FAILURE);
        }
        close(sv[1]);
        return sv[0];
    } catch (const std::system_error &e) {
        errno = e.code().value();
        return -1;
    }
}


static int helper_launch_process0(int socket, char *shmName, int childStdin, int childStdout, int childStderr,
                                  int childErrFd,
                                  int statusFd, char *file, char *chdir, char **argv, char **envp) {
    HelperCommHeader header = {
        .chdir = chdir != nullptr,
        .argc = 0,
        .envpc = 0
    };
    for (const char *c = *argv; c != nullptr; c++) {
        header.argc++;
    }
    for (const char *c = *envp; c != nullptr; c++) {
        header.envpc++;
    }
    // measure size
    size_t size = sizeof(HelperCommHeader);
    size += strlen(file) + 1;
    if (chdir != nullptr) {
        size += strlen(chdir) + 1;
    }
    for (int i = 0; i < header.argc; i++) {
        size += strlen(argv[i]) + 1;
    }
    for (int i = 0; i < header.envpc; i++) {
        size += strlen(envp[i]) + 1;
    }
    // allocate shm
    std::unique_ptr<FdHolder> shmHolder;
    try {
        shmHolder = std::make_unique<Memfd>(shmName);
    } catch (const std::system_error &e) {
        if (e.code().value() != ENOSYS) throw;
        shmHolder = std::make_unique<Shmfd>(shmName);
    }
    shmHolder->ftruncate(size);
    // write data
    fullWriteOrThrow(shmHolder->fd(), &header, sizeof(header));
    fullWriteOrThrow(shmHolder->fd(), file, strlen(file) + 1);
    if (chdir != nullptr) {
        fullWriteOrThrow(shmHolder->fd(), chdir, strlen(chdir) + 1);
    }
    for (int i = 0; i < header.argc; i++) {
        fullWriteOrThrow(shmHolder->fd(), argv[i], strlen(argv[i]) + 1);
    }
    for (int i = 0; i < header.envpc; i++) {
        fullWriteOrThrow(shmHolder->fd(), envp[i], strlen(envp[i]) + 1);
    }
    // send fds
    char dummy = 0;
    int fds[6];
    fds[COMM_STDIN] = childStdin;
    fds[COMM_STDOUT] = childStdout;
    fds[COMM_STDERR] = childStderr;
    fds[COMM_CHILD_ERR_REPORT] = childErrFd;
    fds[COMM_STATUS_REPORT] = statusFd;
    fds[COMM_REQ_MEMFD] = shmHolder->fd();
    iovec iov = {
        .iov_base = &dummy,
        .iov_len = sizeof(dummy),
    };
    char ctlMsgBuf[CMSG_SPACE(sizeof(int) * COMM_FD_COUNT)] = {};
    msghdr msgHeader = {
        .msg_iov = &iov,
        .msg_iovlen = 1,
        .msg_control = ctlMsgBuf,
        .msg_controllen = sizeof(ctlMsgBuf),
    };
    cmsghdr *ctlMsgHdr = CMSG_FIRSTHDR(&msgHeader);
    if (ctlMsgHdr == nullptr) {
        perror("CMSG_FIRSTHDR");
        exit(1);
    }
    ctlMsgHdr->cmsg_level = SOL_SOCKET;
    ctlMsgHdr->cmsg_type = SCM_RIGHTS;
    ctlMsgHdr->cmsg_len = CMSG_LEN(sizeof(fds));
    memcpy(CMSG_DATA(ctlMsgHdr), fds, sizeof(fds));
    if (sendmsg(socket, &msgHeader, 0) == -1) {
        return -1;
    }
    return 0;
}

int helper_launch_process(int socket, char *shmName, int childStdin, int childStdout, int childStderr, int childErrFd,
                          int statusFd, char *file, char *chdir, char **argv, char **envp) {
    try {
        return helper_launch_process0(socket, shmName, childStdin, childStdout, childStderr, childErrFd, statusFd, file,
                                      chdir,
                                      argv, envp);
    } catch (const std::system_error &e) {
        errno = e.code().value();
        return -1;
    }
}
