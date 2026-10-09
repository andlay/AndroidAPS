plugins {
    id("kmp-test-defaults")
    kotlin("multiplatform")
    // NOT com.android.library. AGP 9 refuses that plugin together with the multiplatform plugin.
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.metro)
    id("kotlinx-serialization")
}

// Same generator as :plugins:smoothing: a TextRef-named view of this module's strings for common code.
val generateAssistantStrings = tasks.register<GenerateKeyStringsTask>("generateAssistantStrings") {
    resDir.set(layout.projectDirectory.dir("src/androidMain/res"))
    packageName.set("app.aaps.plugins.assistant")
    owner.set("assistant")
    objectName.set("AssistantStrings")
    idsObjectName.set("AssistantStringIds")
    reportFile.set(layout.buildDirectory.file("reports/assistantStrings/translations.txt"))
    commonOutputDir.set(layout.buildDirectory.dir("generated/assistantStrings/common"))
    androidOutputDir.set(layout.buildDirectory.dir("generated/assistantStrings/android"))
}

kotlin {
    android {
        namespace = "app.aaps.plugins.assistant"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        androidResources { enable = true }
        withHostTest {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        compilerOptions { jvmTarget.set(Versions.jvmTarget) }

        lint {
            checkReleaseBuilds = false
            disable += "MissingTranslation"
            disable += "ExtraTranslation"
        }
    }

    iosArm64()
    iosSimulatorArm64()
    jvm()

    sourceSets {
        commonMain {
            kotlin.srcDir(generateAssistantStrings.flatMap { it.commonOutputDir })
            dependencies {
                implementation(project(":core:data"))
                implementation(project(":core:interfaces"))
                implementation(project(":core:keys"))
                implementation(project(":core:ui"))

                implementation(libs.jetbrains.compose.runtime)
                implementation(libs.jetbrains.compose.material.icons.extended)
                implementation(libs.io.ktor.client.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        androidMain {
            kotlin.srcDir(generateAssistantStrings.flatMap { it.androidOutputDir })
            dependencies {
                implementation(libs.io.ktor.client.okhttp)
            }
        }

        jvmMain {
            dependencies {
                implementation(libs.io.ktor.client.okhttp)
            }
        }

        // Accessor rather than getByName: iosMain is created by the default hierarchy template.
        iosMain {
            dependencies {
                implementation(libs.io.ktor.client.darwin)
            }
        }

        getByName("androidHostTest") {
            dependencies {
                implementation(project(":shared:tests"))
                implementation(libs.org.junit.jupiter)
                implementation(libs.org.junit.jupiter.api)
                implementation(libs.org.mockito.kotlin)
                implementation(libs.com.google.truth)
                implementation(libs.kotlinx.coroutines.test)
                runtimeOnly(libs.org.junit.platform.launcher)
            }
        }
    }
}
