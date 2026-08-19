plugins {
    id("com.android.application")
}

// Watch Face Format bundles are resource-only: Google Play (and the platform itself) requires the
// bundle carrying a WFF watch face to be completely separate from any bundle containing app code,
// hence this being a standalone module rather than living inside :wear (which has extensive Kotlin
// logic). See https://developer.android.com/training/wearables/wff/setup.

// The AAPS application id differs per product flavor, and the watch face references its complication
// providers by fully qualified component name. res/raw/watchface.xml is therefore generated per
// flavor from the template in src/main/wff/, substituting the matching id. Without this, three of the
// four flavors would ship a face whose rings and bezel point at an uninstalled package, which is
// indistinguishable from "no data" on screen.
val aapsApplicationIds = mapOf(
    "full" to "info.nightscout.androidaps",
    "pumpcontrol" to "info.nightscout.aapspumpcontrol",
    "aapsclient" to "info.nightscout.aapsclient",
    "aapsclient2" to "info.nightscout.aapsclient2"
)

android {
    namespace = "info.nightscout.androidaps.wff"

    defaultConfig {
        // WFF requires Wear OS 4 (API 33) or newer; there is no point targeting this module at the
        // wearMinSdk=30 the classic :wear faces use, since WFF didn't exist on those OS versions.
        minSdk = 33
        targetSdk = Versions.compileSdk
        versionCode = 1
    }

    compileSdk = Versions.compileSdk

    // Mirrors :wear's flavors so CI's `assemble${BUILD_VARIANT}` resolves for this module too.
    // Each face is a separate package from its AAPS app, suffixed .wff.
    flavorDimensions.add("standard")
    productFlavors {
        aapsApplicationIds.forEach { (flavor, aapsId) ->
            create(flavor) {
                if (flavor == "full") isDefault = true
                dimension = "standard"
                applicationId = "$aapsId.wff"
                versionName = Versions.appVersion
            }
        }
    }

    buildFeatures {
        // No code at all is permitted in a WFF bundle: android:hasCode="false" in the manifest is the
        // enforced declaration of this, so there is nothing to consume BuildConfig either.
        buildConfig = false
    }

    sourceSets {
        aapsApplicationIds.keys.forEach { flavor ->
            getByName(flavor).res.srcDir(layout.buildDirectory.dir("generated/wffRes/$flavor"))
        }
    }
}

aapsApplicationIds.forEach { (flavor, aapsId) ->
    val generate = tasks.register<Copy>("generate${flavor.replaceFirstChar { it.uppercase() }}WatchFaceRes") {
        description = "Generates res/raw/watchface.xml for the $flavor flavor from src/main/wff/."
        from(layout.projectDirectory.file("src/main/wff/watchface.xml"))
        into(layout.buildDirectory.dir("generated/wffRes/$flavor/raw"))
        filter { line -> line.replace("__AAPS_APPLICATION_ID__", aapsId) }
        // Re-run whenever the substituted value changes, not just when the template does.
        inputs.property("aapsApplicationId", aapsId)
    }
    tasks.named("preBuild") { dependsOn(generate) }
}
