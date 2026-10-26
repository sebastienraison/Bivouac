package com.bivouac.app.data.db

import java.time.Instant

/**
 * RIC-41 : dans quel ordre les fichiers d'un import multi-jours deviennent les jours 1..N.
 *
 * L'ordre de sélection n'est pas fiable : plusieurs sélecteurs système Android ne conservent pas
 * l'ordre des taps (certains rendent la liste triée alphabétiquement), donc s'y fier revient à
 * numéroter les jours au hasard. La référence est donc l'horodatage du premier point GPX de
 * chaque fichier.
 *
 * Repli assumé (RIC-101) : dès qu'un seul fichier du lot n'a pas d'horodatage (GPX n'en impose
 * aucun), l'horodatage cesse d'être une référence pour tout le lot, plutôt que d'intercaler ce
 * fichier à une position devinée. Le lot est alors trié par ordre naturel des noms de fichiers
 * (voir [naturalCompare] : « jour-2 » avant « jour-10 », casse ignorée), qui est ce que l'on
 * numérote soi-même pour ordonner des étapes ; l'ordre de sélection ne départage plus que les
 * noms égaux. Un lot entièrement sans horodatage suit la même règle. Quand tous les fichiers ont
 * un horodatage, les noms ne servent à rien.
 */
object ImportDayOrdering {

    /**
     * Les indices de [firstTimestamps] réordonnés en ordre de jour : le premier élément du
     * résultat est la position, dans la sélection d'origine, du fichier qui devient le jour 1.
     * [fileNames] donne le nom de chaque fichier, dans le même ordre que [firstTimestamps] (chaîne
     * vide quand le fournisseur n'en rend aucun).
     *
     * Le tri est stable dans les deux cas : deux fichiers au même horodatage (ou, faute
     * d'horodatage quelque part, au même nom) gardent leur ordre de sélection.
     */
    fun orderIndices(firstTimestamps: List<Instant?>, fileNames: List<String>): List<Int> {
        require(firstTimestamps.size == fileNames.size) {
            "Un nom de fichier par horodatage attendu : ${fileNames.size} noms pour ${firstTimestamps.size} fichiers"
        }
        val selectionOrder = firstTimestamps.indices.toList()
        if (firstTimestamps.any { it == null }) {
            return selectionOrder.sortedWith { a, b -> naturalCompare(fileNames[a], fileNames[b]) }
        }
        return selectionOrder.sortedBy { firstTimestamps[it] }
    }

    /**
     * Comparaison « naturelle » de deux noms : les suites de chiffres se comparent par leur valeur
     * numérique (« jour-2 » < « jour-10 »), le reste caractère par caractère sans tenir compte de
     * la casse. Les suites de chiffres sont comparées en texte (zéros de tête retirés, puis
     * longueur, puis ordre lexicographique) plutôt que converties : aucune limite de taille, pas
     * de dépassement d'entier sur un nom qui porte un long horodatage.
     *
     * Deux suites de même valeur numérique (« 01 » et « 1 ») sont égales ici ; la comparaison
     * continue sur la suite du nom. Un nom qui n'est que le début de l'autre passe avant lui.
     */
    internal fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca in '0'..'9' && cb in '0'..'9') {
                val endA = digitRunEnd(a, i)
                val endB = digitRunEnd(b, j)
                val digitsA = a.substring(i, endA).trimStart('0')
                val digitsB = b.substring(j, endB).trimStart('0')
                if (digitsA.length != digitsB.length) return digitsA.length.compareTo(digitsB.length)
                val byValue = digitsA.compareTo(digitsB)
                if (byValue != 0) return byValue
                i = endA
                j = endB
            } else {
                val la = ca.lowercaseChar()
                val lb = cb.lowercaseChar()
                if (la != lb) return la.compareTo(lb)
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun digitRunEnd(text: String, start: Int): Int {
        var end = start
        while (end < text.length && text[end] in '0'..'9') end++
        return end
    }
}
