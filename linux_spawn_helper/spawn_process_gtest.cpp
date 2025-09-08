#include <gtest/gtest.h>

#include "spawn_helper.h"

TEST(SpawnProcessOptionTest, A) {
    SpawnProcessOption option = {};
    option.file = "foo";
    option.envpSet = false;
    option.argv = (char *[]){"arg1", "arg2", NULL};
    option.envp = (char *[]){NULL};
    const size_t size = SpawnProcessOption_bytesSize(&option);
    std::cout << "SpawnProcessOption_bytesSize: " << size << std::endl;
    void *buf = malloc(size + 64);
    memset(buf, 0xcd, size + 64);
    SpawnProcessOption_bytes(&option, static_cast<char *>(buf));
    void *buf2 = malloc(size + 64);
    memset(buf2, 0xcd, size + 64);
    memcpy(buf2, buf, size);
    SpawnProcessOption option2 = {};
    EXPECT_EQ(0, SpawnProcessOption_parse(&option2, static_cast<char *>(buf2)));
    std::cout << "parsed" << std::endl;
    std::cout << "option2.file: " << option2.file << std::endl;
    EXPECT_STREQ(option.file, option2.file);
    EXPECT_EQ(option.envpSet, option2.envpSet);
    free(buf);
    free(buf2);
}

