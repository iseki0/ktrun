#ifndef LINUX_SPAWN_HELPER_REQUIRE_NOT_NULL_H
#define LINUX_SPAWN_HELPER_REQUIRE_NOT_NULL_H

#ifndef __FILE_NAME__
#define __FILE_NAME__ "__FILE_NAME__"
#endif

#include <unistd.h>
#define REQUIRE_NOT_NULL(expr) ({ \
    __typeof__(expr) v = (expr);\
    if(v == NULL) { doPanic(#expr, " == NULL", __FILE_NAME__, __LINE__); _exit(1);}\
    (v); \
})
#define REQUIRE_NULL(expr) if((expr) != NULL) { doPanic(#expr, " != NULL", __FILE_NAME__, __LINE__); _exit(1);}

#ifdef __cplusplus
extern "C" {

#endif

void doPanic(const char *msg, const char *msg2, const char *file, int line);

#ifdef __cplusplus
}
#endif


#endif //LINUX_SPAWN_HELPER_REQUIRE_NOT_NULL_H
