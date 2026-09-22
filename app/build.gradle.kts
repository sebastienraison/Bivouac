import com.android.build.api.variant.BuildConfigField
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
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
//
// RIC-201 : une release PUBLIEE se compile toujours sans esri.apiKey, pour deux raisons
// indépendantes. D'abord la reproductibilité F-Droid (https://f-droid.org/docs/Reproducible_Builds/) :
// F-Droid reconstruit depuis les sources sans local.properties personnel, donc sans cette clé --
// une release qui l'embarquerait ne serait plus reconstructible à l'identique par quiconque d'autre,
// et divergerait de l'APK réellement distribué. Ensuite la sécurité : une clé posée dans
// defaultConfig finit dans BuildConfig, donc dans l'APK en clair, et un APK public est un fichier
// zip -- une clé qui s'y trouve est triviale à extraire (unzip + strings). Cette override reste
// un confort de développement local, jamais un mécanisme de release.
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val esriApiKey: String = localProperties.getProperty("esri.apiKey", "")

// RIC-133 : horodatage affiché en bas de Réglages pour savoir exactement quelle build tourne sur
// un appareil donné. Format non localisé, ISO 8601 (aaaa-MM-jj, RIC-193) : une build reste
// identique quel que soit l'appareil qui l'exécute, sa date ne devrait pas varier avec la locale
// du téléphone -- jj/MM/aaaa était ambigu en anglais (le 18e mois) et jamais explicitement daté
// par une locale, alors qu'ISO 8601 est sans ambiguïté dans les deux langues.
//
// RIC-201 : la valeur diffère désormais PAR buildType (buildConfigField déplacé de defaultConfig
// vers chaque buildType) -- les deux moitiés de la règle sont symétriques et volontaires :
//   - debug : reste la date DE COMPILATION (comportement d'origine, inchangé), fixée juste en
//     dessous et posée en buildConfigField classique dans buildTypes.debug. En développement on
//     recompile souvent des modifications non commitées ; dater du dernier commit afficherait une
//     date qui ne correspond pas à ce qui tourne réellement sur l'appareil de test.
//   - release : devient la date du DERNIER COMMIT, déterministe, pour que F-Droid puisse
//     reconstruire depuis les sources un APK identique octet pour octet au nôtre
//     (https://f-droid.org/docs/Reproducible_Builds/). `Date()` capture l'instant de la
//     compilation : deux builds des mêmes sources, à deux instants différents, produiraient deux
//     APK différents -- non reproductible par construction, quelle que soit la machine. Voir le
//     bloc androidComponents.onVariants tout en bas de ce fichier pour le calcul et son repli :
//     posé là plutôt qu'ici en buildConfigField classique, PAS par goût de l'API récente, mais
//     parce qu'un buildConfigField classique force la résolution de sa valeur (donc l'exécution de
//     `git log`) à CHAQUE configuration du projet, pour CHAQUE tâche demandée -- y compris
//     `testDebugUnitTest` ou `lintDebug`, qui n'ont rien à voir avec la release. Un git cassé
//     casserait alors des tâches purement debug, ce que RIC-201 ne demande pas. La MapProperty
//     paresseuse de l'API Variant (`variant.buildConfigFields`) ne résout sa valeur qu'à
//     l'exécution de la tâche qui génère RÉELLEMENT le BuildConfig de la release
//     (generateReleaseBuildConfig), jamais pour les autres variantes.
//
// Date de compilation, pour le buildType debug uniquement (voir ci-dessus).
val debugBuildDate: String = SimpleDateFormat("yyyy-MM-dd").format(Date())

