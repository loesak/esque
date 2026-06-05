plugins { alias(libs.plugins.vanniktech.publish) }

dependencies {
  implementation(libs.kotlin.stdlib)
  api(libs.elasticsearch.rest.client)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.module.kotlin)
  implementation(libs.jackson.datatype.jsr310)
  implementation(libs.jackson.dataformat.yaml)
  implementation(libs.kotlin.logging.jvm)
  implementation(libs.slf4j.api)

  testImplementation(platform(libs.junit.bom))
  testRuntimeOnly(libs.junit.platform.launcher)
  testImplementation(libs.logback.classic)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.assertj.core)
  testImplementation(libs.testcontainers.elasticsearch)
  testImplementation(libs.testcontainers.junit.jupiter)
}

tasks.test { useJUnitPlatform() }

mavenPublishing {
  // Publishes releases via the Central Portal API and SNAPSHOTs to
  // https://central.sonatype.com/repository/maven-snapshots/ automatically.
  // Requires snapshots to be enabled for the namespace on central.sonatype.com.
  publishToMavenCentral()
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
