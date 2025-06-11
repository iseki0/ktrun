# ktrun

A simple util to execute commands in Kotlin/Multiplatform projects. Without other dependencies.

## Targets

- JVM(java.lang.ProcessBuilder): Ready to use
- Windows(CreateProcessW): Ready to use
- Linux(posix_spawn): WIP

## Usage

```kotlin
fun main() {
    val theLaunchedProcess = buildProcess {
        cmdline = listOf("cmd", "/c", "echo Hello World")
        stdout = inherit()
    }
}
```
