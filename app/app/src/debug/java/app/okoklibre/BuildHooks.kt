package app.okoklibre

import android.content.Context
import app.okoklibre.ble.ScaleUserProfile
import app.okoklibre.ble.WeighEvent
import app.okoklibre.ble.WeighingSource
import app.okoklibre.ble.protocol.Measurement
import app.okoklibre.ble.protocol.ScaleAdvertisement
import app.okoklibre.ble.protocol.WeightUnit
import java.io.File
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Debug builds can stand in for the scale, so every weigh-in flow can be driven on an
 * emulator. Off until [DemoReceiver] writes the target weight. Release builds never see this.
 */
object BuildHooks {
    fun weighingSource(context: Context, real: WeighingSource): WeighingSource = object : WeighingSource {
        override fun startWeighing(
            device: ScaleAdvertisement,
            roles: List<ScaleUserProfile>,
            unit: WeightUnit,
        ): Flow<WeighEvent> {
            val script = simulationFile(context).takeIf { it.exists() }?.readText()?.split(" ")
            val target = script?.getOrNull(0)?.toDoubleOrNull() ?: return real.startWeighing(device, roles, unit)
            val impedance = script.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            return simulated(target, impedance)
        }

        override fun selectUser(roleId: Int) = real.selectUser(roleId)
    }

    fun simulationFile(context: Context) = File(context.applicationContext.filesDir, "simulated_scale.txt")

    private fun simulated(targetKg: Double, impedanceOhm: Double) = flow {
        emit(WeighEvent.Scanning)
        delay(900)
        emit(WeighEvent.Connecting)
        delay(500)
        val rampSteps = 16
        for (step in 1..rampSteps) {
            val eased = 1.0 - (1.0 - step.toDouble() / rampSteps).pow(3)
            emit(WeighEvent.Live(targetKg * eased, 2))
            delay(70)
        }
        repeat(8) { i ->
            val jitter = Random.nextDouble(-0.3, 0.3) * (8 - i) / 8
            emit(WeighEvent.Live(targetKg + jitter, 2))
            delay(170)
        }
        emit(WeighEvent.Live(targetKg, 2))
        delay(450)
        emit(
            WeighEvent.Locked(
                Measurement(weightKg = targetKg, lockFlag = true, impedanceOhm = impedanceOhm, scaleProperty = 0),
            ),
        )
        delay(400)
    }
}
