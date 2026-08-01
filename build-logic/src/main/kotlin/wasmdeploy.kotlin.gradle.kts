plugins {
    kotlin("jvm")
}

val libs = the<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")
val javaVersionStr = libs.findVersion("java").get().requiredVersion
val javaVersion = JavaVersion.toVersion(javaVersionStr)

java {
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(javaVersionStr))
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}
