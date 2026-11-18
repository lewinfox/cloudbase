package app.cloudbase.core.airspace

enum class Relevance { ALERT, SHOW, HIDE }

/** Decides which airspaces a paraglider should be warned about, merely shown, or never bothered with. */
class AirspacePolicy(
    val alertKinds: Set<String> = DEFAULT_ALERT_KINDS,
    val hideKinds: Set<String> = DEFAULT_HIDE_KINDS,
    /** Hide anything whose floor is above this (metres, compared datum-naively); paragliders never get there. */
    val ceilingOfInterestM: Double = 6000.0,
    val ignoredNames: Set<String> = emptySet(),
) {
    fun relevance(a: Airspace): Relevance {
        if (a.name in ignoredNames) return Relevance.HIDE
        if (a.type in hideKinds || a.cls in hideKinds) return Relevance.HIDE
        if (a.lower.metres > ceilingOfInterestM) return Relevance.HIDE
        val byType = a.type in alertKinds
        val byClass = a.cls in alertKinds
        if (!byType && !byClass) return Relevance.SHOW
        // NZ common-frequency / mandatory-broadcast zones are labelled class B by openAIP but are
        // not controlled. Only the class label is overridden: an explicit type still alerts.
        if (!byType && (hasWord(a.name, "CFZ") || hasWord(a.name, "MBZ"))) return Relevance.SHOW
        return Relevance.ALERT
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
