package app.cloudbase.core.flight

import kotlin.math.abs
import kotlin.math.pow

object Netto {
    private const val G = 9.80665
    private const val TAN_60 = 1.7320508075688772

    /**
     * Vertical speed of the air mass: measured climb plus the glider's own sink, which grows with bank.
     *
     * Coordinated turn: tan φ = ω·V/g (ω in rad/s). A wing at load factor n = 1/cos φ flies at a higher
     * lift coefficient and sinks faster; we use sink = trimSink · n^1.5. Since n² = 1 + tan²φ,
     * n^1.5 = (1 + tan²φ)^0.75, so no trig is needed. Bank is clamped to 60°. [tas] is true airspeed
     * in m/s. A NaN turn rate is treated as straight flight.
     */
    fun airMass(vario: Double, turnRateDegS: Double, tas: Double, trimSinkMs: Double = 1.1): Double {
        val tanBank = if (turnRateDegS.isNaN() || tas.isNaN()) {
            0.0
        } else {
            minOf(abs(Math.toRadians(turnRateDegS) * tas) / G, TAN_60)
        }
        val sink = trimSinkMs * (1.0 + tanBank * tanBank).pow(0.75)
        return vario + sink
    }
}
