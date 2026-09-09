package com.bivouac.app.data.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RIC-157 : la fabrication réelle d'une copie réduite, pixels compris.
 *
 * Instrumenté et non JVM : Robolectric ne décode ni ne réencode d'image, ses ombres rendent des
 * bitmaps de complaisance. Ce qui se vérifie ici (définition obtenue, EXIF recopié, GPS absent) ne
 * peut donc l'être que sur un vrai décodeur, c'est-à-dire sur l'émulateur jetable.
 */
@RunWith(AndroidJUnit4::class)
class PhotoReducerInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val workDir = File(context.cacheDir, "photo-reducer-test").apply { mkdirs() }

    @After
    fun cleanUp() {
        workDir.deleteRecursively()
    }

    /**
     * Un JPEG de [width] x [height] écrit sur disque, avec un EXIF minimal : date de prise de vue,
     * décalage horaire, orientation, et des coordonnées GPS.
     *
     * Le GPS est mis là exprès : c'est justement ce qui ne doit PAS se retrouver dans la copie.
     * (Une photo réelle passée par MediaStore arrive déjà expurgée, mais le fichier de test, lui,
     * est lu directement : il porte donc le pire cas.)
     */
    private fun writeSourceJpeg(name: String, width: Int, height: Int): File {
        val file = File(workDir, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        } finally {
            bitmap.recycle()
        }
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:06:14 09:32:11")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+02:00")
            // 6 = rotation de 90° dans le sens horaire : l'orientation la plus courante d'une photo
            // prise en portrait, et celle dont la perte se voit immédiatement.
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setLatLong(45.1885, 5.7245)
            saveAttributes()
        }
        return file
    }

    private fun dimensionsOf(file: File): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        return bounds.outWidth to bounds.outHeight
    }

    @Test
    fun aPhotoAboveTheTargetIsPlannedForReductionAndCappedAtTheLongSide() {
        val source = writeSourceJpeg("large.jpg", 4032, 3024)
        val uri = Uri.fromFile(source)
        assertEquals(PhotoCopyPlan.REDUCE, PhotoReducer.planFor(resolver, uri))

        val target = File(workDir, "large-reduced.jpg")
        assertTrue(PhotoReducer.writeReduced(resolver, uri, target))

        val (width, height) = dimensionsOf(target)
        assertEquals(PhotoStoragePolicy.REDUCED_LONG_SIDE_PX, width)
        assertEquals(1536, height)
        // Le but du ticket, mesuré et pas supposé : la copie doit peser une fraction de l'original.
        assertTrue(
            "copie réduite ${target.length()} o, original ${source.length()} o",
            target.length() < source.length() / 2,
        )
    }

    @Test
    fun theReducedCopyKeepsTheShootingTimeAndOrientationButNeverTheGps() {
        val source = writeSourceJpeg("exif.jpg", 3000, 2000)
        val target = File(workDir, "exif-reduced.jpg")
        assertTrue(PhotoReducer.writeReduced(resolver, Uri.fromFile(source), target))

        val exif = ExifInterface(target)
        assertEquals("2026:06:14 09:32:11", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("+02:00", exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
        assertEquals(
            ExifInterface.ORIENTATION_ROTATE_90,
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED),
        )
        // Point acquis du projet : la copie locale n'a jamais porté de GPS dans son EXIF, la
        // position vit dénormalisée en base. Le réencodage ne doit pas en introduire.
        assertNull("la copie réduite ne doit porter aucune coordonnée", exif.latLong)
    }

    // Le pixel du portrait ne doit pas être redressé par la réduction : BitmapFactory ne tourne
    // rien, et c'est le tag d'orientation recopié qui porte le sens. Redresser les pixels ET garder
    // le tag ferait une photo tournée deux fois.
    @Test
    fun aPortraitSourceStaysUnrotatedWithItsTagCarriedOver() {
        val source = writeSourceJpeg("portrait.jpg", 2400, 3600)
        val target = File(workDir, "portrait-reduced.jpg")
        assertTrue(PhotoReducer.writeReduced(resolver, Uri.fromFile(source), target))

        val (width, height) = dimensionsOf(target)
        assertEquals(1365, width)
        assertEquals(PhotoStoragePolicy.REDUCED_LONG_SIDE_PX, height)
    }

    @Test
    fun aPhotoAlreadyUnderTheTargetIsPlannedForARawCopy() {
        val source = writeSourceJpeg("small.jpg", 1600, 1200)
        assertEquals(PhotoCopyPlan.COPY_ALREADY_SMALL, PhotoReducer.planFor(resolver, Uri.fromFile(source)))
    }

    @Test
    fun aPhotoExactlyAtTheTargetIsPlannedForARawCopyToo() {
        val source = writeSourceJpeg("exact.jpg", PhotoStoragePolicy.REDUCED_LONG_SIDE_PX, 1024)
        assertEquals(PhotoCopyPlan.COPY_ALREADY_SMALL, PhotoReducer.planFor(resolver, Uri.fromFile(source)))
    }

    // Un fichier qui n'est pas une image du tout : le plan doit dire « copie brute, mode FULL »
    // sans lever, et une réduction tentée quand même doit rendre false en laissant la place nette.
    @Test
    fun anUndecodableFileFallsBackToARawCopyWithoutThrowing() {
        val source = File(workDir, "broken.jpg").apply { writeText("ceci n'est pas une image") }
        val uri = Uri.fromFile(source)
        assertEquals(PhotoCopyPlan.COPY_UNDECODABLE, PhotoReducer.planFor(resolver, uri))

        val target = File(workDir, "broken-reduced.jpg")
        assertFalse(PhotoReducer.writeReduced(resolver, uri, target))
        assertFalse("aucun fichier ne doit rester derrière un échec", target.exists())
    }
}
