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
    }
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
    mingwX64 {}
    linuxX64()
}
