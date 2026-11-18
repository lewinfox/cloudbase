package app.cloudbase.core.airspace

import app.cloudbase.core.airspace.TestAirspaces.box
import app.cloudbase.core.airspace.TestAirspaces.fl
import app.cloudbase.core.airspace.TestAirspaces.gnd
import app.cloudbase.core.airspace.TestAirspaces.lat
import app.cloudbase.core.airspace.TestAirspaces.lon
import app.cloudbase.core.airspace.TestAirspaces.msl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirspaceIndexTest {
    private val a = box("a", "D", "CTR", gnd, msl(1000.0), -1000.0, -1000.0, 1000.0, 1000.0)
    private val b = box("b", "D", "CTR", gnd, msl(1000.0), 5000.0, -1000.0, 7000.0, 1000.0)
    private val c = box("c", "D", "CTR", gnd, msl(1000.0), -1000.0, 20_000.0, 1000.0, 22_000.0)
    private val index = AirspaceIndex(listOf(a, b, c))

    private fun names(x: Double, y: Double, r: Double) =
        index.near(lat(y), lon(x), r).map { it.name }

    @Test
    fun insideBboxIsAlwaysNear() {
        assertEquals(listOf("a"), names(0.0, 0.0, 1.0))
    }

    @Test
    fun radiusIsMeasuredToTheBoundingBox() {
        // Point 3000 m east of a's east edge and 1000 m west of b's west edge.
        assertEquals(emptyList<String>(), names(4000.0, 0.0, 900.0))
        assertEquals(listOf("b"), names(4000.0, 0.0, 1100.0))
        assertEquals(listOf("a", "b"), names(4000.0, 0.0, 3100.0))
    }

    @Test
    fun cornerDistanceIsEuclideanNotPerAxis() {
        // 3000 m east and 4000 m north of a's NE corner: 5000 m from the box.
        assertTrue(names(4000.0, 5000.0, 4900.0).none { it == "a" })
        assertTrue(names(4000.0, 5000.0, 5100.0).contains("a"))
    }

    @Test
    fun nothingNearReturnsEmpty() {
        assertTrue(names(-50_000.0, -50_000.0, 1000.0).isEmpty())
        assertEquals(3, index.all.size)
    }

    @Test
    fun farNorthIsFoundByLatitudeGap() {
        assertEquals(listOf("c"), names(0.0, 18_000.0, 2100.0))
    }
}

class AirspacePolicyTest {
    private val policy = AirspacePolicy()

    private fun asp(
        cls: String, type: String = "", name: String = "X", lower: AltLimit = gnd, upper: AltLimit = fl(100),
    ) = box(name, cls, type, lower, upper, -1000.0, -1000.0, 1000.0, 1000.0)

    private fun rel(cls: String, type: String = "", name: String = "X", lower: AltLimit = gnd) =
        policy.relevance(asp(cls, type, name, lower))

    @Test
    fun controlledClassesAlert() {
        for (c in listOf("A", "B", "C", "D")) assertEquals(c, Relevance.ALERT, rel(c))
        assertEquals(Relevance.ALERT, rel("D", "TMA"))
        assertEquals(Relevance.ALERT, rel("C", "CTA"))
    }

    @Test
    fun alertTypesAlertWhateverTheClass() {
        for (t in listOf("CTR", "R", "P", "Q", "GP", "TMZ", "RMZ", "ATZ", "MATZ", "TSA", "TRA", "TFR", "ADIZ")) {
            assertEquals("type $t", Relevance.ALERT, rel("UNC", t))
            assertEquals("class $t", Relevance.ALERT, rel(t))
            assertEquals("type $t over class E", Relevance.ALERT, rel("E", t))
        }
    }

    @Test
    fun hideKinds() {
        for (t in listOf("FIR", "UIR", "FIS", "ACC", "VFRSEC", "AWY")) {
            assertEquals("type $t", Relevance.HIDE, rel("UNC", t))
            assertEquals("class $t", Relevance.HIDE, rel(t))
        }
        // A controlled class wins over a clutter type: a class C airway is not somewhere to be.
        assertEquals(Relevance.ALERT, rel("C", "AWY"))
        assertEquals(Relevance.HIDE, rel("E", "AWY"))
        // ...but for everything else hide wins over an alerting class.
        assertEquals(Relevance.HIDE, rel("A", "FIR"))
    }

    @Test
    fun everythingElseIsShown() {
        assertEquals(Relevance.SHOW, rel("E"))
        assertEquals(Relevance.SHOW, rel("F"))
        assertEquals(Relevance.SHOW, rel("G"))
        assertEquals(Relevance.SHOW, rel("UNC"))
        assertEquals(Relevance.SHOW, rel("UNC", "GSEC"))
        assertEquals(Relevance.SHOW, rel("UNC", "ASRA"))
        assertEquals(Relevance.SHOW, rel("UNC", "WARNING"))
        assertEquals(Relevance.SHOW, rel("W"))
        assertEquals(Relevance.SHOW, rel("UNC", "LTA"))
        assertEquals(Relevance.SHOW, rel("UNC", "OFR"))
        assertEquals(Relevance.SHOW, rel("", ""))
    }

