package app.cloudbase.map

/** A slippy-map tile server: [urlTemplate] contains {z} {x} {y}. */
class TileSource(
    val id: String,
    val urlTemplate: String,
    val minZoom: Int,
    val maxZoom: Int,
    val attribution: String,
) {
    fun url(z: Int, x: Int, y: Int): String =
        urlTemplate.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())

    companion object {
        val OPEN_TOPO = TileSource(
            "opentopo", "https://tile.opentopomap.org/{z}/{x}/{y}.png", 2, 17,
            "© OpenStreetMap contributors, SRTM · OpenTopoMap (CC-BY-SA)",
        )
        val TERRARIUM = TileSource(
            "terrarium", "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png", 0, 13,
            "Mapzen terrain tiles",
        )
    }
}
