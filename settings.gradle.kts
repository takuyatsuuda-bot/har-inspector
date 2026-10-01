plugins {
    // JDK 21 が見つからない端末では自動でダウンロードする
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "har-inspector"