    @Test
    fun floorAboveCeilingOfInterestHides() {
        assertEquals(Relevance.ALERT, rel("D", lower = msl(5999.0)))
        assertEquals(Relevance.HIDE, rel("D", lower = msl(6001.0)))
        assertEquals(Relevance.HIDE, rel("A", lower = fl(250))) // 7620 m
        assertEquals(Relevance.ALERT, rel("A", lower = fl(150))) // 4572 m
        assertEquals(Relevance.HIDE, rel("D", lower = AltLimit.UNLIMITED))
        // Datum-naive: an AGL floor is compared as if it were AMSL.
        assertEquals(Relevance.HIDE, rel("D", lower = TestAirspaces.agl(7000.0)))
        val custom = AirspacePolicy(ceilingOfInterestM = 3000.0)
        assertEquals(Relevance.HIDE, custom.relevance(asp("D", lower = msl(3500.0))))
    }

    @Test
    fun ignoredNamesHideEvenAlertingAirspaces() {
        val p = AirspacePolicy(ignoredNames = setOf("MY LOCAL CTR"))
        assertEquals(Relevance.HIDE, p.relevance(asp("D", "CTR", "MY LOCAL CTR")))
        assertEquals(Relevance.ALERT, p.relevance(asp("D", "CTR", "OTHER")))
    }

    @Test
    fun commonFrequencyZonesAreHiddenAndMandatoryBroadcastZonesOnlyShown() {
        assertEquals(Relevance.HIDE, rel("B", "", "NZC571 WAIRARAPA CFZ"))
        assertEquals(Relevance.HIDE, rel("B", "", "NZC175, HIKURANGI CFZ"))
        assertEquals(Relevance.SHOW, rel("B", "", "WAIKANAE MBZ"))
        assertEquals(Relevance.HIDE, rel("C", "", "foo cfz"))
        assertEquals(Relevance.HIDE, rel("B", "", "CFZ"))
        assertEquals(Relevance.SHOW, rel("B", "", "FOO (MBZ) BAR"))
        // Whole word only.
        assertEquals(Relevance.ALERT, rel("B", "", "XCFZ TOWN"))
        assertEquals(Relevance.ALERT, rel("B", "", "CFZY"))
        assertEquals(Relevance.ALERT, rel("B", "", "ROTORUA"))
        // An explicit alerting type is never demoted by the name.
        assertEquals(Relevance.ALERT, rel("B", "CTR", "ODD CFZ NAME"))
    }

    @Test
    fun newZealandDesignatorsOverrideTheExportedClass() {
        // As they appear in openAIP's NZ export.
        assertEquals(Relevance.ALERT, rel("D", "CTR", "NZA756, QUEENSTOWN, CTR C"))
        assertEquals(Relevance.ALERT, rel("C", "", "NZA733, QUEENSTOWN, CTA C"))
        assertEquals(Relevance.SHOW, rel("A", "", "NZB971, WANAKA MBZ"))
        assertEquals(Relevance.HIDE, rel("B", "", "NZC571 WAIRARAPA CFZ"))
        assertEquals(Relevance.ALERT, rel("UNC", "Q", "NZD921, SOMEWHERE, MILITARY"))
        assertEquals(Relevance.ALERT, rel("UNC", "R", "NZR901, FAUNA"))
        assertEquals(Relevance.ALERT, rel("UNC", "P", "NZM300, WAIOURU"))
        assertEquals(Relevance.SHOW, rel("F", "", "NZG 294 WEST HAMILTON, WAIKATO"))
        assertEquals(Relevance.SHOW, rel("UNC", "GSEC", "NZG451, TAUPO"))
        // Not designators: fall through to the normal rules.
        assertEquals(Relevance.ALERT, rel("C", "", "NZ SOMETHING"))
        assertEquals(Relevance.SHOW, rel("G", "", "NZXYZ"))
        // Opting in to MBZ alerts.
        val strict = AirspacePolicy(alertMbz = true)
        assertEquals(Relevance.ALERT, strict.relevance(asp("A", "", "NZB971, WANAKA MBZ", gnd)))
        // An NZ airspace the pilot ignored stays hidden.
        val ignoring = AirspacePolicy(ignoredNames = setOf("NZA756, QUEENSTOWN, CTR C"))
        assertEquals(Relevance.HIDE, ignoring.relevance(asp("D", "CTR", "NZA756, QUEENSTOWN, CTR C", gnd)))
    }

    @Test
    fun customKindSets() {
        val p = AirspacePolicy(alertKinds = setOf("CTR"), hideKinds = setOf("D"))
        assertEquals(Relevance.ALERT, p.relevance(asp("G", "CTR")))
        assertEquals(Relevance.SHOW, p.relevance(asp("C")))
        assertEquals(Relevance.HIDE, p.relevance(asp("D", "CTR")))
    }
}
