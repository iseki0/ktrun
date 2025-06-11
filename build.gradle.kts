plugins {
    kotlin("multiplatform") version "2.1.20"
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
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    targets {
        jvm {}
        mingwX64 {}
//        linuxX64()
//        linuxArm64()
    }
}
