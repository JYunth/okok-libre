package app.okoklibre

import android.content.Context
import app.okoklibre.ble.WeighingSource

/** Release builds talk to the real scale. The debug source set swaps in a simulator on request. */
object BuildHooks {
    fun weighingSource(context: Context, real: WeighingSource): WeighingSource = real
}
