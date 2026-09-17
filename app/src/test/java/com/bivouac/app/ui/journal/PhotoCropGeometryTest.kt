package com.bivouac.app.ui.journal

import com.bivouac.app.data.photo.NormalizedCropRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-143 : la géométrie de l'éditeur « Ajuster ».
 *
 * Ce que ces tests tiennent, c'est la décision de conception du 2026-09-16 : un COIN met à
 * l'échelle en conservant les proportions, un CÔTÉ recadre librement, et dans les deux cas le cadre
 * ne sort jamais de l'image ni ne descend sous sa taille minimale. Tout le reste de l'éditeur est
 * du dessin.
 */
class PhotoCropGeometryTest {

    // Une image de 400 x 200 posée à 50 px du bord gauche d'un conteneur : décalée exprès, pour que
    // les tests attrapent un calcul qui oublierait l'origine des bornes.
    private val bounds = CropFrame(50f, 100f, 450f, 300f)
    private val minSide = 20f

    // --- Placement de l'image ------------------------------------------------------------------

    @Test
    fun `l'image est centree dans son conteneur en contenir`() {
        // Conteneur 1000x1000, image 2:1 : elle occupe toute la largeur et se centre verticalement.
        val fitted = fitInside(1000f, 1000f, 400f, 200f)

        assertEquals(CropFrame(0f, 250f, 1000f, 750f), fitted)
    }

    @Test
    fun `une image plus haute que large est centree horizontalement`() {
        val fitted = fitInside(1000f, 1000f, 200f, 400f)

        assertEquals(CropFrame(250f, 0f, 750f, 1000f), fitted)
    }

    // --- Saisie d'une poignée ------------------------------------------------------------------

    @Test
    fun `un contact pres d'un coin saisit le coin, pas le cote`() {
        val frame = CropFrame(100f, 150f, 300f, 250f)

        assertEquals(CropHandle.TOP_LEFT, cropHandleAt(104f, 152f, frame, touchRadius = 22f))
        assertEquals(CropHandle.BOTTOM_RIGHT, cropHandleAt(298f, 254f, frame, touchRadius = 22f))
    }

    @Test
    fun `un contact le long d'un bord saisit le cote, ou qu'il soit sur ce bord`() {
        val frame = CropFrame(100f, 150f, 300f, 250f)

        // Au milieu du bord, là où la barre est dessinée...
        assertEquals(CropHandle.LEFT, cropHandleAt(98f, 200f, frame, touchRadius = 22f))
        // ...mais aussi ailleurs sur le même bord : la barre dit où est la poignée, elle n'est pas
        // la seule façon de l'atteindre.
        assertEquals(CropHandle.RIGHT, cropHandleAt(305f, 220f, frame, touchRadius = 22f))
        assertEquals(CropHandle.BOTTOM, cropHandleAt(200f, 245f, frame, touchRadius = 22f))
    }

    @Test
    fun `un contact au milieu du cadre saisit l'interieur, pour deplacer le cadre en bloc`() {
        val frame = CropFrame(100f, 150f, 300f, 250f)

        // RIC-179 : un glissement loin de tout bord déplace désormais le cadre entier, plutôt que
        // de ne rien faire.
        assertEquals(CropHandle.INSIDE, cropHandleAt(200f, 200f, frame, touchRadius = 22f))
    }

    @Test
    fun `un contact hors du cadre ne saisit rien`() {
        val frame = CropFrame(100f, 150f, 300f, 250f)

        assertNull(cropHandleAt(50f, 50f, frame, touchRadius = 22f))
    }

    // --- Coins : proportions conservées ---------------------------------------------------------

    @Test
    fun `tirer un coin conserve les proportions du cadre`() {
        val frame = CropFrame(100f, 150f, 300f, 250f) // 200 x 100, donc 2:1

        val dragged = dragCropHandle(frame, bounds, CropHandle.TOP_LEFT, dx = -40f, dy = -20f, minSide)

        assertEquals(2f, dragged.width / dragged.height, 1e-3f)
        // Le coin opposé n'a pas bougé d'un pixel : c'est lui le centre de l'homothétie.
        assertEquals(300f, dragged.right, 1e-3f)
        assertEquals(250f, dragged.bottom, 1e-3f)
        assertTrue("le cadre doit avoir grandi", dragged.width > frame.width)
    }

