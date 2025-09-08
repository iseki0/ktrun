cmake_minimum_required(VERSION 3.31)
project(linux_spawn_helper)
set(CMAKE_C_STANDARD 17)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_VERBOSE_MAKEFILE ON)


add_executable(linux_spawn_helper
        main.c
        spawn_helper.c
        util.c
        mblock.c
        require_not_null.h
        require_not_null.c)

include(FetchContent)
FetchContent_Declare(
        googletest
        URL https://github.com/google/googletest/archive/03597a01ee50ed33e9dfd640b249b4be3799d395.zip
)

# For Windows: Prevent overriding the parent project's compiler/linker settings
#set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

enable_testing()
#

add_executable(
        hello_test
        spawn_helper.c
        util.c
        hello_test.cpp
        mblock.h
        mblock.c
        require_not_null.h
        require_not_null.c
        mblock_gtest.cc
        spawn_process_gtest.cpp
)

target_link_libraries(
        hello_test
        GTest::gtest_main
)

include(GoogleTest)
gtest_discover_tests(hello_test)
