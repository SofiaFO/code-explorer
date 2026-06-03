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

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        local("C:/Program Files/JetBrains/IntelliJ IDEA 2026.1.1")
        bundledPlugin("com.intellij.java")
    }
}

tasks {
    instrumentCode {
        enabled = false
    }
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
}
