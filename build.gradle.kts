import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    kotlin("multiplatform") version "2.3.10"
    id("org.jetbrains.kotlinx.atomicfu") version "0.27.0"
}

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
