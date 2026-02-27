cmake_minimum_required(VERSION 3.22)
project(spawn_helper)
set(CMAKE_C_STANDARD 17)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_LINK_DEPENDS_USE_LINKER FALSE)

add_executable(spawnhelper_process main.cpp)
#target_link_options(spawnhelper_process PRIVATE -s -stdlib=libstdc++)

add_library(spawnhelper STATIC spawnhelper_process.o)
set_target_properties(spawnhelper PROPERTIES LINKER_LANGUAGE C)

#target_compile_options(spawnhelper PUBLIC -fno-sanitize=undefined)

#add_executable(test_run test.cpp)
#target_link_libraries(test_run PRIVATE spawnhelper)


find_program(CMAKE_OBJCOPY NAMES llvm-objcopy objcopy)
if(NOT CMAKE_OBJCOPY)
    message(FATAL_ERROR "objcopy not found (need llvm-objcopy or objcopy)")
endif()

set(compiler_target "${CMAKE_C_COMPILER_TARGET}")
if(NOT compiler_target)
    set(compiler_target "${CMAKE_SYSTEM_PROCESSOR}-${CMAKE_SYSTEM_NAME}")
endif()
set(OBJCOPY_OUTPUT_FORMAT "")
if(compiler_target MATCHES "x86_64.*linux.*" OR compiler_target MATCHES "x86_64-Linux*")
    set(OBJCOPY_OUTPUT_FORMAT "elf64-x86-64")
elseif(compiler_target MATCHES "i.86.*linux.*")
    set(OBJCOPY_OUTPUT_FORMAT "elf32-i386")
elseif(compiler_target MATCHES "aarch64.*linux.*")
    set(OBJCOPY_OUTPUT_FORMAT "elf64-littleaarch64")
elseif(compiler_target MATCHES "arm.*linux.*")
    set(OBJCOPY_OUTPUT_FORMAT "elf32-littlearm")
elseif(compiler_target MATCHES "riscv64.*linux.*")
    set(OBJCOPY_OUTPUT_FORMAT "elf64-littleriscv")
elseif(compiler_target MATCHES "x86_64.*mingw.*")
    set(OBJCOPY_OUTPUT_FORMAT "pe-x86-64")
elseif(compiler_target MATCHES "i.86.*mingw.*")
    set(OBJCOPY_OUTPUT_FORMAT "pe-i386")
else()
    message(FATAL_ERROR "Unknown objcopy output format for target: ${compiler_target}")
endif()
message(STATUS "objcopy output format = ${OBJCOPY_OUTPUT_FORMAT}")

set(OBJCOPY_BINARY_ARCH "")
if(compiler_target MATCHES "x86_64.*")
    set(OBJCOPY_BINARY_ARCH "i386:x86-64")
elseif(compiler_target MATCHES "i.86.*")
    set(OBJCOPY_BINARY_ARCH "i386")
elseif(compiler_target MATCHES "aarch64.*")
    set(OBJCOPY_BINARY_ARCH "aarch64")
elseif(compiler_target MATCHES "arm.*")
    set(OBJCOPY_BINARY_ARCH "arm")
elseif(compiler_target MATCHES "riscv64.*")
    set(OBJCOPY_BINARY_ARCH "riscv:rv64")
elseif(compiler_target MATCHES "riscv32.*")
    set(OBJCOPY_BINARY_ARCH "riscv:rv32")
else()
    message(WARNING "Unknown binary arch for ${compiler_target}, defaulting to i386:x86-64")
    set(OBJCOPY_BINARY_ARCH "i386:x86-64")
endif()
message(STATUS "objcopy --binary-architecture = ${OBJCOPY_BINARY_ARCH}")


add_custom_command(
        OUTPUT spawnhelper_process.o
        WORKING_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}
        COMMAND ${CMAKE_OBJCOPY}
        --input-target=binary
        --output-target=${OBJCOPY_OUTPUT_FORMAT}
        --binary-architecture=${OBJCOPY_BINARY_ARCH}
        spawnhelper_process
        spawnhelper_process.o
        DEPENDS spawnhelper_process
)

