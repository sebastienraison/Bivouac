import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // RIC-111 : plus de plugin kotlin-android — Kotlin intégré à AGP 9.x (built-in Kotlin),
    // activé par défaut. android.kotlinOptions {} est supprimé (plus supporté avec le Kotlin
    // intégré) ; jvmTarget hérite désormais de compileOptions.targetCompatibility ci-dessous,
    // toujours 17, donc pas besoin d'un bloc kotlin { compilerOptions { ... } } explicite.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Optional local override, same pattern as sdk.dir: a developer who has generated a personal
// Esri API key (see BIV-56 — free anonymous tile access is otherwise rate/volume-limited) can
// drop `esri.apiKey=...` in local.properties. Never committed; absent by default, in which case
// EsriWorldImagery falls back to the current unauthenticated public endpoint.
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val esriApiKey: String = localProperties.getProperty("esri.apiKey", "")

// RIC-133 : horodatage figé à la compilation (pas au runtime), affiché en bas de Réglages pour
// savoir exactement quelle build tourne sur un appareil donné. Format non localisé (jj/MM/aaaa) :
// une build reste identique quel que soit l'appareil qui l'exécute, sa date ne devrait pas varier
// avec la locale du téléphone.
val buildDate: String = SimpleDateFormat("dd/MM/yyyy").format(Date())

android {
    namespace = "com.bivouac.app"
    // RIC-111 : Compose BOM 2026.08.00 (Compose 1.12) exige compileSdk >= 37 pour plusieurs
    // artefacts (androidx.compose.ui, material3, core-ktx, lifecycle-compose...) — confirmé par
    // les erreurs AGP au premier essai avec compileSdk=34, qui recommandaient explicitement 37.
    // targetSdk volontairement laissé inchangé (34) : ne change que la surface de compilation
    // (rétrocompatible par construction), pas le comportement runtime de l'app — un bump de
    // targetSdk revient à opter dans des changements de comportement par version d'Android, ce
    // qui mérite sa propre vérification visuelle sur device (jamais faite cette nuit, voir CR).
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bivouac.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "2.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "ESRI_API_KEY", "\"$esriApiKey\"")
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Provisoire : pas de vraie signature de release configurée (chantier toolchain,
            // dette #3, encore différé). Réutilise la signature debug pour permettre un test
            // release sur device sans mettre en place un keystore de prod tout de suite — même
            // signature que le build debug déjà installé, donc `adb install -r` remplace en
            // place sans perte de données.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }
    // RIC-103 : les tests JVM exercent le cycle fermeture/réouverture de la base via Robolectric,
    // seul moyen d'avoir un vrai Context et une vraie base SQLite sans appareil (l'interdiction
    // de connectedAndroidTest avec le téléphone branché rend la voie instrumentée impraticable).
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // Robolectric ouvre des descripteurs de fichiers par réflexion (ParcelFileDescriptor),
                // ce que le système de modules du JDK 17 bloque par défaut.
                test.jvmArgs("--add-opens", "java.base/java.io=ALL-UNNAMED")
            }
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// room-migration (used by the Room KSP processor itself to validate/diff exported schema JSON
// across versions, and transitively by room-testing's MigrationTestHelper) requires
// kotlinx-serialization-json 1.8.1's GeneratedSerializer ABI, but a strict constraint published
// alongside room:2.8.4 pins the whole kotlinx-serialization-bom back down to 1.7.3, causing an
// AbstractMethodError both in the KSP processor classpath (once more than one schema version is
// present, e.g. schemas/5.json and 6.json) and at androidTest runtime. Not used by any app
// runtime code, so forcing it everywhere is safe.
configurations.all {
    resolutionStrategy {
        force(
            "org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1",
            "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.8.1",
            "org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1",
            "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.8.1",
        )
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.material.icons.core)
    implementation(libs.material.icons.extended)
    implementation(libs.osmdroid.android)
    implementation(libs.jpx)
    // JPX reads GPX via javax.xml.stream (StAX), which the Android platform doesn't ship.
    // stax-api provides the missing API classes, aalto-xml a pure-Java implementation of them.
    implementation(libs.stax.api)
    implementation(libs.aalto.xml)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.navigation.compose)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.6.1")
    debugImplementation(libs.ui.tooling)
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation(libs.room.testing)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
