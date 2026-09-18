package com.bivouac.app.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Element

/**
 * RIC-187 (lot 0 i18n) : garde-fou qui parse directement les deux fichiers de ressources générés
 * par tools/i18n/generate_strings.py (values/strings.xml = anglais, values-fr/strings.xml =
 * français) depuis le système de fichiers du module -- pas de dépendance à un contexte Android,
 * ce test tourne comme n'importe quel test JVM pur.
 *
 * Vérifie ce que le générateur garantit déjà à la génération (mêmes clés, mêmes paramètres, mêmes
 * quantités, aucune chaîne vide) : filet de sécurité si les fichiers générés divergent de
 * l'inventaire sans repasser par le générateur (édition manuelle malgré l'en-tête "NE PAS ÉDITER",
 * merge de branches...), pas une redite du test du générateur lui-même
 * (tools/i18n/test_generate_strings.py, qui teste le script, pas son résultat committé).
 */
class StringsResourcesConsistencyTest {

    private sealed class Resource {
        data class Str(val value: String) : Resource()
        data class Plural(val items: Map<String, String>) : Resource()
    }

    private lateinit var enResources: Map<String, Resource>
    private lateinit var frResources: Map<String, Resource>

    @Before
    fun charger() {
        enResources = parseStringsXml(File("src/main/res/values/strings.xml"))
        frResources = parseStringsXml(File("src/main/res/values-fr/strings.xml"))
    }

    private fun parseStringsXml(file: File): Map<String, Resource> {
        assertTrue("Fichier introuvable : ${file.absolutePath}", file.exists())
        val factory = DocumentBuilderFactory.newInstance()
        val doc = factory.newDocumentBuilder().parse(file)
        val root = doc.documentElement
        val result = LinkedHashMap<String, Resource>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            val name = node.getAttribute("name")
            when (node.tagName) {
                "string" -> result[name] = Resource.Str(node.textContent)
                "plurals" -> {
                    val items = LinkedHashMap<String, String>()
                    val itemNodes = node.getElementsByTagName("item")
                    for (j in 0 until itemNodes.length) {
                        val item = itemNodes.item(j) as Element
                        items[item.getAttribute("quantity")] = item.textContent
                    }
                    result[name] = Resource.Plural(items)
                }
            }
        }
        return result
    }

    private val paramPattern = Regex("""%(\d+)\$([sd])""")

    private fun params(text: String): Set<String> =
        paramPattern.findAll(text).map { "%${it.groupValues[1]}\$${it.groupValues[2]}" }.toSet()

    @Test
    fun `les deux fichiers portent exactement les memes cles`() {
        assertEquals(enResources.keys, frResources.keys)
    }

    @Test
    fun `aucune chaine simple n'est vide`() {
        val emptyKeys = (enResources.asSequence() + frResources.asSequence())
            .mapNotNull { (key, resource) -> if (resource is Resource.Str && resource.value.isBlank()) key else null }
            .toList()
        assertTrue("Clés avec une valeur vide : $emptyKeys", emptyKeys.isEmpty())
    }

    @Test
    fun `aucun item de plurals n'est vide`() {
        val emptyItems = (enResources.asSequence() + frResources.asSequence())
            .flatMap { (key, resource) ->
                if (resource is Resource.Plural) {
                    resource.items.filter { it.value.isBlank() }.keys.map { "$key/$it" }
                } else {
                    emptyList()
                }
            }
            .toList()
        assertTrue("Items de plurals avec une valeur vide : $emptyItems", emptyItems.isEmpty())
    }

    @Test
    fun `meme type de ressource pour chaque cle commune`() {
        val mismatches = enResources.keys.filter { key ->
            val en = enResources[key]
            val fr = frResources[key]
            (en is Resource.Str && fr is Resource.Plural) || (en is Resource.Plural && fr is Resource.Str)
        }
        assertTrue("Clés dont le type diffère entre en et fr : $mismatches", mismatches.isEmpty())
    }

    @Test
    fun `meme jeu de parametres pour chaque chaine simple`() {
        val mismatches = mutableListOf<String>()
        for ((key, en) in enResources) {
            val fr = frResources[key] ?: continue
            if (en is Resource.Str && fr is Resource.Str) {
                val enParams = params(en.value)
                val frParams = params(fr.value)
                if (enParams != frParams) mismatches += "$key : en=$enParams fr=$frParams"
            }
        }
        assertTrue("Paramètres incohérents : $mismatches", mismatches.isEmpty())
    }

    /**
     * RIC-191 (lot 4 i18n) : le français porte une quantité de plus que l'anglais, "many", que
     * CLDR y réserve aux multiples d'un million. Le générateur l'ajoute pour values-fr uniquement,
     * identique à "other", pour satisfaire Lint (MissingQuantity) sans inventer de traduction.
     * Elle est donc retirée avant de comparer les deux langues, et vérifiée à part ci-dessous.
     */
    private val frenchOnlyQuantities = setOf("many")

    @Test
    fun `memes quantites et memes parametres pour chaque plurals`() {
        val mismatches = mutableListOf<String>()
        for ((key, en) in enResources) {
            val fr = frResources[key] ?: continue
            if (en is Resource.Plural && fr is Resource.Plural) {
                val frQuantities = fr.items.keys - frenchOnlyQuantities
                if (en.items.keys != frQuantities) {
                    mismatches += "$key : quantités en=${en.items.keys} fr=$frQuantities"
                    continue
                }
                for (quantity in en.items.keys) {
                    val enParams = params(en.items.getValue(quantity))
                    val frParams = params(fr.items.getValue(quantity))
                    if (enParams != frParams) {
                        mismatches += "$key/$quantity : en=$enParams fr=$frParams"
                    }
                }
            }
        }
        assertTrue("Plurals incohérents : $mismatches", mismatches.isEmpty())
    }

    @Test
    fun `chaque plurals francais porte la quantite many`() {
        // RIC-191 : c'est ce que Lint réclamait (MissingQuantity, 29 avertissements). Le contrôle
        // est ici plutôt que dans le générateur seul : une régénération oubliée se verrait.
        val manquants = frResources
            .filterValues { it is Resource.Plural && "many" !in it.items }
            .keys
        assertTrue("Plurals fr sans quantité many : $manquants", manquants.isEmpty())
    }

    @Test
    fun `au moins autant de ressources que l'inventaire en compte`() {
        // 388 lignes dans l'inventaire v4 (RIC-187 : v3 avait 403, 15 clés "OK" fusionnées en une
        // seule common_ok_button) moins les clés volontairement non générées (bilan_month_initials_
        // array, voir generate_strings.py SKIPPED_KEYS), plus app_name fixe.
        val minimumAttendu = 388 - 1 + 1
        if (enResources.size < minimumAttendu) {
            fail("Seulement ${enResources.size} ressources générées, au moins $minimumAttendu attendues.")
        }
    }
}
