# Linux Spawn Helper C 协议规格书

本文档只覆盖 `linux_spawn_helper` 目录下的 **C 实现**，不包含 `spawn_helper/*.cpp` 路径。

## 1. 范围与参与方

- Client：调用 `initHelper` / `startHelper` / `sendSpawnRequest` 的宿主进程（通常是上层运行时）。
- Helper：`linux_spawn_helper/main.c` 生成的 helper 进程（通过 `fexecve` 启动）。
- Child：helper 通过 `clone3 + execvp/execvpe` 拉起的目标业务进程。

协议目标：
- 让 Client 把一次“拉起子进程”请求发送给 Helper。
- Helper 在本地完成 `clone3`、stdio 重定向、`chdir`、`exec*`。
- 把创建结果和错误反馈回 Client。

## 2. 通道模型

### 2.1 固定通信 FD

- `COMM_FD_UDS = 3939`（`spawn_helper_comm.h`）
- 含义：Helper 启动后在该 FD 上收发 Unix Domain Socket `SOCK_SEQPACKET` 消息。

要求：
- 父进程在 `startHelper` 中用 `dup2(sv[1], COMM_FD_UDS)` 注入该 FD。
- Helper 内会将该 FD 设为 `O_NONBLOCK` + `FD_CLOEXEC` 并注册 epoll。

### 2.2 每次请求的临时 FD

每个 spawn 请求都会通过 `SCM_RIGHTS` 发送 6 个 FD（顺序强约束）：

- `REQ_CHILD_FD0_IDX=0`：Child `stdin` 来源。
- `REQ_CHILD_FD1_IDX=1`：Child `stdout` 目标。
- `REQ_CHILD_FD2_IDX=2`：Child `stderr` 目标。
- `REQ_MEM_FD_IDX=3`：请求参数内存文件（memfd）。
- `REQ_CHILD_ERR_IDX=4`：Child exec 前错误回传管道（写端发给 helper，读端在 client）。
- `REQ_CLONE_MSG_IDX=5`：clone 结果管道（写端发给 helper，读端在 client）。

FD 总数常量：
- `REQ_FD_LEN = 6`
- 控制消息长度：
  `REQ_CMSG_SIZE = CMSG_SPACE(sizeof(int) * REQ_FD_LEN)`

## 3. 消息与数据格式

### 3.1 UDS 请求消息

载荷：
- `msghdr.msg_iov` 含 1 字节 dummy（UDS 必须有正文）。
- `msghdr.msg_control` 为一个 `SCM_RIGHTS`，携带上文 6 个 FD。

校验（Helper 侧）：
- 必须存在 `SCM_RIGHTS`。
- `cmsg_len` 必须等于 `REQ_CMSG_SIZE`。
- 6 个 FD 均非 0，否则视为非法消息并退出。

### 3.2 memfd 参数块格式

由 `SpawnProcessOption_bytes` 写入，由 `SpawnProcessOption_parse` 解析。

格式（顺序布局）：
1. `SpawnProcessOptionPersistentHeader`
2. `file`（C 字符串，含 `\0`）
3. `cwd`（若 `cwdSet=true`，C 字符串，含 `\0`）
4. `argv[0..argvNumber-1]`（每项 C 字符串，含 `\0`）
5. `envp[0..envpNumber-1]`（若 `envpSet=true`，每项 C 字符串，含 `\0`）

头结构（当前实现）：
- `size_t argvNumber`
- `size_t envpNumber`
- `bool envpSet`
- `bool cwdSet`

重要约束：
- 这是 **ABI 相关格式**（含 `size_t` 和 `bool`），并非跨架构稳定二进制协议。
- 需要 Client 和 Helper 使用兼容 ABI（字长、大小端、结构体布局一致）。
- 未携带显式总长度和版本字段，依赖双方同版本代码。

## 4. 时序协议

### 4.1 Helper 启动阶段

1. Client `initHelper`：
   - 将嵌入二进制写入 `binaryMemFd`。
