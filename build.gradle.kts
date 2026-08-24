plugins {
    alias(libs.plugins.android.application) apply false
    // RIC-111 : plus de plugin kotlin-android — Kotlin intégré à AGP 9.x (voir libs.versions.toml).
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
