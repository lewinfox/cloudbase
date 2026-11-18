// Everything that can be wrong in the air lives here: pure Kotlin, no Android, tested on the JVM.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    // Optional real-world airspace files for sanity tests; tests skip when the directory is absent.
    systemProperty("airspaceSamples", System.getenv("AIRSPACE_SAMPLES") ?: "")
}
