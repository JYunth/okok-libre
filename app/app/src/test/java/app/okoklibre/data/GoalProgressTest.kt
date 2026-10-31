package app.okoklibre.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalProgressTest {

    private fun goal(init: Double, target: Double) = GoalEntity(
        id = 1,
        roleId = 1,
        initWeightKg = init,
        targetWeightKg = target,
        startEpochDay = 20000,
    )

    @Test
    fun `no weighings yet means zero progress`() {
        val p = computeGoalProgress(goal(90.0, 80.0), null)
        assertEquals(0.0, p.progressPct, 1e-9)
        assertFalse(p.achieved)
        org.junit.Assert.assertNull(p.currentWeightKg)
    }

    @Test
    fun `half way to a loss target is 50 percent`() {
        val p = computeGoalProgress(goal(90.0, 80.0), 85.0)
        assertEquals(50.0, p.progressPct, 1e-9)
        assertEquals(-5.0, p.remainingKg, 1e-9)
        assertFalse(p.achieved)
    }

    @Test
    fun `reaching the loss target completes the goal`() {
        val p = computeGoalProgress(goal(90.0, 80.0), 79.9)
        assertEquals(100.0, p.progressPct, 1e-9)
        assertTrue(p.achieved)
    }

    @Test
    fun `gain goals progress in the opposite direction`() {
        val p = computeGoalProgress(goal(60.0, 70.0), 65.0)
        assertEquals(50.0, p.progressPct, 1e-9)
        assertFalse(p.achieved)
        assertTrue(computeGoalProgress(goal(60.0, 70.0), 70.5).achieved)
    }

    @Test
    fun `moving away from the target clamps at zero`() {
        val p = computeGoalProgress(goal(90.0, 80.0), 95.0)
        assertEquals(0.0, p.progressPct, 1e-9)
        assertFalse(p.achieved)
    }

    @Test
    fun `zero-distance goal is not achieved until target reached`() {
        val g = goal(80.0, 80.0)
        assertEquals(0.0, computeGoalProgress(g, 80.5).progressPct, 1e-9)
        assertTrue(computeGoalProgress(g, 80.0).achieved)
    }
}
