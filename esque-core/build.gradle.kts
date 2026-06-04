plugins {
    alias(libs.plugins.vanniktech.publish)
}

dependencies {
    implementation(libs.kotlin.stdlib)
    api(libs.elasticsearch.rest.client)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.kotlin.logging.jvm)
    implementation(libs.slf4j.api)

    testImplementation(libs.logback.classic)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.testcontainers.elasticsearch)
    testImplementation(libs.testcontainers.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    if (providers.gradleProperty("signingInPlaceKey").isPresent ||
        providers.environmentVariable("ORG_GRADLE_PROJECT_signingInPlaceKey").isPresent) {
        signAllPublications()
    }

    coordinates(
        groupId = project.group.toString(),
        artifactId = project.name,
        version = project.version.toString(),
    )

    pom {
        name.set("esque")
        description.set("Resembles an Elasticsearch Stateful Query Executor")
        url.set("https://github.com/loesak/esque")
        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                name.set("Aaron Loes")
                email.set("aaron.loes@gmail.com")
                organization.set("Loesak")
                organizationUrl.set("https://github.com/loesak/esque")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/loesak/esque.git")
            developerConnection.set("scm:git:ssh://github.com:loesak/esque.git")
            url.set("https://github.com/loesak/esque")
        }
    }
}
