#include <gtest/gtest.h>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "spawn_helper_comm.h"

// Helper function to safely free a SpawnProcessOption
void safe_free_option(SpawnProcessOption* option) {
    if (option) {
        SpawnProcessOption_free(option);
    }
}

TEST(SpawnProcessOptionTest, BasicSerializationAndParsing) {
    SpawnProcessOption option = {};
    option.file = const_cast<char*>("foo");
    char* args[] = {const_cast<char*>("arg1"), const_cast<char*>("arg2"), nullptr};
    option.argv = args;
    option.envp = nullptr;
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
    EXPECT_EQ(option.envp, option2.envp);
    safe_free_option(&option2);
    free(buf);
    free(buf2);
}

TEST(SpawnProcessOptionTest, WithEnvironmentVariables) {
    SpawnProcessOption option = {};
    option.file = const_cast<char*>("/bin/sh");
    char* args[] = {const_cast<char*>("sh"), const_cast<char*>("-c"), const_cast<char*>("echo test"), nullptr};
    char* envs[] = {const_cast<char*>("PATH=/usr/bin:/bin"), const_cast<char*>("HOME=/home/user"), nullptr};
    option.argv = args;
    option.envp = envs;
    option.cwd = nullptr;

    const size_t size = SpawnProcessOption_bytesSize(&option);
    std::vector<char> buf(size + 32);
    std::memset(buf.data(), 0xCD, buf.size());
    
    SpawnProcessOption_bytes(&option, buf.data());
    
    SpawnProcessOption parsed = {};
    EXPECT_EQ(0, SpawnProcessOption_parse(&parsed, buf.data()));
    
    // Verify all fields
    EXPECT_STREQ(option.file, parsed.file);
    EXPECT_STREQ(option.argv[0], parsed.argv[0]);
    EXPECT_STREQ(option.argv[1], parsed.argv[1]);
    EXPECT_STREQ(option.argv[2], parsed.argv[2]);
    EXPECT_EQ(parsed.argv[3], nullptr);
    
    EXPECT_STREQ(option.envp[0], parsed.envp[0]);
    EXPECT_STREQ(option.envp[1], parsed.envp[1]);
    EXPECT_EQ(parsed.envp[2], nullptr);
    
    EXPECT_EQ(parsed.cwd, nullptr);
    
    safe_free_option(&parsed);
}

TEST(SpawnProcessOptionTest, WithWorkingDirectory) {
    SpawnProcessOption option = {};
    option.file = const_cast<char*>("/bin/pwd");
    char* args[] = {const_cast<char*>("pwd"), nullptr};
    option.argv = args;
    option.envp = nullptr;
    option.cwd = const_cast<char*>("/tmp");

    const size_t size = SpawnProcessOption_bytesSize(&option);
    std::vector<char> buf(size + 32);
    std::memset(buf.data(), 0xCD, buf.size());
    
    SpawnProcessOption_bytes(&option, buf.data());
    
    SpawnProcessOption parsed = {};
    EXPECT_EQ(0, SpawnProcessOption_parse(&parsed, buf.data()));
    
    EXPECT_STREQ(option.file, parsed.file);
    EXPECT_STREQ(option.argv[0], parsed.argv[0]);
    EXPECT_EQ(parsed.argv[1], nullptr);
    EXPECT_EQ(parsed.envp, nullptr);
    EXPECT_STREQ(option.cwd, parsed.cwd);
    
    safe_free_option(&parsed);
}

