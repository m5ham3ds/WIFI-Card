// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

tasks.register("printBuildTools") {
    doLast {
        val buildToolsDir = java.io.File("/opt/android/sdk/build-tools")
        if (buildToolsDir.exists()) {
            buildToolsDir.listFiles()?.forEach { println("Found build tools: " + it.name) }
        } else {
            println("Build tools dir not found")
        }
    }
}
