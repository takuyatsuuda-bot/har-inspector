plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.fumulabo"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.1")
    }
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null }
        }
    }
    publishing {
        token = providers.environmentVariable("JB_PUBLISH_TOKEN")
    }
}

tasks.test { useJUnitPlatform() }

// runIde で見本フォルダを開き、example.har をそのまま表示する
tasks.runIde {
    val demo = layout.projectDirectory.dir("samples/demo").asFile
    args(demo.absolutePath, demo.resolve("example.har").absolutePath)
}
