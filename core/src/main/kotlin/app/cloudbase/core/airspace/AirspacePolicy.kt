package app.cloudbase.core.airspace

enum class Relevance { ALERT, SHOW, HIDE }

/** Decides which airspaces a paraglider should be warned about, merely shown, or never bothered with. */
class AirspacePolicy(
    val alertKinds: Set<String> = DEFAULT_ALERT_KINDS,
    val hideKinds: Set<String> = DEFAULT_HIDE_KINDS,
    /** Hide anything whose floor is above this (metres, compared datum-naively); paragliders never get there. */
    val ceilingOfInterestM: Double = 6000.0,
    val ignoredNames: Set<String> = emptySet(),
    /**
     * Alert for mandatory broadcast zones too. Off by default: an MBZ is uncontrolled airspace
     * you may enter, provided you make the radio calls.
     */
    val alertMbz: Boolean = false,
) {
    fun relevance(a: Airspace): Relevance {
        if (a.name in ignoredNames) return Relevance.HIDE
        if (a.lower.metres > ceilingOfInterestM) return Relevance.HIDE
        nzRelevance(a.name)?.let { return it }
        val byType = a.type in alertKinds
        val byClass = a.cls in alertKinds
        // Hidden kinds (FIR, FIS sectors...) are clutter. The one exception is an airway with a
        // controlled class: a class C airway at a reachable height is somewhere a paraglider
        // must not be, so there the class wins.
        val controlledAirway = byClass && a.type == "AWY"
        if (!controlledAirway && (a.type in hideKinds || a.cls in hideKinds)) return Relevance.HIDE
        if (!byType && !byClass) return Relevance.SHOW
        // Common-frequency / mandatory-broadcast zones carry a class label in some exports but
        // are not controlled. Only the class label is overridden: an explicit type still alerts.
        if (!byType && hasWord(a.name, "CFZ")) return Relevance.HIDE
        if (!byType && hasWord(a.name, "MBZ")) return if (alertMbz) Relevance.ALERT else Relevance.SHOW
        return Relevance.ALERT
    }

    /**
     * New Zealand designators say what an airspace is more reliably than the class in the
     * openAIP export, which labels every mandatory broadcast zone class A and every common
     * frequency zone class B. "NZA756" is controlled airspace, "NZB..." an MBZ, and so on.
     * Returns null for anything that is not an NZ designator.
     */
    private fun nzRelevance(name: String): Relevance? {
        if (name.length < 4 || !name.startsWith("NZ")) return null
        val digit = if (name[3] == ' ' && name.length > 4) name[4] else name[3]
        if (!digit.isDigit()) return null
        return when (name[2]) {
            'A' -> Relevance.ALERT // control areas and zones
            'R', 'M' -> Relevance.ALERT // restricted, military operating: entry needs approval
            'D', 'V' -> Relevance.ALERT // danger, volcanic hazard
            'L', 'P' -> Relevance.ALERT // low flying zones, parachute drop zones
            'B' -> if (alertMbz) Relevance.ALERT else Relevance.SHOW // mandatory broadcast zones
            'C' -> Relevance.HIDE // common frequency zones: a radio frequency, not a boundary
            'G', 'T' -> Relevance.SHOW // general aviation areas, transit lanes
            else -> null
        }
    }

    companion object {
        val DEFAULT_ALERT_KINDS: Set<String> = setOf(
            "A", "B", "C", "D",
            "CTR", "R", "P", "Q", "GP", "TMZ", "RMZ", "ATZ", "MATZ", "TSA", "TRA", "TFR", "ADIZ",
        )
        val DEFAULT_HIDE_KINDS: Set<String> = setOf("FIR", "UIR", "FIS", "ACC", "VFRSEC", "AWY")

        /** True if [word] occurs in [text] delimited by non-alphanumerics (case-insensitive). */
        private fun hasWord(text: String, word: String): Boolean {
            var from = 0
            while (true) {
                val i = text.indexOf(word, from, ignoreCase = true)
                if (i < 0) return false
                val j = i + word.length
                if ((i == 0 || !text[i - 1].isLetterOrDigit()) && (j == text.length || !text[j].isLetterOrDigit())) return true
                from = i + 1
            }
        }
    }
}
