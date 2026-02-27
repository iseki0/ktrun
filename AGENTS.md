# Agent Working Rules

These rules are mandatory for this repository.

## Build Tooling

- Always use Gradle Wrapper (`./gradlew` or `gradlew.bat`), never a system-installed `gradle`.
- Before upgrading Gradle, check Kotlin-Gradle compatibility first.
- Do not perform a Gradle upgrade that violates the compatibility matrix.

## Privilege and Sandbox

- If a required action is blocked by sandbox/permissions, request escalation instead of bypassing the requirement.
- On Windows, if commit signing or related Git operations require elevated execution due to sandbox constraints, request elevation and proceed only after approval.
- Do not repeatedly run the same blocked command without elevation.

## Git and Commits

- Every commit must be signed.
- Never bypass signing due to permission constraints; resolve permissions via proper escalation.
