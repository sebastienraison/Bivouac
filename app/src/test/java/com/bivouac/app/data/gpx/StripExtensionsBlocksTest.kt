package com.bivouac.app.data.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Couvre le remplacement de la regex EXTENSIONS_BLOCK par le balayage linéaire (RIC-95) :
// mêmes retraits qu'avant sur les formes réelles, et temps borné sur les entrées forgées qui
// rendaient la regex quadratique. Chaînes minimales construites pour le transform, pas des
// fixtures de traces réelles.
class StripExtensionsBlocksTest {

    @Test
    fun stripsARegularBlock() {
        val xml = "<trkpt lat=\"45\" lon=\"6\"><extensions><hr>120</hr></extensions><ele>1200</ele></trkpt>"
        assertEquals("<trkpt lat=\"45\" lon=\"6\"><ele>1200</ele></trkpt>", stripExtensionsBlocks(xml))
    }

    @Test
    fun stripsSelfClosingBlocks() {
        assertEquals("<a></a>", stripExtensionsBlocks("<a><extensions/></a>"))
        assertEquals("<a></a>", stripExtensionsBlocks("<a><extensions /></a>"))
        assertEquals("<a></a>", stripExtensionsBlocks("<a><extensions ns=\"garmin\"/></a>"))
    }

    @Test
    fun stripsEveryBlockOfAMultiPointTrack() {
        val xml = "<p1><extensions><hr>1</hr></extensions></p1><p2><extensions><hr>2</hr></extensions></p2>"
        assertEquals("<p1></p1><p2></p2>", stripExtensionsBlocks(xml))
    }

    @Test
    fun blockRunsToTheFirstCloserOnly() {
        // Même sémantique que l'ancienne regex non-greedy : un </extensions> excédentaire reste.
        val xml = "<a><extensions><extensions></extensions></extensions></a>"
        assertEquals("<a></extensions></a>", stripExtensionsBlocks(xml))
    }

    @Test
    fun leavesAnUnclosedBlockVerbatim() {
        val xml = "<a><extensions><hr>120</hr></a>"
        assertEquals(xml, stripExtensionsBlocks(xml))
    }

    @Test
    fun doesNotTouchOtherElementsSharingThePrefix() {
        val xml = "<extensionsCustom>x</extensionsCustom>"
        assertEquals(xml, stripExtensionsBlocks(xml))
    }

    @Test
    fun handlesBlockSpanningLines() {
        val xml = "<a><extensions\n  xmlns=\"g\">\n<hr>120</hr>\n</extensions></a>"
        assertEquals("<a></a>", stripExtensionsBlocks(xml))
    }

    // L'entrée qui rendait la regex quadratique : des milliers d'ouvertures jamais refermées.
    // Sans borne de temps stricte en JUnit 4 simple, on vérifie surtout que ça termine vite et
    // rend l'entrée telle quelle (la regex mettait plusieurs dizaines de secondes bien avant
    // cette taille).
    @Test
    fun forgedManyUnclosedOpeningsStaysLinear() {
        val xml = "<extensions a>".repeat(200_000)
        val startedAt = System.nanoTime()
        val result = stripExtensionsBlocks(xml)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals(xml, result)
        assertTrue("stripExtensionsBlocks a pris ${elapsedMs}ms", elapsedMs < 2_000)
    }
}