android {
    namespace = "com.bivouac.app"
    // RIC-111 : Compose BOM 2026.08.00 (Compose 1.12) exige compileSdk >= 37 pour plusieurs
    // artefacts (androidx.compose.ui, material3, core-ktx, lifecycle-compose...) : confirmé par
    // les erreurs AGP au premier essai avec compileSdk=34, qui recommandaient explicitement 37.
    // compileSdk ne change que la surface de compilation (rétrocompatible par construction), pas
    // le comportement runtime de l'app : c'est targetSdk, plus bas, qui décide des changements de
    // comportement auxquels l'app se déclare prête.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bivouac.app"
        minSdk = 26
        // RIC-116 : 37 (Android 17), directement, sans étape par 36. Monter targetSdk revient à
        // opter dans les changements de comportement d'une version d'Android, donc à s'engager sur
        // une recette ; le raisonnement porte ici sur le calendrier autant que sur le code.
        //
        // Pourquoi pas 36 : Google Play exige déjà targetSdk 36 depuis le 31/08/2026, et exigera
        // 37 en août 2027. S'arrêter à 36 achèterait donc onze mois au prix d'une seconde recette
        // complète l'an prochain. Or les changements de comportement d'Android 17 confrontés à ce
        // code donnent un delta NUL au-delà de la ligne ci-dessous : limite mémoire des RemoteViews
        // (aucun widget), MessageQueue sans verrou et champs static final scellés (aucune
        // réflexion dans l'app), System.load en lecture seule (aucun appel : les deux seuls .so de
        // l'APK, libandroidx.graphics.path et libdatastore_shared_counter, sont embarqués par des
        // bibliothèques AndroidX et chargés par System.loadLibrary, et ils sont déjà alignés sur
        // 16 Ko, vérifié au zipalign), durcissement des lancements d'activité en arrière-plan
        // (aucun PendingIntent ni IntentSender ; les seuls startActivity partent d'une action
        // utilisateur au premier plan), audio en arrière-plan, SMS/OTP, ContactsContract, mot de
        // passe au clavier physique, BluetoothSocket RFCOMM : rien de tout cela n'existe ici.
        // ECH et Certificate Transparency activés par défaut sont transparents pour les trois hôtes
        // de tuiles publics (OSM, OpenTopoMap, ArcGIS Online). ACCESS_LOCAL_NETWORK, la permission
        // qui devient obligatoire en 17, ne concerne pas l'app : elle ne parle qu'à des serveurs
        // publics en HTTPS, sans socket brut ni NsdManager.
        //
        // Les changements d'Android 15 et 16 ont été passés en revue de la même façon. Aucun
        // service (donc rien des changements sur les services de premier plan), aucune
        // notification, aucun accès au focus audio, aucun ScheduledExecutorService, pas d'appel à
        // MediaStore#getVersion, et seulement en/fr (les changements de rendu du texte et la
        // dépréciation d'elegantTextHeight visent les écritures arabe, thaïe et indiennes).
        // L'accès galerie est déjà celui d'Android 14 (READ_MEDIA_VISUAL_USER_SELECTED, voir le
        // manifeste). Le retour arrière passe déjà par BackHandler, c'est-à-dire par
        // OnBackInvokedCallback : la fin de onBackPressed et de KEYCODE_BACK en 36 ne casse rien,
        // et aucun opt-out enableOnBackInvokedCallback n'est à poser. Aucune contrainte
        // d'orientation, de redimensionnement ni de ratio n'est déclarée, donc leur mise à l'écart
        // sur grand écran (36, rendue non contournable en 37) ne retire rien à l'app.
        //
        // L'edge-to-edge imposé est le seul changement qui touche vraiment l'app, et elle y était
        // déjà : enableEdgeToEdge() dans MainActivity.onCreate, et les inserts gérés écran par
        // écran. La disparition de l'opt-out en 36 (windowOptOutEdgeToEdgeEnforcement) ne fait donc
        // que sceller un état en vigueur, comme le mode d'encoche : enableEdgeToEdge pose déjà
        // LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS dès l'API 30 (vérifié dans le bytecode
        // d'androidx.activity 1.9.2, EdgeToEdgeApi30).
        //
        // Ce qui reste est une recette VISUELLE, sans correctif à écrire. Ce qu'un appareil
        // Android 16 suffit à montrer : lisibilité des icônes système au-dessus d'une carte plein
        // cadre (setStatusBarColor/setNavigationBarColor sont sans effet depuis 15, donc plus de
        // voile derrière les barres) ; hauteur de repli des deux tiroirs Journal et Import GPX, en
        // portrait, en paysage et en écran partagé (voir halfWindowHeight) ; geste de retour
        // prédictif, à la fois là où BackHandler l'intercepte (placement de photo, fermeture du
        // Journal) et là où il sort de l'app ; clavier et inserts sur la saisie des notes et des
        // tags. Ce qui exige un appareil Android 17 : rien de propre à 17 n'est visible sur un
        // téléphone, les deux changements 17 qui pourraient se voir concernent un grand écran
        // (contraintes d'orientation et de ratio définitivement ignorées au-delà de 600 dp de
        // largeur, donc à regarder sur tablette) et le réseau (ECH et Certificate Transparency
        // activés par défaut, donc à vérifier en chargeant les trois couches de tuiles).
        targetSdk = 37
        versionCode = 12
        versionName = "2.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "ESRI_API_KEY", "\"$esriApiKey\"")
        // RIC-201 : BUILD_DATE n'est plus ici -- valeur différente par buildType, voir le
        // commentaire RIC-133/RIC-201 plus haut et les deux buildTypes ci-dessous.
    }

    buildTypes {
        debug {
            buildConfigField("String", "BUILD_DATE", "\"$debugBuildDate\"")
        }
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
            // RIC-201 : BUILD_DATE n'est pas fixé ici (buildConfigField classique), mais plus bas
            // via androidComponents.onVariants -- voir le commentaire RIC-133/RIC-201 en tête de
            // fichier pour la raison : il faut que la lecture de `git log` reste paresseuse.
        }
    }
    // RIC-201 : le bloc de métadonnées de dépendances qu'AGP place par défaut dans l'APK Signing
    // Block (ID 0x504b4453) est chiffré avec une clé Google, donc il diffère à chaque build même à
    // sources et BUILD_DATE strictement identiques -- mesuré directement lors de RIC-201 : deux
    // builds indépendants de la même branche produisaient deux APK dont la SEULE zone différente,
    // octet pour octet, était ce bloc précis (tout le reste -- 244 entrées ZIP, resources.arsc,
    // notre propre signature v2, répertoire central, EOCD -- était identique). C'est la seule
    // source de non-reproductibilité identifiée par RIC-201, donc désactivée pour l'APK.
    // Reste dans le bundle (.aab) : seul Google Play l'exploite (signalement des dépendances à
    // risque dans Play Console), et F-Droid ne distribue jamais le bundle, seulement l'APK -- ce
    // qui s'y trouve n'a donc aucune incidence sur la reproductibilité vérifiée par F-Droid.
    dependenciesInfo {
        includeInApk = false
        // Valeur par défaut d'AGP, mais explicite : le choix ne doit pas dépendre d'un défaut qui
        // pourrait changer avec une future version d'AGP.
        includeInBundle = true
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
                // RIC-116 : l'android-all de l'API 37 crée une ApplicationSharedMemory au démarrage
                // de chaque test, et l'intercepteur de Robolectric y passe par
                // jdk.internal.access.SharedSecrets, un paquet que java.base n'exporte pas ("Failed
                // to interact with raw FileDescriptor internals", 242 tests sur 520). add-exports et
                // pas add-opens : c'est l'accès à la classe qui manque, pas la réflexion profonde.
                test.jvmArgs("--add-exports", "java.base/jdk.internal.access=ALL-UNNAMED")
            }
        }

        // RIC-43 : les suites instrumentées (migrations Room, sauvegarde/restauration) passent par
        // un émulateur jetable provisionné par le build, jamais par un appareil branché.
        // `connectedAndroidTest` désinstalle l'app à la fin de son exécution et effacerait les
        // données réelles du téléphone de recette : la tâche à lancer est
        // `pixel6Api37DebugAndroidTest`, qui crée l'AVD, l'exécute et le jette.
        //
        // Images « aosp » (sans les services Google) quand elles existent : rien ici n'en dépend,
        // et c'est la plus légère à télécharger. La première exécution récupère l'image système si
        // elle manque, c'est normal.
        managedDevices {
            localDevices {
                // RIC-116 : appareil de référence, aligné sur targetSdk = 37. C'est le seul qui
                // exerce réellement les changements de comportement d'Android 16 et 17
                // (edge-to-edge sans opt-out, retour prédictif activé par défaut, contraintes
                // d'orientation ignorées) : un appareil API 34 ne les déclenche pas, quel que soit
                // le targetSdk compilé.
                //
                // « google » et non « aosp » ici, contrairement aux deux autres : Google ne publie
                // aucune image `default` (aosp) pour l'API 37, seulement google_apis et
                // google_apis_playstore (vérifié avec `sdkmanager --list`). Les services Google
                // embarqués ne changent rien aux suites de ce dépôt, qui ne les touchent pas.
                create("pixel6Api37") {
                    device = "Pixel 6"
                    apiLevel = 37
                    systemImageSource = "google"
                    testedAbi = "x86_64"
                }
                // RIC-116 : conservé sous l'appareil de référence ci-dessus. Android 14 est la
                // version que le parc réel exécute encore majoritairement, et c'est le niveau
                // auquel l'app tournait avant ce ticket : le garder rend visible toute régression
                // qui ne se produirait QUE sur 37, et inversement.
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

// RIC-201 : BUILD_DATE du buildType release, posé ici plutôt qu'en buildConfigField classique dans
// buildTypes.release -- voir le commentaire RIC-133/RIC-201 en tête de fichier pour la raison
// (rester paresseux : ne lire `git log` qu'à l'exécution de generateReleaseBuildConfig, jamais à la
// configuration du projet, qui tourne pour toute tâche demandée y compris debug/lint).
// `variant.buildConfigFields` est une MapProperty : `.put(clé, Provider<...>)` ne résout la valeur
// qu'au moment où AGP lit effectivement la map, à l'exécution de la tâche de la variante release.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        // buildConfigFields est nullable dans le type de l'API (nulle si buildFeatures.buildConfig
        // est désactivé) ; ce module l'active explicitement (voir android.buildFeatures plus haut),
        // donc jamais nul ici -- `?.` reste la façon idiomatique de le dire au compilateur sans `!!`.
        variant.buildConfigFields?.put(
            "BUILD_DATE",
            providers.provider {
                // Date du dernier commit. %cs = date de committer, format court ISO 8601, rendue
                // dans le fuseau ENREGISTRÉ DANS LE COMMIT (pas celui de la machine qui compile) --
                // deux machines dans des fuseaux différents, ou la même machine à deux instants
                // différents, lisent donc la même date pour le même commit. Lecture faite via
                // providers.exec (API Provider de Gradle, depuis 7.5), jamais Runtime.exec ni
                // ProcessBuilder : un appel process brut est un input de build non déclaré, que le
                // cache de configuration ne peut pas suivre (et refuse explicitement s'il est actif)
                // ; providers.exec s'enregistre comme source de valeur trackée, donc compatible avec
                // `--configuration-cache` si ce projet l'active un jour.
                val gitCommitDate: String? = try {
                    val gitLog = providers.exec {
                        commandLine("git", "log", "-1", "--format=%cs")
                        isIgnoreExitValue = true
                    }
                    if (gitLog.result.get().exitValue == 0) gitLog.standardOutput.asText.get().trim() else null
                } catch (e: Exception) {
                    // git absent du PATH, ou incapable de démarrer le process : on tente le repli
                    // plutôt que d'échouer tout de suite, SOURCE_DATE_EPOCH peut très bien être
                    // positionnée.
                    null
                }
                // Repli si le dépôt git est indisponible (source distribuée hors d'un clone git,
                // `git` absent du PATH...) : SOURCE_DATE_EPOCH, la variable d'environnement standard
                // des builds reproductibles (https://reproducible-builds.org/docs/source-date-epoch/,
                // secondes Unix, toujours en UTC) que F-Droid ou un autre orchestrateur peut
                // positionner explicitement. Si ni git ni SOURCE_DATE_EPOCH ne répondent, le build
                // ÉCHOUE avec un message explicite : mieux vaut un échec net qu'une date
                // silencieusement fausse dans un APK dont la reproductibilité est justement ce qu'on
                // vérifie -- une valeur "juste plausible" ici serait pire que pas de valeur du tout,
                // elle passerait inaperçue jusqu'à l'échec de comparaison octet à octet chez F-Droid.
                val date = gitCommitDate
                    ?: providers.environmentVariable("SOURCE_DATE_EPOCH").orNull?.let { epoch ->
                        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
                            .withZone(ZoneOffset.UTC)
                            .format(Instant.ofEpochSecond(epoch.trim().toLong()))
                    }
                    ?: throw GradleException(
                        "RIC-201 : impossible de déterminer BUILD_DATE pour le buildType release -- " +
                            "`git log -1 --format=%cs` a échoué (pas un dépôt git, HEAD sans commit, " +
                            "ou `git` introuvable dans PATH) et SOURCE_DATE_EPOCH n'est pas définie. " +
                            "Lancer le build release depuis un clone git, ou positionner " +
                            "SOURCE_DATE_EPOCH (secondes Unix UTC) si les sources sont distribuées " +
                            "hors d'un dépôt git.",
                    )
                BuildConfigField("String", "\"$date\"", "RIC-201 : date du dernier commit (reproductibilité F-Droid)")
            },
        )
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
    // RIC-116 : 4.17 et pas 4.16, parce que Robolectric exécute par défaut ses tests au niveau
    // d'API du targetSdk de l'app et refuse de démarrer au-delà de celui qu'il embarque
    // (« Package targetSdkVersion=37 > maxSdkVersion=36 » sur 4.16). 4.17 est la première version
    // à livrer un android-all pour l'API 37. Alternative écartée : figer @Config(sdk = 36) pour
    // tout le module, ce qui reviendrait à faire tourner 520 tests sous une version d'Android que
    // l'app ne cible plus.
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.6.1")
    debugImplementation(libs.ui.tooling)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation(libs.room.testing)
    // RIC-116 : androidx.test remonté pour l'API 37. La cause est précise : Espresso construit son
    // injection d'événements en appelant android.hardware.input.InputManager.getInstance() par
    // réflexion, méthode cachée que l'API 37 ne fournit plus (NoSuchMethodException, 16 tests sur
    // 54 en échec avec espresso-core 3.5.0). espresso-core est déclaré explicitement parce qu'il
    // n'arrivait ici que transitivement par compose ui-test, qui l'épingle encore en 3.5.0.
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
