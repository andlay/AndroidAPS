plugins {
    id("com.android.application")
}

// Watch Face Format bundles are resource-only: Google Play (and the platform itself) requires the
// bundle carrying a WFF watch face to be completely separate from any bundle containing app code,
// hence this being a standalone module rather than living inside :wear (which has extensive Kotlin
// logic). See https://developer.android.com/training/wearables/wff/setup.
android {
    namespace = "info.nightscout.androidaps.wff"

    defaultConfig {
        applicationId = "info.nightscout.androidaps.wff"
        // WFF requires Wear OS 4 (API 33) or newer; there is no point targeting this module at the
        // wearMinSdk=30 the classic :wear faces use, since WFF didn't exist on those OS versions.
        minSdk = 33
        targetSdk = Versions.compileSdk
        versionCode = 1
        versionName = Versions.appVersion
    }

    compileSdk = Versions.compileSdk


    buildFeatures {
        // No code at all is permitted in a WFF bundle -- android:hasCode="false" in the manifest
        // below is the enforced declaration of this; disabling buildConfig here too since there's no
        // code to consume it.
        buildConfig = false
    }
}
