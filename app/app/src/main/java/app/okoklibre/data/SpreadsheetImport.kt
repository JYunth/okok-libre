package app.okoklibre.data

import java.time.ZoneId
import kotlin.math.abs

private const val HEIGHT_TOLERANCE_CM = 3.0
private const val AGE_TOLERANCE_YEARS = 1

/** The same human on this phone: ids can't be trusted across installs, a name and birthday can. */
fun sameLocalPerson(row: RoleEntity, existing: List<RoleEntity>): RoleEntity? = existing.firstOrNull {
    it.nickname.equals(row.nickname, ignoreCase = true) && it.birthdayEpochDay == row.birthdayEpochDay
}

/**
 * Works out which local profile each spreadsheet owner is. A role id means nothing on
 * another install, so each reading's own sex, height and age have to fit the person.
 * An owner nobody fits, or several people fit equally, maps to nothing and is skipped:
 * losing a row beats filing someone's weight under the wrong person.
 */
fun matchSpreadsheetOwners(
    rows: List<WeightRecordEntity>,
    roles: List<RoleEntity>,
    zone: ZoneId,
): Map<Long, RoleEntity> = rows.groupBy { it.roleId }.mapNotNull { (fileRoleId, readings) ->
    val fitting = roles.filter { role -> readings.all { fits(it, role, zone) } }
    val owner = fitting.firstOrNull { it.id == fileRoleId } ?: fitting.singleOrNull()
    owner?.let { fileRoleId to it }
}.toMap()

private fun fits(reading: WeightRecordEntity, role: RoleEntity, zone: ZoneId): Boolean =
    reading.sex == role.sex &&
        abs(reading.heightCm - role.heightCm) <= HEIGHT_TOLERANCE_CM &&
        abs(ageYearsAt(role.birthdayEpochDay, reading.timestampEpochSec, zone) - reading.ageYears) <= AGE_TOLERANCE_YEARS
