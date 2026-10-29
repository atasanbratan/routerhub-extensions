import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// Release builds are signed with the key the RouterHub app trusts as "Official" (its
// routerhub.officialSigners). CI (.github/workflows/publish.yml) passes the key in these environment
// variables; without them release APKs are left unsigned and debug builds use the debug key as usual.
val releaseKeyStore: String? = providers.environmentVariable("ROUTERHUB_KEYSTORE").orNull

subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<ApplicationExtension> {
            if (releaseKeyStore != null) {
                signingConfigs.create("official") {
                    storeFile = file(releaseKeyStore)
                    storePassword = providers.environmentVariable("ROUTERHUB_KEYSTORE_PASSWORD").get()
                    keyAlias = providers.environmentVariable("ROUTERHUB_KEY_ALIAS").get()
                    keyPassword = providers.environmentVariable("ROUTERHUB_KEY_PASSWORD").get()
                }
                buildTypes.getByName("release").signingConfig = signingConfigs.getByName("official")
            }
        }
    }
}