2. Client `startHelper`：
   - 建立 `errFd`、`socketpair`。
   - `fork` 子进程并 `fexecve(binaryMemFd, ...)`。
   - 若 exec 前失败，子进程把 `errno` 写入 `errFd[1]` 并退出。
   - 父进程读 `errFd[0]`：
     - 读到 EOF（0 字节）表示 helper 已成功 exec。
     - 读到 errno 表示启动失败。

### 4.2 Spawn 请求阶段

1. Client `sendSpawnRequest`：
   - 创建 `childErrFd`、`cloneMsgFd` 两条管道。
   - 构建 memfd 参数块。
   - 通过 `sendmsg + SCM_RIGHTS` 发送 6 FD 给 helper。
2. Helper `recvMessage`：
   - 取出 FD，mmap `REQ_MEM_FD_IDX`，解析 `SpawnProcessOption`。
3. Helper `handleMessage`：
   - `clone3(CLONE_PIDFD)`。
   - 子进程路径：
     - `dup2(stdin/stdout/stderr)`。
     - 可选 `chdir(cwd)`。
     - `execvpe`（有 envp）或 `execvp`（无 envp）。
     - 若失败，向 `REQ_CHILD_ERR_IDX` 写：
       - `errno`（int）
       - `chdirFailed`（int，1 表示失败发生在 chdir，0 表示其他阶段）
   - 父进程路径：
     - 将 pidfd 加入 epoll。
     - 向 `REQ_CLONE_MSG_IDX` 写 `ProcessCloneResult{pid,_errno}`。
4. Client 等待两路回执：
   - 先读 `cloneMsgFd[0]`：
     - `_errno != 0`：本次创建失败。
   - 再读 `childErrFd[0]`：
     - 读到 0 字节：exec 前阶段无错误。
     - 读到 8 字节：有错误（errno + chdirFailed）。

### 4.3 退出状态上报（Helper -> Client）

当 child 退出后，helper 通过 `COMM_FD_UDS` 发送普通数据消息：
- 结构：`ProcessExitMsg { int pid; int exitCode; }`
- `exitCode` 来源：`WIFEXITED ? WEXITSTATUS : -1`

注意：
- 该消息与 spawn 请求复用同一 UDS。
- 当前 `sendSpawnRequest`（C API）本身不消费该退出消息，需调用方在上层另行监听 UDS。

## 5. 错误语义

### 5.1 启动 helper 错误

- `startHelper` 返回 `HelperStartResult`：
  - `commFd=-1` 表示失败。
  - `childErrno` 为 child exec helper 失败时的 errno（否则 0）。

### 5.2 spawn 请求错误

`sendSpawnRequest` 返回：
- `>=0`：child pid。
- `-1`：失败，`errno` 表示原因。

额外输出：
- `bool *chdirFailed`：当 child 侧回报错误且阶段为 chdir 时置 true。

错误来源分层：
- 本地系统调用错误（`pipe2`/`sendmsg`/`mmap` 等）。
- helper clone 失败（`ProcessCloneResult._errno`）。
- child pre-exec 失败（`childErrFd` 回报）。
- 协议破损（长度异常）会映射为 `EBADMSG`。

## 6. 生命周期与资源管理约束

- `sendSpawnRequest` 内部会关闭自身不再使用的 FD。
- helper 在处理完请求后会关闭接收到的临时 FD。
- helper 以 epoll 管理 pidfd，child 退出后移除并释放对应事件数据。
- 当 `COMM_FD_UDS` 触发 `EPOLLHUP/EPOLLERR`，helper 退出。

## 7. 已知限制

- 协议无版本字段、无能力协商。
- memfd 参数格式 ABI 绑定，不可直接跨架构或跨不同 ABI 实现互通。
- `COMM_FD_UDS=3939` 为硬编码协议常量，需与启动注入保持一致。
- 安全上依赖本机 UDS+SCM_RIGHTS 语义，未做额外鉴权。

## 8. 实现对照

- Client API：`linux_spawn_helper/spawn_helper.c`
- Helper 主循环：`linux_spawn_helper/main.c`
- 参数序列化：`linux_spawn_helper/spawn_helper_comm.c`
- 协议常量与结构体：`linux_spawn_helper/spawn_helper_comm.h`
