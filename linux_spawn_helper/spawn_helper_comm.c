#include "spawn_helper_comm.h"

#include <errno.h>
#include <stdlib.h>
#include <string.h>


#include "mblock.h"
#include "require_not_null.h"

#define CHECK_ERR(cond) if(cond == -1 ) goto err;

void doFreeStringIfNotNull(char **input) {
    REQUIRE_NOT_NULL(input);
    char *str = *input;
    if (str == NULL) return;
    free(str);
    *input = NULL;
}

void doFreeStringArrayIfNotNull(char ***input) {
    REQUIRE_NOT_NULL(input);
    char **arr = *input;
    if (arr == NULL) return;
    for (size_t i = 0; arr[i] != NULL; i++) {
        free(arr[i]);
    }
    free(arr);
    *input = NULL;
}

int doAllocStringArray(char ***target, const size_t n) {
    REQUIRE_NOT_NULL(target);
    REQUIRE_NULL(*target);
    *target = (char **) malloc((n + 1) * sizeof(char *));
    if (*target == NULL) {
        errno = ENOMEM;
        return -1;
    }
    memset(*target, 0, (n + 1) * sizeof(char *));
    return 0;
}

struct SpawnProcessOptionPersistentHeader {
    size_t argvNumber;
    size_t envpNumber;
    bool envpSet;
    bool cwdSet;
};

int SpawnProcessOption_parse(struct SpawnProcessOption *option, char *buf) {
    REQUIRE_NOT_NULL(option);
    REQUIRE_NULL(option->argv);
    REQUIRE_NULL(option->envp);
    REQUIRE_NULL(option->file);
    REQUIRE_NULL(option->cwd);
    errno = 0;
    struct SpawnProcessOptionPersistentHeader header;
    memcpy(&header, buf, sizeof(header));
    buf += sizeof(header);
    CHECK_ERR(MBlock_GetCString(buf, &option->file, &buf));
    CHECK_ERR(MBlock_GetCString(buf, &option->cwd, &buf));
    CHECK_ERR(doAllocStringArray(&option->argv, header.argvNumber));
    for (size_t i = 0; i < header.argvNumber; i++) {
        CHECK_ERR(MBlock_GetCString(buf, &option->argv[i], &buf));
    }
    CHECK_ERR(doAllocStringArray(&option->envp, header.envpNumber));
    for (size_t i = 0; i < header.envpNumber; i++) {
        CHECK_ERR(MBlock_GetCString(buf, &option->envp[i], &buf));
    }
    option->envpSet = header.envpSet;
    option->cwdSet = header.cwdSet;
    return 0;
err:;
    const int e = errno;
    doFreeStringArrayIfNotNull(&option->argv);
    doFreeStringArrayIfNotNull(&option->envp);
    doFreeStringIfNotNull(&option->file);
    doFreeStringIfNotNull(&option->cwd);
    errno = e;
    return -1;
}

void SpawnProcessOption_free(struct SpawnProcessOption *option) {
    REQUIRE_NOT_NULL(option);
    doFreeStringArrayIfNotNull(&option->argv);
    doFreeStringArrayIfNotNull(&option->envp);
    doFreeStringIfNotNull(&option->file);
    doFreeStringIfNotNull(&option->cwd);
}

size_t SpawnProcessOption_bytesSize(const struct SpawnProcessOption *option) {
    size_t size = sizeof(struct SpawnProcessOptionPersistentHeader);
    size += MBlock_SizeOfCString(option->file);
    size += MBlock_SizeOfCString(option->cwd == NULL ? "" : option->cwd);
    for (size_t i = 0; option->argv != NULL && option->argv[i] != NULL; i++) {
        size += MBlock_SizeOfCString(option->argv[i]);
    }
    for (size_t i = 0; option->envp != NULL && option->envp[i] != NULL; i++) {
        size += MBlock_SizeOfCString(option->envp[i]);
    }
    return size;
}

void SpawnProcessOption_bytes(const struct SpawnProcessOption *option, char *buf) {
    struct SpawnProcessOptionPersistentHeader header;
    header.argvNumber = 0;
    if (option->argv != NULL) {
        for (size_t i = 0; option->argv[i] != NULL; i++) {
            header.argvNumber++;
        }
    }
    header.envpNumber = 0;
    if (option->envp != NULL) {
        for (size_t i = 0; option->envp[i] != NULL; i++) {
            header.envpNumber++;
        }
    }
    header.cwdSet = option->cwdSet;
    header.envpSet = option->envpSet;
    memcpy(buf, &header, sizeof(header));
    buf += sizeof(header);
    MBlock_PutCString(buf, option->file, &buf);
    MBlock_PutCString(buf, option->cwd == NULL ? "" : option->cwd, &buf);
    for (size_t i = 0; option->argv != NULL && option->argv[i] != NULL; i++) {
        MBlock_PutCString(buf, option->argv[i], &buf);
    }
    for (size_t i = 0; option->envp != NULL && option->envp[i] != NULL; i++) {
        MBlock_PutCString(buf, option->envp[i], &buf);
    }
}
