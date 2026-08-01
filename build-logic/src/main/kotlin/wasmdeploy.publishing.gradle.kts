plugins {
    `maven-publish`
    signing
    id("com.gradleup.nmcp")
}

plugins.withType<JavaPlugin> {
    configure<JavaPluginExtension> {
        withSourcesJar()
        withJavadocJar()
    }
}

configure<PublishingExtension> {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set(project.name)
            description.set(project.description ?: "Wasm Deploy Optimizer Gradle Plugin")
            url.set("https://github.com/baole/wasmDeploy")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("baole")
                    name.set("Bao Le Duc")
                    email.set("leducbao@gmail.com")
                }
            }
            scm {
                connection.set("scm:git:git://github.com/baole/wasmDeploy.git")
                developerConnection.set("scm:git:ssh://github.com/baole/wasmDeploy.git")
                url.set("https://github.com/baole/wasmDeploy")
            }
        }
    }
}

configure<SigningExtension> {
    val signingKey = providers.gradleProperty("signingKey").orNull ?: System.getenv("GPG_SIGNING_KEY")
    val signingPassword =
        providers.gradleProperty("signingPassword").orNull ?: System.getenv("GPG_SIGNING_PASSWORD")
    val isRelease = providers.gradleProperty("releaseBuild").orNull?.toBoolean() ?: false
    isRequired = isRelease
    if (isRelease) {
        if (!signingKey.isNullOrEmpty() && !signingPassword.isNullOrEmpty()) {
            useInMemoryPgpKeys(signingKey, signingPassword)
        }
    }
    val publishing = extensions.getByType<PublishingExtension>()
    sign(publishing.publications)
}
