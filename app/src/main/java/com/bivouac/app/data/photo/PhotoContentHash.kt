package com.bivouac.app.data.photo

import java.io.InputStream
import java.security.MessageDigest

/**
 * RIC-157 : l'empreinte SHA-256 d'une photo, en hexadécimal minuscule.
 *
 * Extraite de LoggedTrackRepository (RIC-43) parce qu'elle a désormais deux usages qui DOIVENT
 * coïncider au caractère près : ce qui est écrit dans
 * [com.bivouac.app.data.db.LoggedTrackPhotoEntity.contentHash] à l'import, et ce que
 * [PhotoOriginalResolver] recalcule sur un candidat de la galerie pour confirmer que c'est bien
 * l'original. Deux implémentations séparées finiraient par diverger sur un détail de format, et
 * la re-résolution ne retrouverait plus jamais rien, sans la moindre erreur visible.
 *
 * Par flux et non par tableau d'octets : une photo pèse quelques Mo, elle n'a pas à être chargée
 * entière en mémoire pour être hachée.
 */
object PhotoContentHash {

    fun of(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
