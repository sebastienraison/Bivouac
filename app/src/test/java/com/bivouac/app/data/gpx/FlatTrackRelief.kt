package com.bivouac.app.data.gpx

/**
 * RIC-231 : depuis qu'une trace dont toutes les altitudes sont identiques est « sans altitude »
 * ([ElevationAvailability]), une fixture « plate » à 1000.0 partout n'a plus de relief du tout : sa
 * série lissée est nulle, ses tronçons aussi. Les fixtures qui veulent une trace plate AVEC
 * altitude y ajoutent ce relief négligeable (1 mm par kilomètre, soit 2 mm sur un jour de 2 km) :
 * assez pour que les altitudes ne soient pas identiques, trop peu pour changer une pente, un D+ ou
 * une classe. Une fixture qui veut vraiment une trace sans relief exploitable reste constante.
 */
internal fun flatTrackRelief(distanceMeters: Double): Double = distanceMeters * 1e-6
