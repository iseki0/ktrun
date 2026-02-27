#include "spawn_helper_comm.h"

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void assert_string_array_equals(char *const *expected, char *const *actual) {
    size_t i = 0;
    for (; expected[i] != NULL; i++) {
        assert(actual[i] != NULL);
        assert(strcmp(expected[i], actual[i]) == 0);
    }
    assert(actual[i] == NULL);
}

static void test_round_trip_with_argv_only(void) {
    struct SpawnProcessOption option = {0};
    char *argv[] = {"arg1", "arg2", NULL};
    option.file = "/bin/echo";
    option.argv = argv;

    const size_t size = SpawnProcessOption_bytesSize(&option);
    assert(size > 0);

    char *buffer = (char *) calloc(size, 1);
    assert(buffer != NULL);
    SpawnProcessOption_bytes(&option, buffer);

    struct SpawnProcessOption parsed = {0};
    assert(SpawnProcessOption_parse(&parsed, buffer) == 0);
    assert(strcmp(parsed.file, option.file) == 0);
    assert(parsed.cwd == NULL);
    assert(parsed.envp == NULL);
    assert_string_array_equals(option.argv, parsed.argv);

    SpawnProcessOption_free(&parsed);
    free(buffer);
}

static void test_round_trip_with_cwd_and_envp(void) {
    struct SpawnProcessOption option = {0};
    char *argv[] = {"sh", "-c", "echo ok", NULL};
    char *envp[] = {"HOME=/tmp", "LC_ALL=C", NULL};
    option.file = "/bin/sh";
    option.cwd = "/tmp";
    option.argv = argv;
    option.envp = envp;

    const size_t size = SpawnProcessOption_bytesSize(&option);
    char *buffer = (char *) calloc(size, 1);
    assert(buffer != NULL);
    SpawnProcessOption_bytes(&option, buffer);

    struct SpawnProcessOption parsed = {0};
    assert(SpawnProcessOption_parse(&parsed, buffer) == 0);
    assert(strcmp(parsed.file, option.file) == 0);
    assert(strcmp(parsed.cwd, option.cwd) == 0);
    assert_string_array_equals(option.argv, parsed.argv);
    assert_string_array_equals(option.envp, parsed.envp);

    SpawnProcessOption_free(&parsed);
    free(buffer);
}

static void test_size_matches_exact_buffer_use(void) {
    struct SpawnProcessOption option = {0};
    char *argv[] = {"cmd", NULL};
    option.file = "cmd";
    option.argv = argv;

    const size_t size = SpawnProcessOption_bytesSize(&option);
    char *buffer = (char *) malloc(size);
    assert(buffer != NULL);

    SpawnProcessOption_bytes(&option, buffer);

    struct SpawnProcessOption parsed = {0};
    assert(SpawnProcessOption_parse(&parsed, buffer) == 0);
    assert(strcmp(parsed.file, option.file) == 0);
    assert_string_array_equals(option.argv, parsed.argv);

    SpawnProcessOption_free(&parsed);
    free(buffer);
}

int main(void) {
    test_round_trip_with_argv_only();
    test_round_trip_with_cwd_and_envp();
    test_size_matches_exact_buffer_use();
    puts("spawn_process_comm_test: all tests passed");
    return 0;
}
