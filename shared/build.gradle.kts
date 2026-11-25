plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    kotlin("plugin.serialization") version "2.4.21"
}

kotlin {
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            // Kotatsu-Redo Parsers Library (pulls the latest master snapshot from JitPack)
            api("com.github.Kotatsu-Redo:kotatsu-parsers-redo:master-SNAPSHOT")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
            // Required runtime libraries used by MangaLoaderContext & parsers on Desktop JVM
            implementation("com.squareup.okhttp3:okhttp:4.12.0")
            implementation("org.jsoup:jsoup:1.18.3")
            implementation("org.json:json:20240303")
            implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")


        }
    }
}