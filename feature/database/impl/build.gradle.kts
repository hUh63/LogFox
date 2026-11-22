plugins {
    alias(libs.plugins.logfox.android.feature)
    alias(libs.plugins.logfox.android.room)
}

// Must be a top-level ksp block: inside defaultConfig the argument is silently ignored by
// current AGP/KSP combinations, which is why the schema for the current version was never
// exported (only up to 18.json is committed)
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.f0x1d.logfox.feature.database.impl"
}

dependencies {
    api(projects.feature.database.api)
    compileOnly(libs.androidx.compose.runtime)
    implementation(libs.bundles.androidx)
    implementation(libs.timber)
}
