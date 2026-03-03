# ktrun

A simple util to execute commands in Kotlin/Multiplatform projects. Without other dependencies.

## Targets

- JVM(java.lang.ProcessBuilder): Ready to use
- Windows(CreateProcessW): Ready to use
- Linux(clone/exec with seperated helper process): Ready to use

## Usage

```kotlin
fun main() {
    val process = buildProcess {
        cmdline = listOf("cmd", "/c", "echo Hello World")
        stdout = inherit()
    }
    process.waitForExit()
}
```

## Process termination

`Process` now exposes two termination entry points:

- `terminate(force = false)`: prefer graceful termination.
- `kill()`: compatibility alias for forceful termination (`terminate(force = true)`).

Platform behavior:

- Linux:
  - `force = false` sends `SIGTERM`.
  - `force = true` sends `SIGKILL`.
  - tries `pidfd_send_signal` first (to reduce PID-reuse race), then falls back to `kill(pid, sig)`.
- JVM:
  - `terminate(false)` -> `Process.destroy()`
  - `kill()` / `terminate(true)` -> `Process.destroyForcibly()`
- Windows (mingw):
  - currently both paths use `TerminateProcess`.
