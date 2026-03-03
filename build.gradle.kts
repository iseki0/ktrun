import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    kotlin("multiplatform") version "2.3.10"
    id("org.jetbrains.kotlinx.atomicfu") version "0.31.0"
}

val osName = System.getProperty("os.name")
val isLinuxHost = osName.contains("Linux", ignoreCase = true)
val isWindowsHost = osName.contains("Windows", ignoreCase = true)

group = "space.iseki.ktrun"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    commonTestImplementation(kotlin("test"))
}

kotlin {
    @OptIn(ExperimentalAbiValidation::class) abiValidation {
        enabled = true
    }
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        freeCompilerArgs.add("-Xopt-in=kotlin.native.internal.InternalForKotlinNative")
    }
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
    mingwX64 {}
    linuxX64 {
        compilations.getByName("main") {
            val doForkAndExec by cinterops.creating {
                definitionFile = file("src/linuxX64Main/nativeInterop/cinterop/doForkAndExec.def")
                packageName = "space.iseki.ktrun.native"
            }
        }
    }
}

val linuxX64NativePartCMake = tasks.register("linuxX64NativePartCMake", Exec::class.java) {
    doFirst { File("linux_spawn_helper/cmake-build-linux-x86_64").mkdirs() }
    workingDir("linux_spawn_helper/cmake-build-linux-x86_64")
    commandLine("cmake", "--preset", "linux-x86_64", "..")
}

val linuxX64NativePartBuild = tasks.register("linuxX64NativePartBuild", Exec::class.java) {
    dependsOn(linuxX64NativePartCMake)
    workingDir("linux_spawn_helper/cmake-build-linux-x86_64")
    commandLine("ninja")
}

val linuxX64NativePartTest = tasks.register("linuxX64NativePartTest", Exec::class.java) {
    group = "verification"
    description = "Run Linux C tests (ctest): native on Linux hosts, via WSL on Windows."
    if (isLinuxHost || isWindowsHost) {
        dependsOn(linuxX64NativePartBuild)
    }
    if (isWindowsHost) {
        commandLine("wsl", "linux_spawn_helper/cmake-build-linux-x86_64/hello_test")
    } else {
        commandLine("linux_spawn_helper/cmake-build-linux-x86_64/hello_test")
    }
    onlyIf("Linux host or Windows+WSL host required") {
        val host = System.getProperty("os.name")
        host.contains("Linux", ignoreCase = true) || host.contains("Windows", ignoreCase = true)
    }
}

if (isWindowsHost) {
    val buildDirFile = layout.buildDirectory.get().asFile
    val testExe = File(buildDirFile, "bin/linuxX64/debugTest/test.kexe").absolutePath
    val normalized = testExe.replace("\\", "/")
    val wslExePath = if (normalized.length >= 3 && normalized[1] == ':' && normalized[2] == '/') {
        val drive = normalized[0].lowercaseChar()
        "/mnt/$drive${normalized.substring(2)}"
    } else {
        normalized
    }

    val linuxX64TestWsl = tasks.register("linuxX64TestWsl", Exec::class.java) {
        group = "verification"
        description = "Run linuxX64 Kotlin/Native tests through WSL on Windows hosts."
        dependsOn("linkDebugTestLinuxX64")
        inputs.file(File(buildDirFile, "bin/linuxX64/debugTest/test.kexe"))
        commandLine("wsl", wslExePath)
    }

    tasks.named("linuxX64Test") {
        dependsOn(linuxX64TestWsl)
        enabled = false
    }
}

val linuxX64NativePartClean = tasks.register("linuxX64NativePartClean", Exec::class.java) {
    workingDir("linux_spawn_helper/cmake-build-linux-x86_64")
    commandLine("ninja", "clean")
    dependsOn(linuxX64NativePartCMake)
    onlyIf {
        File("linux_spawn_helper/cmake-build-linux-x86_64", "build.ninja").exists()
    }
}

tasks.clean {
    dependsOn(linuxX64NativePartClean)
}

tasks.named("cinteropDoForkAndExecLinuxX64") {
    dependsOn(linuxX64NativePartBuild)
}

tasks.check {
    dependsOn(linuxX64NativePartTest)
}
