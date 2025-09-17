#include <fcntl.h>
#include <sys/epoll.h>
#include <unistd.h>
#include <errno.h>
#include "util.h"

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
