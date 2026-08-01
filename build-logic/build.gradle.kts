plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.nmcp.plugin)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
