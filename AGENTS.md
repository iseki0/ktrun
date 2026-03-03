# Agent Working Rules

These rules are mandatory for this repository.

## Build Tooling

- Always use Gradle Wrapper (`./gradlew` or `gradlew.bat`) for all Gradle operations.
- Never run the system-installed `gradle` command directly.
- Never run Gradle inside sandboxed execution.
- If Gradle is required, request elevated execution and run via Gradle Wrapper only.
- Before upgrading Gradle, check Kotlin-Gradle compatibility first.
- Do not perform a Gradle upgrade that violates the compatibility matrix.

## Privilege and Sandbox

- If a required action is blocked by sandbox/permissions, request escalation instead of bypassing the requirement.
- On Windows, if commit signing or related Git operations require elevated execution due to sandbox constraints, request elevation and proceed only after approval.
- Do not repeatedly run the same blocked command without elevation.

## Git and Commits

- Every commit must be signed.
- Never bypass signing due to permission constraints; resolve permissions via proper escalation.

## Session Notes

- Linux helper control fd constant is `COMM_FD_UDS = 39` (`linux_spawn_helper/spawn_helper_comm.h`). Keep Kotlin/C sides consistent.
- On Windows, `linuxX64Test` is routed through WSL via `linuxX64TestWsl` task in `build.gradle.kts`.
- WSL distro behavior can differ (`wsl` default distro here is WSL1, another installed distro is WSL2). For manual validation, use `wsl -d <distro> ...` explicitly.
- For tests that synchronize on process stdout, `Readable.readNBytes` reads until requested length or EOF. Use exact-length reads for handshake markers (e.g., `READY\n`) to avoid blocking until process exit.
- Linux `Process.terminate(force=false)` sends `SIGTERM`; `kill()` is forceful alias. Linux implementation prefers `pidfd_send_signal` and falls back to `kill(pid, sig)`.