TEST(SpawnProcessOptionTest, CompleteConfiguration) {
    SpawnProcessOption option = {};
    option.file = const_cast<char*>("/usr/bin/env");
    char* args[] = {const_cast<char*>("env"), const_cast<char*>("echo"), const_cast<char*>("Hello World"), nullptr};
    char* envs[] = {
        const_cast<char*>("USER=testuser"), 
        const_cast<char*>("SHELL=/bin/bash"),
        const_cast<char*>("TERM=xterm"),
        nullptr
    };
    option.argv = args;
    option.envp = envs;
    option.cwd = const_cast<char*>("/var/log");

    const size_t size = SpawnProcessOption_bytesSize(&option);
    std::vector<char> buf(size + 64);
    std::memset(buf.data(), 0xCD, buf.size());
    
    SpawnProcessOption_bytes(&option, buf.data());
    
    SpawnProcessOption parsed = {};
    EXPECT_EQ(0, SpawnProcessOption_parse(&parsed, buf.data()));
    
    // Verify file
    EXPECT_STREQ(option.file, parsed.file);
    
    // Verify arguments
    for (int i = 0; option.argv[i] != nullptr; i++) {
        EXPECT_STREQ(option.argv[i], parsed.argv[i]) << "Mismatch at argv[" << i << "]";
    }
    EXPECT_EQ(parsed.argv[3], nullptr);
    
    // Verify environment variables
    for (int i = 0; option.envp[i] != nullptr; i++) {
        EXPECT_STREQ(option.envp[i], parsed.envp[i]) << "Mismatch at envp[" << i << "]";
    }
    EXPECT_EQ(parsed.envp[3], nullptr);
    
    // Verify working directory
    EXPECT_STREQ(option.cwd, parsed.cwd);
    
    safe_free_option(&parsed);
}

TEST(SpawnProcessOptionTest, EmptyStringsHandling) {
    SpawnProcessOption option = {};
    option.file = const_cast<char*>("");  // Empty file (should be unusual but handled)
    char* args[] = {const_cast<char*>(""), nullptr};  // Empty argument
    char* envs[] = {const_cast<char*>("EMPTY="), nullptr};  // Empty value
    option.argv = args;
    option.envp = envs;
    option.cwd = const_cast<char*>("");  // Empty working directory

    const size_t size = SpawnProcessOption_bytesSize(&option);
    std::vector<char> buf(size + 32);
    std::memset(buf.data(), 0xCD, buf.size());
    
    SpawnProcessOption_bytes(&option, buf.data());
    
    SpawnProcessOption parsed = {};
    EXPECT_EQ(0, SpawnProcessOption_parse(&parsed, buf.data()));
    
    EXPECT_STREQ("", parsed.file);
    EXPECT_STREQ("", parsed.argv[0]);
    EXPECT_EQ(parsed.argv[1], nullptr);
    EXPECT_STREQ("EMPTY=", parsed.envp[0]);
    EXPECT_EQ(parsed.envp[1], nullptr);
    EXPECT_STREQ("", parsed.cwd);
    
    safe_free_option(&parsed);
}

TEST(SpawnProcessOptionTest, LongStringsHandling) {
    // Test with long strings to verify memory handling
    std::string long_file(1000, 'a');
    std::string long_arg(500, 'b');
    std::string long_env(800, 'c');
    std::string long_cwd(300, 'd');
    
    SpawnProcessOption option = {};
    option.file = const_cast<char*>(long_file.c_str());
    char* args[] = {const_cast<char*>(long_arg.c_str()), nullptr};
    char* envs[] = {const_cast<char*>(long_env.c_str()), nullptr};
    option.argv = args;
    option.envp = envs;
    option.cwd = const_cast<char*>(long_cwd.c_str());

    const size_t size = SpawnProcessOption_bytesSize(&option);
    EXPECT_GT(size, 1000u + 500u + 800u + 300u);  // Should be at least sum of string lengths plus overhead
    
    std::vector<char> buf(size + 64);
    std::memset(buf.data(), 0xCD, buf.size());
    
    SpawnProcessOption_bytes(&option, buf.data());
    
    SpawnProcessOption parsed = {};
    EXPECT_EQ(0, SpawnProcessOption_parse(&parsed, buf.data()));
    
    EXPECT_EQ(long_file, std::string(parsed.file));
    EXPECT_EQ(long_arg, std::string(parsed.argv[0]));
    EXPECT_EQ(parsed.argv[1], nullptr);
    EXPECT_EQ(long_env, std::string(parsed.envp[0]));
    EXPECT_EQ(parsed.envp[1], nullptr);
    EXPECT_EQ(long_cwd, std::string(parsed.cwd));
    
    safe_free_option(&parsed);
}

