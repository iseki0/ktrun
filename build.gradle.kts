import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    kotlin("multiplatform") version "2.2.0"
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
    linuxArm64 {
        compilations.getByName("main") {
            val doForkAndExec by cinterops.creating {
                definitionFile = file("src/linuxArm64Main/nativeInterop/cinterop/doForkAndExec.def")
                packageName = "space.iseki.ktrun.native"
            }
        }
    }
}

