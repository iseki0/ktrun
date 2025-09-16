cmake_minimum_required(VERSION 3.22)
project(linux_spawn_helper)
set(CMAKE_C_STANDARD 17)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_VERBOSE_MAKEFILE ON)

add_executable(linux_spawn_helper
        main.c
        spawn_helper_comm.c
        util.c
        mblock.c
        require_not_null.c
)

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
        spawn_helper_comm.c
        util.c
        hello_test.cpp
        mblock.c
        require_not_null.c
        mblock_gtest.cc
        spawn_process_comm_gtest.cpp
)

find_program(CMAKE_OBJCOPY NAMES llvm-objcopy objcopy)
if(NOT CMAKE_OBJCOPY)
    message(FATAL_ERROR "objcopy not found (need llvm-objcopy or objcopy)")
endif()
find_program(CMAKE_STRIP NAMES llvm-strip strip)
if(NOT CMAKE_STRIP)
    message(FATAL_ERROR "strip not found (need llvm-strip or strip)")
endif()

set(STRIPPED_BIN ${CMAKE_CURRENT_BINARY_DIR}/linux_spawn_helper_stripped)

# 目标架构（你现在是 linux-musl x86_64，就用这两个；如果换架构再改）
set(EMBED_OUTFMT "elf64-x86-64")
set(EMBED_ARCH   "i386:x86-64")

# 在构建目录里使用“短文件名”以控制符号名
set(EMBED_BASENAME linux_spawn_helper.bin)              # 符号会包含这个名字
set(EMBED_OBJ      ${CMAKE_CURRENT_BINARY_DIR}/linux_spawn_helper.o)

add_custom_command(
        OUTPUT ${EMBED_OBJ}
        # 1) 复制产物到当前工作目录并改名为短文件名
        COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:linux_spawn_helper> ${EMBED_BASENAME}
        # 2) strip（对副本操作，避免改动原 target）
        COMMAND ${CMAKE_STRIP} ${EMBED_BASENAME}
        # 3) objcopy 二进制 -> .o
        COMMAND ${CMAKE_OBJCOPY}
        --input-target=binary
        --output-target=${EMBED_OUTFMT}
        --binary-architecture=${EMBED_ARCH}
        ${EMBED_BASENAME}
        ${EMBED_OBJ}
        # 关键：把工作目录切到 binary dir，这样 objcopy 看到的输入就是相对名 EMBED_BASENAME
        WORKING_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}
        DEPENDS linux_spawn_helper
        COMMENT "Stripping and embedding linux_spawn_helper → ${EMBED_BASENAME}"
        VERBATIM
)


add_library(
        spawn_helper_lib
        spawn_helper.c
        mblock.c
        util.c
        spawn_helper_comm.c
        require_not_null.c
        ${CMAKE_CURRENT_BINARY_DIR}/linux_spawn_helper.o
)
target_compile_options(spawn_helper_lib PUBLIC -fno-sanitize=undefined)

target_link_libraries(
        hello_test
        GTest::gtest_main
)

include(GoogleTest)
#gtest_discover_tests(hello_test)
