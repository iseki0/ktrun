#ifndef SPAWN_HELPER_COMM_H
#define SPAWN_HELPER_COMM_H 1

#include <stddef.h>

#define COMM_FD_UDS 39

#define RES_MASK  (0x12345600)
#define RES_COMPLETED  (RES_MASK | 2)
#define RES_EXITED  (RES_MASK | 3)

#define REQ_FD_LEN 6
#define REQ_CHILD_FD0_IDX 0
#define REQ_CHILD_FD1_IDX 1
#define REQ_CHILD_FD2_IDX 2
#define REQ_MEM_FD_IDX 3
#define REQ_CHILD_ERR_IDX 4
#define REQ_CLONE_MSG_IDX 5
#define REQ_CMSG_SIZE CMSG_SPACE(sizeof(int) * REQ_FD_LEN)

#ifdef __cplusplus
extern "C" {
#endif

#define PREFIXED(name) space_iseki_spawnhelper_##name
#define SpawnProcessOption PREFIXED(SpawnProcessOption)
struct SpawnProcessOption {
    char *file;
    char *cwd;
    char **argv;
    char **envp;
};

#define SpawnProcessOption_parse PREFIXED(SpawnProcessOption_parse)
int SpawnProcessOption_parse(struct SpawnProcessOption *option, char *buf);

#define SpawnProcessOption_free PREFIXED(SpawnProcessOption_free)
void SpawnProcessOption_free(struct SpawnProcessOption *option);

#define SpawnProcessOption_bytesSize PREFIXED(SpawnProcessOption_bytesSize)
size_t SpawnProcessOption_bytesSize(const struct SpawnProcessOption *option);

#define SpawnProcessOption_bytes PREFIXED(SpawnProcessOption_bytes)
void SpawnProcessOption_bytes(const struct SpawnProcessOption *option, char *buf);

struct ProcessExitMsg {
    int pid;
    int exitCode;
};


struct ProcessCloneResult {
    int pid;
    int _errno;
};

#ifdef __cplusplus
}
#endif

#endif

