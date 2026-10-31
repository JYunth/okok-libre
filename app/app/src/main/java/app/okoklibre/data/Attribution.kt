package app.okoklibre.data

import app.okoklibre.algo.matchRole

/** Who a locked reading belongs to. */
sealed interface Attribution {
    /** One clear owner. [switched] means it is not who the app was showing. */
    data class Save(val role: RoleEntity, val switched: Boolean) : Attribution

    /** Several people fit. The first is the best guess and wins after the countdown. */
    data class Confirm(val candidates: List<RoleEntity>) : Attribution

    /** Nobody fits; the person has to say. */
    data object Ask : Attribution
}

/**
 * Shared-phone attribution, built on the ±3 kg match.
 *
 * The active profile wins ties because the person holding the phone usually picked
 * it. A profile with no history is only assumed when it is active: a brand-new
 * household member can't be ruled in or out by weight alone.
 */
fun attributeReading(
    weightKg: Double,
    roles: List<RoleEntity>,
    lastWeightByRole: Map<Long, Double>,
    activeRoleId: Long?,
): Attribution {
    if (roles.isEmpty()) return Attribution.Ask
    if (roles.size == 1) return Attribution.Save(roles.single(), switched = roles.single().id != activeRoleId)

    val byId = roles.associateBy { it.id }
    val matches = matchRole(
        lastWeightByRole.filterKeys { it in byId }.mapKeys { it.key.toInt() },
        weightKg,
    ).map { byId.getValue(it.toLong()) }
    val active = byId[activeRoleId]
    val activeIsNew = active != null && active.id !in lastWeightByRole
    val others = matches.filter { it.id != activeRoleId }
    val activeFits = active != null && (activeIsNew || matches.any { it.id == active.id })

    return when {
        activeFits && others.isEmpty() -> Attribution.Save(active!!, switched = false)
        activeFits -> Attribution.Confirm(listOf(active!!) + others)
        others.size == 1 -> Attribution.Save(others.single(), switched = true)
        others.isNotEmpty() -> Attribution.Confirm(others)
        else -> Attribution.Ask
    }
}
