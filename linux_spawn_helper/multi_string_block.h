#ifndef SH_MULTI_STRING_BLOCK_H
#define SH_MULTI_STRING_BLOCK_H 1

#include <string.h>

#define COUNT_STRING_ARRAY(arr)                    \
({                                                  \
    size_t count = 0;                               \
    for(const char *p = *arr; p!=NULL; p++) count++;  \
    count;                                          \
})

#define MUSZ_BP_COUNT(name) name##_musz_count
#define MUSZ_BP_ILENS(name) name##_musz_item_lens
#define MUSZ_BP_TLEN(name) name##_musz_total_len

#define MUSZ_BUILD(buf, name) \
    { \
    char *ptr = buf; \
    for(size_t i = 0; i < MUSZ_BP_COUNT(name); i++) { \
        memcpy(ptr, name[i], MUSZ_BP_ILENS(name)[i]+1); \
        ptr += MUSZ_BP_ILENS(name)[i]+1; \
    } \
    ptr[0] = '\0'; \
    }

#define MUSZ_BUILD_PREPARE(name) \
    size_t MUSZ_BP_COUNT(name) = COUNT_STRING_ARRAY(name); \
    size_t MUSZ_BP_ILENS(name)[MUSZ_BP_COUNT(name)]; \
    size_t MUSZ_BP_TLEN(name) = 0; \
    for (size_t i = 0; i < MUSZ_BP_COUNT(name); i++) { \
        const size_t len = strlen(name[i]); \
        MUSZ_BP_ILENS(name)[i] = len; \
        MUSZ_BP_TLEN(name) += len + sizeof(char); \
    } \
    MUSZ_BP_TLEN(name) += 1;



void aa(char **input1) {
    MUSZ_BUILD_PREPARE(input1);
    char buf[MUSZ_BP_TLEN(input1)];
    MUSZ_BUILD(buf, input1);
}



#endif
