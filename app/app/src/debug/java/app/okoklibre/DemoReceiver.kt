package app.okoklibre

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.okoklibre.algo.Sex
import app.okoklibre.ble.protocol.ScaleProtocolType
import app.okoklibre.data.GoalEntity
import app.okoklibre.data.ReminderEntity
import app.okoklibre.data.RoleEntity
import app.okoklibre.data.RoleType
import app.okoklibre.data.ScaleDeviceEntity
import app.okoklibre.data.StatusRepository
import app.okoklibre.data.TmpWeightEntity
import app.okoklibre.data.WeightStatusEntity
import app.okoklibre.ui.AppState
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Synthetic household for verifying the app without a scale. Debug builds only.
 *
 *   adb shell am broadcast -n app.okoklibre.debug/app.okoklibre.DemoReceiver --es action seed
 *   adb shell am broadcast -n app.okoklibre.debug/app.okoklibre.DemoReceiver --es action scale \
 *       --ef weight 66.4 --ef impedance 520
 */
class DemoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.getStringExtra("action")) {
                    "seed" -> seed(AppState.get(appContext))
                    "scale" -> BuildHooks.simulationFile(appContext).writeText(
                        "${intent.getFloatExtra("weight", 70f)} ${intent.getFloatExtra("impedance", 0f)}",
                    )
                    "scale-off" -> BuildHooks.simulationFile(appContext).delete()
                }
            } finally {
                pending.finish()
            }
        }
    }

    private class Plan(
        val days: Int,
        val startFatKg: Double,
        val endFatKg: Double,
        val startMuscleKg: Double,
        val endMuscleKg: Double,
        val skipChance: Double,
    )

    private suspend fun seed(app: AppState) {
        val db = app.database
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        db.roleDao().clear()
        db.dayAggregateDao().clear()
        db.tmpWeightDao().clear()
        db.scaleDeviceDao().clear()
        db.reminderDao().clear()

        fun birthday(years: Long) = today.minusYears(years).minusDays(40).toEpochDay()
        val now = System.currentTimeMillis() / 1000
        val asha = app.roleRepository.createRole("Asha", Sex.FEMALE, birthday(34), 165, RoleType.MAIN, createdAtEpochSec = now)
        val ravi = app.roleRepository.createRole("Ravi", Sex.MALE, birthday(41), 178, RoleType.MEMBER, createdAtEpochSec = now)
        val mira = app.roleRepository.createRole("Mira", Sex.FEMALE, birthday(29), 158, RoleType.MEMBER, createdAtEpochSec = now)

        // Recomposition: fat comes down while muscle climbs.
        fill(app, asha, Plan(210, 21.5, 17.6, 40.2, 41.4, 0.12), today, zone, Random(11))
        // A steady cut with a stall in the middle, on a lighter logging habit.
        fill(app, ravi, Plan(160, 25.0, 20.2, 57.5, 56.6, 0.30), today, zone, Random(23))
        // Only just started.
        fill(app, mira, Plan(9, 15.0, 14.9, 35.0, 35.0, 0.45), today, zone, Random(5))

        val latestAsha = app.weightRepository.latest(asha)?.weightKg ?: 64.0
        db.goalDao().insert(
            GoalEntity(
                roleId = asha,
                initWeightKg = 66.0,
                targetWeightKg = latestAsha - 2.4,
                startEpochDay = today.minusDays(120).toEpochDay(),
            ),
        )
        db.reminderDao().upsert(ReminderEntity(roleId = asha, timeMinutesOfDay = 430, mon = true, tue = true, wed = true, thu = true, fri = true))
        db.reminderDao().upsert(ReminderEntity(roleId = ravi, timeMinutesOfDay = 465, sat = true, sun = true))
        app.reminderScheduler.scheduleAll(db.reminderDao().enabled())
        db.scaleDeviceDao().upsert(
            ScaleDeviceEntity(
                mac = "A4:C1:38:5D:10:7E",
                name = "OKOK scale",
                typeId = 2,
                protocolVersion = 0x11,
                protocolType = ScaleProtocolType.OKOK_CLASSIC,
                lastSeenEpochSec = now,
            ),
        )
        db.tmpWeightDao().insert(
            TmpWeightEntity(mac = "A4:C1:38:5D:10:7E", weightKg = 71.2, timestampEpochSec = now - 3 * 86_400),
        )
        with(app.settingsRepository) {
            setOnboardingDone(true)
            setActiveRoleId(asha)
        }
        app.settingsRepository.activeRoleId.first()
    }

    private suspend fun fill(app: AppState, roleId: Long, plan: Plan, today: LocalDate, zone: ZoneId, random: Random) {
        val role = app.roleRepository.role(roleId) ?: return
        val age = today.year - LocalDate.ofEpochDay(role.birthdayEpochDay).year
        for (day in plan.days downTo 0) {
            if (day != 0 && random.nextDouble() < plan.skipChance) continue
            val date = today.minusDays(day.toLong())
            val progress = 1.0 - day.toDouble() / plan.days.coerceAtLeast(1)
            val wave = sin(progress * 9.0) * 0.25
            val fat = plan.startFatKg + (plan.endFatKg - plan.startFatKg) * progress + wave + gaussian(random) * 0.12
            val muscle = plan.startMuscleKg + (plan.endMuscleKg - plan.startMuscleKg) * progress + gaussian(random) * 0.12
            val bone = if (role.sex == Sex.MALE) 3.2 else 2.5
            var water = gaussian(random) * 0.28
            if (date.dayOfWeek == DayOfWeek.MONDAY) water += 0.5
            if (date.dayOfWeek == DayOfWeek.FRIDAY) water -= 0.2
            val evening = random.nextDouble() < 0.14
            if (evening) water += 0.7
            val weight = fat + muscle + bone + water
            val weightOnly = day != 0 && random.nextDouble() < 0.07
            val hour = if (evening) 19 else 7
            val timestamp = date.atTime(hour, random.nextInt(4, 48)).atZone(zone).toEpochSecond()
            val id = app.weightRepository.insertComputed(
                role = role,
                weightKg = roundTo(weight, 0.05),
                timestampEpochSec = timestamp,
                impedanceOhm = if (weightOnly) 0.0 else impedanceFor(role.sex, role.heightCm.toDouble(), weight, age, fat),
            )
            if (random.nextDouble() < 0.1) {
                app.statusRepository.save(
                    WeightStatusEntity(
                        weightRecordId = id,
                        mood = random.nextInt(2, 6),
                        exerciseIntensity = StatusRepository.EXERCISE_SOFT,
                    ),
                )
            }
        }
    }

    /** Inverts the body-fat formula so a planned fat mass comes back out. */
    private fun impedanceFor(sex: Sex, heightCm: Double, weightKg: Double, age: Int, fatKg: Double): Double = when (sex) {
        Sex.MALE -> (fatKg - (-0.3315 * heightCm + 0.6216 * weightKg + 0.0183 * age + 22.554)) / 0.0085
        Sex.FEMALE -> (fatKg - (-0.3332 * heightCm + 0.7509 * weightKg + 0.0196 * age + 22.7193)) / 0.0072
    }.coerceIn(200.0, 900.0)

    private fun gaussian(random: Random): Double =
        (1..6).sumOf { random.nextDouble() } - 3.0

    private fun roundTo(value: Double, step: Double) = Math.round(value / step) * step
}
