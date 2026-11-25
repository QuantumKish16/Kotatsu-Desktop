import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.androidx.foundation.desktop)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
    // Coil 3 for loading manga covers & pages using your OkHttpClient
    implementation("io.coil-kt.coil3:coil-compose:3.1.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.1.0")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
}

compose.desktop {
    application {
        mainClass = "com.kishore.kotatsu.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Kotatsu Desktop"
            packageVersion = "0.2.0"
            description = "Desktop Manga Reader powered by Kotatsu Parsers"
            vendor = "Kishore"

            modules("java.sql", "java.naming", "jdk.crypto.ec", "java.desktop")

            windows {
                menuGroup = "Kotatsu Desktop"
                upgradeUuid = "8f4b2c1a-9d3e-4f7b-b6a2-1c5e8d9f0a3b"
                shortcut = true
                dirChooser = true
            }
        }
    }
}


