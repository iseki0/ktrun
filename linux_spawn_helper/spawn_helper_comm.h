#ifndef SPAWN_HELPER_COMM_H
#define SPAWN_HELPER_COMM_H 1

#include <stdbool.h>
#include <stddef.h>

#define COMM_FD_UDS 6

#define RES_MASK  (0x12345600)
#define RES_COMPLETED  (RES_MASK | 2)
#define RES_EXITED  (RES_MASK | 3)

#define REQ_FD_LEN 5
#define REQ_CHILD_FD0_IDX 0
#define REQ_CHILD_FD1_IDX 1
#define REQ_CHILD_FD2_IDX 2
#define REQ_ERR_FD_IDX 3
#define REQ_MEM_FD_IDX 4
#define REQ_CMSG_SIZE CMSG_SPACE(sizeof(int) * REQ_FD_LEN)

#ifdef __cplusplus
extern "C" {
#endif

struct SpawnProcessOption {
    bool envpSet;
    char *file;
    char **argv;
    char **envp;
};

int SpawnProcessOption_parse(struct SpawnProcessOption *option, char *buf);

void SpawnProcessOption_free(struct SpawnProcessOption *option);

size_t SpawnProcessOption_bytesSize(const struct SpawnProcessOption *option);

void SpawnProcessOption_bytes(const struct SpawnProcessOption *option, char *buf);

struct ResMeg {
    int kind;

    union {
        struct {
            int pid;
            int exitCode;
        } exited;

        struct {
            int errNo;
            long pid;
            char errWhere[256];
            char _;
        } completed;
    };
};

#ifdef __cplusplus
}
#endif

#endif

