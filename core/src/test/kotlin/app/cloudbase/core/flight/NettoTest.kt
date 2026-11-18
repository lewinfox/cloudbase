package app.cloudbase.core.flight

import org.junit.Assert.assertEquals
import org.junit.Test

class NettoTest {
    @Test fun straightFlightAddsTrimSink() {
        // Vario -1.1 at trim in still air is netto 0; 2.0 m/s climb is 3.1 m/s air.
        assertEquals(0.0, Netto.airMass(-1.1, 0.0, 10.5), 1e-12)
        assertEquals(3.1, Netto.airMass(2.0, 0.0, 10.5), 1e-12)
        assertEquals(0.5, Netto.airMass(0.0, 0.0, 10.5, trimSinkMs = 0.5), 1e-12)
    }

    @Test fun twentySecondCircle() {
        // Hand calculation: omega = 360/20 = 18 deg/s = 0.314159 rad/s; V = 10.5 m/s.
        //   tan(bank) = 0.314159 * 10.5 / 9.80665 = 0.33636  (bank 18.6 deg)
        //   load factor n = sqrt(1 + 0.33636^2) = 1.0550;  n^1.5 = 1.0837
        //   sink = 1.1 * 1.0837 = 1.1921 m/s
        assertEquals(1.1921, Netto.airMass(0.0, 18.0, 10.5), 0.0005)
        assertEquals(1.1921 + 1.4, Netto.airMass(1.4, 18.0, 10.5), 0.0005)
        // Direction of the turn does not matter.
        assertEquals(Netto.airMass(1.4, 18.0, 10.5), Netto.airMass(1.4, -18.0, 10.5), 1e-12)
    }

    @Test fun bankIsClampedAt60Degrees() {
        // n = 2, n^1.5 = 2.8284, sink = 3.1113
        assertEquals(1.1 * Math.pow(2.0, 1.5), Netto.airMass(0.0, 200.0, 12.0), 1e-9)
        assertEquals(Netto.airMass(0.0, 200.0, 12.0), Netto.airMass(0.0, 900.0, 12.0), 1e-12)
    }

    @Test fun nanTurnRateIsStraightFlight() {
        assertEquals(Netto.airMass(1.0, 0.0, 10.5), Netto.airMass(1.0, Double.NaN, 10.5), 0.0)
    }

    /** Against the simulator: netto from true vario + true turn rate recovers the true air mass climb. */
    @Test fun recoversSimAirMassClimb() {
        val sim = FlightSim(sensorNoise = false)
        var lastHeading = sim.headingDeg
        var worst = 0.0
        var sumAbs = 0.0
        var n = 0
        while (!sim.finished) {
            sim.step(0.02)
            val turn = (((sim.headingDeg - lastHeading + 540.0) % 360.0) - 180.0) / 0.02
            lastHeading = sim.headingDeg
            // The final glide is deliberately accelerated (sink is not the trim sink): not covered by this model.
            if (!sim.flying || sim.trueTas > 13.0) continue
            val err = Math.abs(Netto.airMass(sim.trueVario, turn, sim.trueTas) - sim.trueAirMassClimb)
            worst = maxOf(worst, err)
            sumAbs += err
            n++
        }
        // The sim's vertical speed follows the air with a 0.5 s lag, which is most of what remains.
        println("netto vs sim air mass: mean |error| %.3f, worst %.2f m/s over %d samples".format(sumAbs / n, worst, n))
        assertEquals(0.0, sumAbs / n, 0.1)
        assertEquals(0.0, worst, 1.5)
    }
}
