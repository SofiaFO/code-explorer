plugins {
    id("java")
    id("org.jetbrains.intellij.platform")
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val localPath = providers.gradleProperty("idea.local.path").orNull
val platformVersion = providers.gradleProperty("platformVersion")
    .getOrElse("2026.1.1")

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        if (!localPath.isNullOrBlank()) {
            local(localPath)
        } else {
            intellijIdeaUltimate(platformVersion)
        }
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
    }
}

tasks {
    instrumentCode {
        enabled = false
    }
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.compilerArgs.add("-Xlint:deprecation")
    }
}