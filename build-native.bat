@echo off
pushd src\linuxArm64Main\nativeInterop\cinterop
del fork_and_exec.o
del fork_and_exec.a
zig cc -target aarch64-linux -fno-sanitize=all -c fork_and_exec.c
zig ar rcs fork_and_exec.a fork_and_exec.o
del fork_and_exec.o
popd

pushd src\linuxX64Main\nativeInterop\cinterop
del fork_and_exec.o
del fork_and_exec.a
zig cc -target x86_64-linux -fno-sanitize=all -c fork_and_exec.c
zig ar rcs fork_and_exec.a fork_and_exec.o
del fork_and_exec.o
popd

