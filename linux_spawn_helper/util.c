#include <fcntl.h>
#include <sys/epoll.h>
#include <unistd.h>
#include <errno.h>
#include "util.h"


int clearCloexec(const int fd) {
    const int flags = fcntl(fd, F_GETFD);
    if (flags == -1) {
        return -1;
    }
    if (!(flags & FD_CLOEXEC)) return 0;
    if (fcntl(fd, F_SETFD, flags & ~FD_CLOEXEC) == -1) return -1;
    return 0;
}

int setCloexec(const int fd) {
    const int flags = fcntl(fd, F_GETFD);
    if (flags == -1) {
        return -1;
    }
    if (flags & FD_CLOEXEC) return 0;
    if (fcntl(fd, F_SETFD, flags | FD_CLOEXEC) == -1) return -1;
    return 0;
}

int setNonBlock(const int fd) {
    const int flags = fcntl(fd, F_GETFL);
    if (flags == -1) {
        return -1;
    }
    if (flags & O_NONBLOCK) return 0;
    if (fcntl(fd, F_SETFL, flags | O_NONBLOCK) == -1) return -1;
    return 0;
}

int clearNonBlock(const int fd) {
    const int flags = fcntl(fd, F_GETFL);
    if (flags == -1) {
        return -1;
    }
    if (!(flags & O_NONBLOCK)) return 0;
    if (fcntl(fd, F_SETFL, flags & ~O_NONBLOCK) == -1) return -1;
    return 0;
}

int addToEpoll(const int epollFd, const int fd, const pid_t pid, const uint32_t events) {
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

ssize_t fullWrite(const int fd, const void *buf, const size_t count) {
    ssize_t totalWritten = 0;
    while (totalWritten < count) {
        const ssize_t written = write(fd, (const char *) buf + totalWritten, count - totalWritten);
        if (written < 0) {
            return -1; // Error occurred
        }
        totalWritten += written;
    }
    return totalWritten; // Return total bytes written
}

void fullWriteOrExit(const int fd, const void *buf, const size_t count) {
    ssize_t totalWritten = 0;
    while (totalWritten < count) {
        const ssize_t written = write(fd, (const char *) buf + totalWritten, count - totalWritten);
        if (written < 0) {
            _exit(errno);
        }
        totalWritten += written;
    }
}

int readFull(const int fd, void *buf, const int count) {
    int totalRead = 0;
    while (totalRead < count) {
        const int r = read(fd, (char *) buf + totalRead, count - totalRead);
        if (r < 0) {
            if (errno == EINTR) {
                continue; // Interrupted, try again
            }
            return -1; // Error occurred
        }
        if (r == 0) {
            break; // EOF reached
        }
        totalRead += r;
    }
    return totalRead; // Return total bytes read
}
