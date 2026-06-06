plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.vanniktech.publish) apply false
    alias(libs.plugins.ktfmt) apply false
    alias(libs.plugins.detekt) apply false
}

allprojects {
    group = "org.loesak.esque"
    version = findProperty("projectVersion") as String? ?: "NONE"
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "com.ncorti.ktfmt.gradle")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.file("detekt.yml"))
    }

    repositories {
        mavenCentral()
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }
}
