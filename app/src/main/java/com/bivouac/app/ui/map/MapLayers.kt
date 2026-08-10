package com.bivouac.app.ui.map

import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex

// Esri's tile REST endpoint expects z/y/x, unlike the z/x/y convention osmdroid's built-in
// XYTileSource always builds — confirmed against both sources rather than assumed, since the two
// conventions are easy to mix up and silently fetch mismatched tiles.
private val EsriWorldImagery: ITileSource = object : OnlineTileSourceBase(
    "EsriWorldImagery",
    0,
    19,
    256,
    "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    "Esri, Maxar, Earthstar Geographics",
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "$baseUrl$zoom/$y/$x"
    }
}

enum class MapLayer(val label: String, val tileSource: ITileSource) {
    STANDARD("Standard", TileSourceFactory.MAPNIK),
    HIKING("Randonnée", TileSourceFactory.OpenTopo),
    SATELLITE("Satellite", EsriWorldImagery),
}
