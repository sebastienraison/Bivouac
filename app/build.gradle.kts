import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // RIC-111 : plus de plugin kotlin-android : Kotlin intégré à AGP 9.x (built-in Kotlin),
    // activé par défaut. android.kotlinOptions {} est supprimé (plus supporté avec le Kotlin
    // intégré) ; jvmTarget hérite désormais de compileOptions.targetCompatibility ci-dessous,
    // toujours 17, donc pas besoin d'un bloc kotlin { compilerOptions { ... } } explicite.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Optional local override, same pattern as sdk.dir: a developer who has generated a personal
// Esri API key (see BIV-56, free anonymous tile access is otherwise rate/volume-limited) can
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
// savoir exactement quelle build tourne sur un appareil donné. Format non localisé, ISO 8601
// (aaaa-MM-jj, RIC-193) : une build reste identique quel que soit l'appareil qui l'exécute, sa date
// ne devrait pas varier avec la locale du téléphone -- jj/MM/aaaa était ambigu en anglais (le 18e
// mois) et jamais explicitement daté par une locale, alors qu'ISO 8601 est sans ambiguïté dans les
// deux langues.
val buildDate: String = SimpleDateFormat("yyyy-MM-dd").format(Date())

android {
    namespace = "com.bivouac.app"
    // RIC-111 : Compose BOM 2026.08.00 (Compose 1.12) exige compileSdk >= 37 pour plusieurs
    // artefacts (androidx.compose.ui, material3, core-ktx, lifecycle-compose...) : confirmé par
    // les erreurs AGP au premier essai avec compileSdk=34, qui recommandaient explicitement 37.
    // targetSdk volontairement laissé inchangé (34) : ne change que la surface de compilation
    // (rétrocompatible par construction), pas le comportement runtime de l'app : un bump de
    // targetSdk revient à opter dans des changements de comportement par version d'Android, ce
    // qui mérite sa propre vérification visuelle sur device, jamais faite depuis (RIC-116).
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bivouac.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "2.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "ESRI_API_KEY", "\"$esriApiKey\"")
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Choix assumé, pas un oubli : F-Droid compile et signe lui-même le binaire depuis
            // les sources (sa propre clé, jamais la nôtre) : la signature de ce buildType n'a
            // donc aucune incidence sur ce qui est réellement distribué. Elle ne sert qu'au
            // mainteneur, pour pouvoir tester un build "release-shaped" en local sans se
            // fabriquer un keystore de prod pour un usage qui ne le nécessite pas. Réutilise la
            // signature debug : même signature que le build debug déjà installé, donc
            // `adb install -r` remplace en place sans perte de données.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // RIC-163 : jpx 3.2.1 appelle en interne Stream.toList() (java.util.stream, ajouté en
        // JDK 16), absent de la libcore Android en dessous d'API 34 : provoquait un
        // NoSuchMethodError sur tout import GPX pour l'essentiel du parc minSdk 26-33 (voir
        // GpxParserInstrumentedTest, qui reproduit l'échec sans cette ligne). Le desugaring de
        // bibliothèque réécrit les appels à ces API récentes vers une implémentation embarquée
        // dans l'APK, sans changer minSdk = 26.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // RIC-187 (lot 0 du chantier i18n RIC-24) : deux contentDescription de l'inventaire
    // (planification_bivouac_count_description, journal_list_bivouac_nights_description) sont
    // volontairement des <plurals> sans aucun chiffre dans le texte -- le compte est affiche a cote
    // par un Text separe, pas concatene dans la description elle-meme (note pilotage). Lint le
    // signale en erreur (ImpliedQuantity) parce qu'en francais la categorie CLDR "one" couvre aussi
    // bien 0 que 1 : une vraie mise en garde en general, mais un faux positif pour ces deux cles
    // precises, dont le texte ne pretend justement pas porter le nombre. Passe en avertissement
    // plutot que desactive : les lots 1 a 4 devront verifier chaque nouvelle occurrence au cas par
    // cas avant de la laisser filer.
    //
    // RIC-191 (lot 4) : verifie, une TROISIEME occurrence, journal_error_photo_save_failed. Elle est
    // deliberee : la forme "one" dit "Une photo n'a pas pu etre enregistree", qui se lit mieux que
    // "1 photo n'a pas pu etre enregistree" et reste juste pour zero (cas qui ne se produit pas, le
    // message n'etant compose que si le compte est strictement positif).
    lint {
        warning += "ImpliedQuantity"
        // RIC-187 (lot 0 i18n) : garde-fou du chantier RIC-24 -- une ressource ajoutee dans
        // values/strings.xml (anglais, langue par defaut) sans son equivalent dans
        // values-fr/strings.xml casserait le francais, la locale de Seb et donc celle testee en
        // premier. MissingTranslation est desactive par defaut dans le gabarit Android Studio ;
        // remonte ici en erreur bloquante des le lot 0, avant qu'aucun ecran ne consomme encore
        // ces ressources (lots 1 a 4).
        error += "MissingTranslation"
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

        // RIC-43 : les suites instrumentées (migrations Room, sauvegarde/restauration) passent par
        // un émulateur jetable provisionné par le build, jamais par un appareil branché.
        // `connectedAndroidTest` désinstalle l'app à la fin de son exécution et effacerait les
        // données réelles du téléphone de recette : la tâche à lancer est
        // `pixel6Api35DebugAndroidTest`, qui crée l'AVD, l'exécute et le jette.
        //
        // Images « aosp » (sans les services Google) partout : rien ici n'en dépend, et c'est la
        // plus légère à télécharger. La première exécution récupère l'image système si elle manque,
        // c'est normal.
        managedDevices {
            localDevices {
                // RIC-116 : appareil de référence, aligné sur targetSdk = 35. C'est le seul qui
                // exerce réellement les changements de comportement d'Android 15 (edge-to-edge
                // imposé, Configuration qui inclut les barres système, voile des barres ignoré) :
                // un appareil API 34 ne les déclenche pas, quel que soit le targetSdk compilé.
                create("pixel6Api35") {
                    device = "Pixel 6"
                    apiLevel = 35
                    systemImageSource = "aosp"
                    testedAbi = "x86_64"
                }
                // RIC-116 : conservé sous l'appareil de référence ci-dessus. Android 14 est la
                // version que le parc réel exécute encore majoritairement, et c'est le niveau
                // auquel l'app tournait avant ce ticket : le garder rend visible toute régression
                // qui ne se produirait QUE sur 35, et inversement.
                create("pixel6Api34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp"
                    // Explicite parce que le défaut change en AGP 10 (x86_64 -> arm64-v8a) et que
                    // l'image aosp ne sait pas traduire l'ARM : sans cette ligne, la même
                    // configuration cesserait de fonctionner à la prochaine montée d'AGP.
                    testedAbi = "x86_64"
                }
                // RIC-163 : le seul appareil de test (ci-dessus, API 34) masquait un bug présent
                // sur tout le reste du parc minSdk 26-33 : jpx 3.2.1 appelle en interne
                // Stream.toList() (Java 16), absent de la libcore Android avant API 34. Ce device
                // couvre le rapport F-Droid d'origine (Android 13 / API 33) et comble l'angle mort ;
                // il reste dans la config à demeure, pas seulement pour la repro de ce ticket.
                create("pixel6Api33") {
                    device = "Pixel 6"
                    apiLevel = 33
                    systemImageSource = "aosp"
                    testedAbi = "x86_64"
                }
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
    implementation(libs.exifinterface)
    implementation(libs.coil.compose)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.6.1")
    debugImplementation(libs.ui.tooling)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation(libs.room.testing)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
