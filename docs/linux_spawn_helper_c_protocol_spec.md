# Linux Spawn Helper (C) Protocol Spec

This document describes only the C implementation under `linux_spawn_helper/`.
It does not describe the C++ implementation under `spawn_helper/`.

## 1. Components

- Client: Kotlin code in `src/linuxMain/.../DefaultSpawnHelperInitOps.kt` creates/starts helper, then calls C `sendSpawnRequest()` in `spawn_helper.c`.
- Helper: executable from `linux_spawn_helper/main.c` (started via Kotlin `posix_spawn` path).
- Child: target process created by helper using `clone3` (preferred) or `fork` (fallback), then `execvp/execvpe`.

## 2. Startup Protocol

### 2.1 Embedded binary loading (Kotlin)

- Kotlin copies `_binary_linux_spawn_helper_bin_start..end` into an executable fd.
- Preferred path: memfd (`/proc/self/fd/<fd>` execution).
- Fallback path: executable temp file (`mkstemp + fchmod + unlink on cleanup`).

### 2.2 Helper process start (Kotlin)

- Kotlin creates `socketpair(AF_UNIX, SOCK_SEQPACKET|SOCK_CLOEXEC)`.
- Kotlin `posix_spawn` starts helper executable and `dup2`s helper socket to `COMM_FD_UDS`.
- The client then uses the parent side of that socket (`helperFd`) for request/exit protocol.

## 3. Request Protocol (`sendSpawnRequest`)

## 3.1 Transport

- Channel: `helperFd` (the parent side of Kotlin-created helper socketpair).
- Message type: Unix socket message with `SCM_RIGHTS`.
- A 1-byte dummy payload is required in `iovec`.

### 3.2 FD array (strict order)

Defined in `spawn_helper_comm.h`:

- `REQ_CHILD_FD0_IDX` (0): child stdin fd
- `REQ_CHILD_FD1_IDX` (1): child stdout fd
- `REQ_CHILD_FD2_IDX` (2): child stderr fd
- `REQ_MEM_FD_IDX` (3): request data memfd
- `REQ_CHILD_ERR_IDX` (4): child pre-exec error pipe write end
- `REQ_CLONE_MSG_IDX` (5): clone result pipe write end

Total count:
- `REQ_FD_LEN = 6`
- expected control size: `REQ_CMSG_SIZE = CMSG_SPACE(sizeof(int) * REQ_FD_LEN)`

### 3.3 Serialized request body in memfd

Body is produced by:
- `SpawnProcessOption_bytesSize`
- `SpawnProcessOption_bytes`

Logical fields:
- `file`
- optional `cwd`
- `argv[]` (null-terminated array)
- optional `envp[]` (null-terminated array)

Persistent layout starts with:
- `size_t argvNumber`
- `size_t envpNumber`
- `bool envpSet`
- `bool cwdSet`

Then c-strings in order:
`file`, optional `cwd`, `argv entries`, optional `envp entries`.

Important: this binary format is ABI-dependent (`size_t`, `bool`, struct layout).

## 4. Helper-side handling (`main.c`)

### 4.1 Event loop

Helper creates:
- `EPOLL_FD`
- `WAKEUP_FD` (`eventfd`)
- `SIGCHLD_FD` (`signalfd(SIGCHLD)`)
- monitors `COMM_FD_UDS`, pidfds (when available), and `SIGCHLD_FD`

### 4.2 Receive request

`recvMessage()`:
- calls `recvmsg(COMM_FD_UDS, ..., MSG_CMSG_CLOEXEC)`.
- validates `SCM_RIGHTS`, `cmsg_len == REQ_CMSG_SIZE`, and all received fds are non-zero.
- mmaps `REQ_MEM_FD_IDX`, parses `SpawnProcessOption`.
- dispatches `handleMessage(...)`.

### 4.3 Spawn flow

`handleMessage(...)`:
- preferred spawn path: `clone3` with `CLONE_PIDFD` and `exit_signal = SIGCHLD`.
- fallback spawn path: if `clone3` fails with `ENOSYS` or `EPERM`, helper falls back to `fork()`.
- child branch:
  - `dup2` stdio fds.
  - optional `chdir(cwd)`.
  - `execvpe` if `envp != NULL`, else `execvp`.
  - on failure writes two ints to `REQ_CHILD_ERR_IDX`:
    - errno
    - `chdirFailed` flag (`1` if failure occurred in chdir stage, else `0`)
- parent branch:
  - `clone3` path: adds pidfd to epoll.
  - `fork` fallback path: tracks child pid in an internal list; child exit is reaped from `signalfd(SIGCHLD)` events.
  - writes `ProcessCloneResult { pid, _errno }` to `REQ_CLONE_MSG_IDX`.

## 5. Client-side response handling in `sendSpawnRequest`

After sending request:
- closes local duplicates not needed (`memFd`, write ends).
- reads `ProcessCloneResult` from clone pipe:
  - `_errno != 0` => fail (`errno = _errno`, return `-1`)
- reads child error pipe:
  - 0 bytes => no pre-exec error
  - 8 bytes => two ints: `childErrno`, `chdirFailed`
  - malformed size => `EBADMSG`
- if child error exists => fail (`errno = childErrno`, return `-1`)
- success => returns child pid (`int`).

Output parameter:
- `bool *chdirFailed` is set true only when child reported chdir-stage failure.

## 6. Exit notifications

When child exits, helper sends on `COMM_FD_UDS`:
- `struct ProcessExitMsg { int pid; int exitCode; }`
- `exitCode = WEXITSTATUS` if exited normally, otherwise `-1`.

Note:
- `sendSpawnRequest()` itself does not consume `ProcessExitMsg`.
- upper layer must read this channel if it needs async exit events.

## 7. Error model

Failure sources:
- local syscall failure in client wrapper (`pipe2`, `sendmsg`, `mmap`, ...)
- helper spawn failure (`ProcessCloneResult._errno`) on both `clone3` and fallback `fork` paths
- child pre-exec failure (`childErr` pipe)
- protocol/frame mismatch (`EBADMSG`)

All public C APIs use:
- success: non-negative / valid struct fields
- failure: return `-1` and set `errno`.

## 8. Known constraints

- `COMM_FD_UDS` is hardcoded (`3939`) and must match startup `dup2`.
- request body binary format is ABI-coupled.
- helper exits hard on some protocol violations (`exit(1)`).
- symbols are Linux-specific (`clone3`, pidfd/signalfd behavior, syscall numbers).

## 9. Source map

- API facade: `linux_spawn_helper/spawn_helper.c`
- helper runtime: `linux_spawn_helper/main.c`
- request serialization: `linux_spawn_helper/spawn_helper_comm.c`
- protocol constants: `linux_spawn_helper/spawn_helper_comm.h`
- exported API: `linux_spawn_helper/spawn_helper.h`
- Kotlin startup path: `src/linuxMain/kotlin/space/iseki/ktrun/DefaultSpawnHelperInitOps.kt`