    @Test
    fun `tirer un coin perpendiculairement a la diagonale ne change rien`() {
        val frame = CropFrame(100f, 150f, 300f, 250f)

        // La diagonale du coin haut-gauche vaut (-200, -100) depuis le coin opposé ; (1, -2) lui est
        // perpendiculaire. C'est la propriété qui rend le geste prévisible : seule la composante qui
        // « pousse » le long de la diagonale agrandit ou rétrécit.
        val dragged = dragCropHandle(frame, bounds, CropHandle.TOP_LEFT, dx = 10f, dy = -20f, minSide)

        assertEquals(frame.left, dragged.left, 1e-3f)
        assertEquals(frame.top, dragged.top, 1e-3f)
    }

    @Test
    fun `chaque coin met a l'echelle autour du coin oppose`() {
        val frame = CropFrame(100f, 150f, 300f, 250f)

        val topRight = dragCropHandle(frame, bounds, CropHandle.TOP_RIGHT, dx = 20f, dy = -10f, minSide)
        assertEquals(100f, topRight.left, 1e-3f)
        assertEquals(250f, topRight.bottom, 1e-3f)
        assertEquals(2f, topRight.width / topRight.height, 1e-3f)

        val bottomLeft = dragCropHandle(frame, bounds, CropHandle.BOTTOM_LEFT, dx = -20f, dy = 10f, minSide)
        assertEquals(300f, bottomLeft.right, 1e-3f)
        assertEquals(150f, bottomLeft.top, 1e-3f)
        assertEquals(2f, bottomLeft.width / bottomLeft.height, 1e-3f)
    }

    @Test
    fun `un coin tire au-dela de l'image bute sur le bord sans en sortir`() {
        val frame = CropFrame(100f, 150f, 300f, 250f)

        val dragged = dragCropHandle(frame, bounds, CropHandle.TOP_LEFT, dx = -5000f, dy = -5000f, minSide)

        assertTrue(dragged.left >= bounds.left - 1e-3f)
        assertTrue(dragged.top >= bounds.top - 1e-3f)
        // Et les proportions tiennent toujours : buter n'est pas déformer.
        assertEquals(2f, dragged.width / dragged.height, 1e-3f)
    }

    @Test
    fun `un coin tire vers l'interieur s'arrete a la taille minimale`() {
        val frame = CropFrame(100f, 150f, 300f, 250f)

        val dragged = dragCropHandle(frame, bounds, CropHandle.TOP_LEFT, dx = 5000f, dy = 5000f, minSide)

        assertTrue("largeur : ${dragged.width}", dragged.width >= minSide - 1e-3f)
        assertTrue("hauteur : ${dragged.height}", dragged.height >= minSide - 1e-3f)
        assertEquals(2f, dragged.width / dragged.height, 1e-3f)
        // Jamais de retournement : le cadre ne traverse pas son coin d'ancrage.
        assertTrue(dragged.left < dragged.right)
        assertTrue(dragged.top < dragged.bottom)
    }

    // --- Côtés : recadrage libre ----------------------------------------------------------------

    @Test
    fun `tirer un cote ne bouge qu'une dimension`() {
        val frame = CropFrame(100f, 150f, 300f, 250f)

        val dragged = dragCropHandle(frame, bounds, CropHandle.LEFT, dx = -30f, dy = 999f, minSide)

        assertEquals(70f, dragged.left, 1e-3f)
        assertEquals(150f, dragged.top, 1e-3f)
        assertEquals(300f, dragged.right, 1e-3f)
        assertEquals(250f, dragged.bottom, 1e-3f)
    }

    @Test
    fun `un cote tire au-dela de l'image bute sur le bord`() {
        val frame = CropFrame(100f, 150f, 300f, 250f)

        assertEquals(
            bounds.left,
            dragCropHandle(frame, bounds, CropHandle.LEFT, dx = -5000f, dy = 0f, minSide).left,
            1e-3f,
        )
        assertEquals(
            bounds.bottom,
            dragCropHandle(frame, bounds, CropHandle.BOTTOM, dx = 0f, dy = 5000f, minSide).bottom,
            1e-3f,
        )
    }

    @Test
    fun `un cote pousse vers le bord oppose s'arrete a la taille minimale`() {
        val frame = CropFrame(100f, 150f, 300f, 250f)

        val dragged = dragCropHandle(frame, bounds, CropHandle.RIGHT, dx = -5000f, dy = 0f, minSide)

        assertEquals(frame.left + minSide, dragged.right, 1e-3f)
    }

    // --- Intérieur : déplacer le cadre entier (RIC-179) -----------------------------------------

