package app.cloudbase.core.airspace

/** Spatial lookup over a loaded airspace set. A linear bbox scan is plenty at a few thousand airspaces. */
class AirspaceIndex(val all: List<Airspace>) {
    /**
     * Airspaces whose bounding box comes within [radiusM] of the point (a superset of those whose
     * polygon does). The east-west scale is approximate to well under 1 %.
     */
    fun near(lat: Double, lon: Double, radiusM: Double): List<Airspace> {
        val result = ArrayList<Airspace>()
        for (a in all) if (a.bboxDistanceM(lat, lon) <= radiusM) result.add(a)
        return result
    }
}