TEST(SpawnProcessOptionTest, ManyArgumentsAndEnvironmentVars) {
    SpawnProcessOption option = {};
    option.file = const_cast<char*>("/bin/test");
    
    // Create many arguments - keep storage alive through entire test
    std::vector<std::string> arg_storage;
    std::vector<char*> args;
    arg_storage.reserve(20);  // Reserve space to prevent reallocation
    args.reserve(21);         // 20 args + nullptr
    
    for (int i = 0; i < 20; i++) {
        arg_storage.emplace_back("arg" + std::to_string(i));
        args.push_back(const_cast<char*>(arg_storage.back().c_str()));
    }
    args.push_back(nullptr);
    option.argv = args.data();
    
    // Create many environment variables - keep storage alive through entire test
    std::vector<std::string> env_storage;
    std::vector<char*> envs;
    env_storage.reserve(15);  // Reserve space to prevent reallocation
    envs.reserve(16);         // 15 env vars + nullptr
    
    for (int i = 0; i < 15; i++) {
        env_storage.emplace_back("VAR" + std::to_string(i) + "=value" + std::to_string(i));
        envs.push_back(const_cast<char*>(env_storage.back().c_str()));
    }
    envs.push_back(nullptr);
    option.envp = envs.data();
    
    option.cwd = nullptr;

    const size_t size = SpawnProcessOption_bytesSize(&option);
    std::vector<char> buf(size + 64);
    std::memset(buf.data(), 0xCD, buf.size());
    
    SpawnProcessOption_bytes(&option, buf.data());
    
    SpawnProcessOption parsed = {};
    EXPECT_EQ(0, SpawnProcessOption_parse(&parsed, buf.data()));
    
    EXPECT_STREQ(option.file, parsed.file);
    
    // Verify all arguments
    for (int i = 0; i < 20; i++) {
        EXPECT_STREQ(option.argv[i], parsed.argv[i]) << "Mismatch at argv[" << i << "]";
    }
    EXPECT_EQ(parsed.argv[20], nullptr);
    
    // Verify all environment variables
    for (int i = 0; i < 15; i++) {
        EXPECT_STREQ(option.envp[i], parsed.envp[i]) << "Mismatch at envp[" << i << "]";
    }
    EXPECT_EQ(parsed.envp[15], nullptr);
    
    EXPECT_EQ(parsed.cwd, nullptr);
    
    safe_free_option(&parsed);
}

TEST(SpawnProcessOptionTest, SizeCalculationAccuracy) {
    SpawnProcessOption option = {};
    option.file = const_cast<char*>("test");
    char* args[] = {const_cast<char*>("arg1"), const_cast<char*>("arg2"), nullptr};
    char* envs[] = {const_cast<char*>("VAR=val"), nullptr};
    option.argv = args;
    option.envp = envs;
    option.cwd = const_cast<char*>("workdir");

    const size_t calculated_size = SpawnProcessOption_bytesSize(&option);
    
    // Serialize to exact size buffer
    std::vector<char> exact_buf(calculated_size);
    SpawnProcessOption_bytes(&option, exact_buf.data());
    
    // Verify we can parse from exact size
    SpawnProcessOption parsed = {};
    EXPECT_EQ(0, SpawnProcessOption_parse(&parsed, exact_buf.data()));
    
    EXPECT_STREQ(option.file, parsed.file);
    EXPECT_STREQ(option.argv[0], parsed.argv[0]);
    EXPECT_STREQ(option.argv[1], parsed.argv[1]);
    EXPECT_EQ(parsed.argv[2], nullptr);
    EXPECT_STREQ(option.envp[0], parsed.envp[0]);
    EXPECT_EQ(parsed.envp[1], nullptr);
    EXPECT_STREQ(option.cwd, parsed.cwd);
    
    safe_free_option(&parsed);
}

