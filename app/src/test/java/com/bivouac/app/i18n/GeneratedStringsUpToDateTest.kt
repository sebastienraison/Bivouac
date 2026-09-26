package com.bivouac.app.i18n

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * RIC-206 : garde-fou de non-regression contre l'edition manuelle des strings.xml generes.
 *
 * `StringsResourcesConsistencyTest` verifie une COHERENCE INTERNE (memes cles, memes parametres,
 * memes quantites entre en et fr) : une correction bien faite a la main dans les deux fichiers y
 * passerait sans probleme. Ce test-ci verifie autre chose -- que les deux fichiers COMMITTES
 * correspondent bien a une regeneration fraiche depuis l'inventaire CSV qui fait foi (voir
 * tools/i18n/README.md) : le cas qui a motive ce ticket est une correction posee a la main dans
 * strings.xml et jamais reportee dans l'inventaire, qu'une regeneration ulterieure aurait annulee
 * en silence.
 *
 * L'inventaire (`docs/pilotage/i18n/strings-inventaire-vN.csv`) vit HORS depot : `docs/` est
 * gitignore, donc absent des worktrees et de tout CI. Ce test ne peut donc s'executer que sur un
 * poste qui a cet inventaire quelque part -- jamais en rouge silencieux quand il manque :
 * `Assume.assumeTrue` avec un message explicite fait ignorer le test plutot que le faire echouer
 * ou le faire passer a tort.
 *
 * Localisation de l'inventaire (dans cet ordre) :
 *   1. propriete systeme `bivouac.i18n.inventoryDir` (transmise par app/build.gradle.kts depuis
 *      une propriete de projet `-Pbivouac.i18n.inventoryDir=...` ou la variable d'environnement)
 *   2. variable d'environnement `BIVOUAC_I18N_INVENTORY_DIR`
 *   3. a defaut, `<racine du depot>/docs/pilotage/i18n/`
 *
 * Le NOM du CSV a chercher dans ce repertoire n'est pas fige ici : il est lu dans l'en-tete
 * "Source : ..." des fichiers generes committes, qui porte le chemin utilise a la derniere
 * generation (voir generate_strings.py). On en garde juste le nom de fichier (basename) : le
 * repertoire, lui, vient des trois pistes ci-dessus, pas du chemin absolu ecrit dans l'en-tete
 * (qui est celui du poste qui a genere, pas forcement celui qui fait tourner le test).
 */
class GeneratedStringsUpToDateTest {

    // Le test tourne avec app/ comme repertoire de travail (comme StringsResourcesConsistencyTest).
    private val enPath = File("src/main/res/values/strings.xml")
    private val frPath = File("src/main/res/values-fr/strings.xml")

    private val repoRoot: File
        get() = File(".").canonicalFile.parentFile ?: File(".").canonicalFile

    private fun inventoryDir(): File {
        val fromSystemProperty = System.getProperty("bivouac.i18n.inventoryDir")
        val fromEnv = System.getenv("BIVOUAC_I18N_INVENTORY_DIR")
        val configured = fromSystemProperty?.takeIf { it.isNotBlank() }
            ?: fromEnv?.takeIf { it.isNotBlank() }
        return if (configured != null) File(configured) else File(repoRoot, "docs/pilotage/i18n")
    }

    private fun sourceCsvName(strings: File): String {
        val line = strings.readLines(Charsets.UTF_8)
            .firstOrNull { it.trimStart().startsWith("Source :") }
        checkNotNull(line) { "En-tete 'Source : ...' introuvable dans ${strings.absolutePath}." }
        // "    Source : /chemin/vers/strings-inventaire-v12.csv (inventaire de l'agent..." -- le
        // chemin est le premier token apres "Source :", sans espace (noms de fichiers en
        // kebab-case dans ce depot).
        val path = line.substringAfter("Source :").trim().substringBefore(' ')
        return File(path).name
    }

    private fun python3Available(): Boolean =
        try {
            val process = ProcessBuilder("python3", "--version").start()
            process.waitFor()
            true
        } catch (e: java.io.IOException) {
            false
        }

    private fun firstDiffLines(expected: List<String>, actual: List<String>, max: Int = 15): String {
        val out = StringBuilder()
        val size = maxOf(expected.size, actual.size)
        var shown = 0
        for (i in 0 until size) {
            val e = expected.getOrNull(i)
            val a = actual.getOrNull(i)
            if (e != a) {
                out.append("  ligne ${i + 1} :\n")
                out.append("    committe   : ${e ?: "<absente>"}\n")
                out.append("    regenere   : ${a ?: "<absente>"}\n")
                shown++
                if (shown >= max) {
                    out.append("  (...)\n")
                    break
                }
            }
        }
        return out.toString()
    }

    @Test
    fun `les strings-xml committes correspondent a une regeneration depuis l'inventaire`() {
        val csvName = sourceCsvName(enPath)
        val dir = inventoryDir()
        val csvFile = File(dir, csvName)

        assumeTrue(
            "python3 introuvable sur ce poste : garde-fou ignore (installer python3 pour l'activer).",
            python3Available(),
        )
        assumeTrue(
            "Inventaire '$csvName' introuvable dans '${dir.absolutePath}' -- definir " +
                "bivouac.i18n.inventoryDir (propriete Gradle) ou BIVOUAC_I18N_INVENTORY_DIR " +
                "(variable d'environnement) vers le repertoire qui le contient, ou le poser dans " +
                "<racine du depot>/docs/pilotage/i18n/. Garde-fou ignore, pas suppose vert.",
            csvFile.isFile,
        )

        val tmpDir = kotlin.io.path.createTempDirectory("bivouac-i18n-check").toFile()
        try {
            val generatorScript = File(repoRoot, "tools/i18n/generate_strings.py")
            check(generatorScript.isFile) {
                "Generateur introuvable : ${generatorScript.absolutePath} (repoRoot mal calcule ?)."
            }

            val process = ProcessBuilder(
                "python3",
                generatorScript.absolutePath,
                csvFile.absolutePath,
                "--out-dir",
                tmpDir.absolutePath,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            check(exitCode == 0) {
                "generate_strings.py a echoue (code $exitCode) sur '${csvFile.absolutePath}' :\n$output"
            }

            val generatedEn = File(tmpDir, "values/strings.xml")
            val generatedFr = File(tmpDir, "values-fr/strings.xml")

            val enCommittedLines = enPath.readLines(Charsets.UTF_8)
            val enGeneratedLines = generatedEn.readLines(Charsets.UTF_8)
            val frCommittedLines = frPath.readLines(Charsets.UTF_8)
            val frGeneratedLines = generatedFr.readLines(Charsets.UTF_8)

            val enBytesEqual = enPath.readBytes().contentEquals(generatedEn.readBytes())
            val frBytesEqual = frPath.readBytes().contentEquals(generatedFr.readBytes())

            if (!enBytesEqual || !frBytesEqual) {
                val message = StringBuilder()
                message.append(
                    "strings.xml committes different d'une regeneration fraiche depuis " +
                        "'${csvFile.absolutePath}' -- une correction a ete faite a la main sans " +
                        "etre reportee dans l'inventaire (ou l'inventaire a change sans " +
                        "regeneration). Corriger l'inventaire, puis : python3 " +
                        "tools/i18n/generate_strings.py ${csvFile.absolutePath}\n",
                )
                if (!enBytesEqual) {
                    message.append("values/strings.xml :\n")
                    message.append(firstDiffLines(enCommittedLines, enGeneratedLines))
                }
                if (!frBytesEqual) {
                    message.append("values-fr/strings.xml :\n")
                    message.append(firstDiffLines(frCommittedLines, frGeneratedLines))
                }
                org.junit.Assert.fail(message.toString())
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