    @Test
    fun `tirer l'interieur translate le cadre sans changer sa taille`() {
        val frame = CropFrame(150f, 150f, 250f, 200f) // 100 x 50, loin de tout bord de `bounds`

        val dragged = dragCropHandle(frame, bounds, CropHandle.INSIDE, dx = 30f, dy = -20f, minSide)

        assertEquals(180f, dragged.left, 1e-3f)
        assertEquals(130f, dragged.top, 1e-3f)
        assertEquals(frame.width, dragged.width, 1e-3f)
        assertEquals(frame.height, dragged.height, 1e-3f)
    }

    @Test
    fun `un deplacement bute sur chacun des quatre bords sans deformer le cadre`() {
        val frame = CropFrame(150f, 150f, 250f, 200f) // 100 x 50

        val left = dragCropHandle(frame, bounds, CropHandle.INSIDE, dx = -5000f, dy = 0f, minSide)
        assertEquals(bounds.left, left.left, 1e-3f)
        assertEquals(frame.width, left.width, 1e-3f)
        assertEquals(frame.height, left.height, 1e-3f)

        val right = dragCropHandle(frame, bounds, CropHandle.INSIDE, dx = 5000f, dy = 0f, minSide)
        assertEquals(bounds.right, right.right, 1e-3f)
        assertEquals(frame.width, right.width, 1e-3f)
        assertEquals(frame.height, right.height, 1e-3f)

        val top = dragCropHandle(frame, bounds, CropHandle.INSIDE, dx = 0f, dy = -5000f, minSide)
        assertEquals(bounds.top, top.top, 1e-3f)
        assertEquals(frame.width, top.width, 1e-3f)
        assertEquals(frame.height, top.height, 1e-3f)

        val bottom = dragCropHandle(frame, bounds, CropHandle.INSIDE, dx = 0f, dy = 5000f, minSide)
        assertEquals(bounds.bottom, bottom.bottom, 1e-3f)
        assertEquals(frame.width, bottom.width, 1e-3f)
        assertEquals(frame.height, bottom.height, 1e-3f)
    }

    @Test
    fun `un deplacement en diagonale butee sur deux bords a la fois ne deforme pas le cadre`() {
        val frame = CropFrame(150f, 150f, 250f, 200f)

        val dragged = dragCropHandle(frame, bounds, CropHandle.INSIDE, dx = -5000f, dy = -5000f, minSide)

        assertEquals(bounds.left, dragged.left, 1e-3f)
        assertEquals(bounds.top, dragged.top, 1e-3f)
        assertEquals(frame.width, dragged.width, 1e-3f)
        assertEquals(frame.height, dragged.height, 1e-3f)
    }

    @Test
    fun `un deplacement se comporte pareil sur des bornes portrait que paysage`() {
        // Bornes portrait : ce que fitInside produit une fois l'image tournée d'un quart de tour.
        // Rien dans le déplacement du cadre ne doit supposer une orientation paysage.
        val portraitBounds = CropFrame(100f, 50f, 300f, 450f) // 200 x 400
        val frame = CropFrame(150f, 100f, 250f, 150f) // 100 x 50, proche du bord haut

        val dragged = dragCropHandle(frame, portraitBounds, CropHandle.INSIDE, dx = 0f, dy = -5000f, minSide)

        assertEquals(portraitBounds.top, dragged.top, 1e-3f)
        assertEquals(frame.width, dragged.width, 1e-3f)
        assertEquals(frame.height, dragged.height, 1e-3f)
    }

    // --- Aller-retour écran / base ---------------------------------------------------------------

    @Test
    fun `le cadre ecran et le rectangle normalise se convertissent dans les deux sens`() {
        val frame = CropFrame(150f, 150f, 350f, 250f)

        val normalized = frame.toNormalized(bounds)

        assertEquals(0.25f, normalized.left, 1e-4f)
        assertEquals(0.25f, normalized.top, 1e-4f)
        assertEquals(0.75f, normalized.right, 1e-4f)
        assertEquals(0.75f, normalized.bottom, 1e-4f)

        val back = normalized.toFrame(bounds)
        assertEquals(frame.left, back.left, 1e-3f)
        assertEquals(frame.top, back.top, 1e-3f)
        assertEquals(frame.right, back.right, 1e-3f)
        assertEquals(frame.bottom, back.bottom, 1e-3f)
    }

    @Test
    fun `l'image entiere donne le rectangle plein cadre`() {
        assertEquals(NormalizedCropRect.FULL, bounds.toNormalized(bounds))
        assertEquals(bounds, NormalizedCropRect.FULL.toFrame(bounds))
    }
}
