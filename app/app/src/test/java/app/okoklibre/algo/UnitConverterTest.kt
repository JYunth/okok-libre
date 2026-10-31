package app.okoklibre.algo

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit conversion. */
class UnitConverterTest {

    @Test
    fun weightUnitCodes() {
        assertEquals(WeightUnit.KG, WeightUnit.fromAppCode(1400))
        assertEquals(WeightUnit.LB, WeightUnit.fromAppCode(1401))
        assertEquals(WeightUnit.JIN, WeightUnit.fromAppCode(1402))
        assertEquals(WeightUnit.ST_LB, WeightUnit.fromAppCode(1403))
        assertEquals(WeightUnit.KG, WeightUnit.fromWireCode(0))
        assertEquals(WeightUnit.LB, WeightUnit.fromWireCode(1))
        assertEquals(WeightUnit.JIN, WeightUnit.fromWireCode(2))
        assertEquals(WeightUnit.ST_LB, WeightUnit.fromWireCode(3))
    }

    @Test
    fun jin() {
        assertEquals(140.0, UnitConverter.kgToJin(70.0), 0.0)
        assertEquals(70.0, UnitConverter.jinToKg(140.0), 0.0)
        assertEquals(55.0, UnitConverter.jinToKg(UnitConverter.kgToJin(55.0)), 0.0)
    }

    @Test
    fun lbExact() {
        assertEquals(154.32357332265707, UnitConverter.kgToLbExact(70.0), 1e-9)
        assertEquals(70.0, UnitConverter.lbToKg(UnitConverter.kgToLbExact(70.0)), 1e-9)
        assertEquals(0.4535924, UnitConverter.lbToKg(1.0), 1e-12)
    }

    @Test
    fun lbScaleQuirk_roundsUpToNext02LbStep() {
        // ((int)((kg*10)*11023/5000) + 1)/2*2/10.0
        assertEquals(154.4, UnitConverter.kgToLb(70.0), 0.0) // exact 154.32 -> 154.4
        assertEquals(110.2, UnitConverter.kgToLb(50.0), 0.0) // exact 110.23 -> 110.2
        assertEquals(166.4, UnitConverter.kgToLb(75.5), 0.0) // exact 166.449 -> 166.4
        assertEquals(220.4, UnitConverter.kgToLb(100.0), 0.0) // exact 220.46 -> 220.4
        assertEquals(132.8, UnitConverter.kgToLb(60.25), 0.0) // exact 132.83 -> 132.8
        assertEquals(0.0, UnitConverter.kgToLb(0.0), 0.0)
    }

    @Test
    fun stLb_formatAndParse() {
        assertEquals("11:11.4", UnitConverter.kgToStLb(75.0))
        assertEquals("11:0.4", UnitConverter.kgToStLb(70.0))
        assertEquals("15:10.4", UnitConverter.kgToStLb(100.0))
        assertEquals("11:0.3", UnitConverter.kgToStLbExact(70.0)) // 154.3236 % 14 = 0.3236 -> 0.3
        assertEquals(165.4, UnitConverter.stLbToLb("11:11.4"), 1e-9)
        assertEquals(75.024, UnitConverter.stLbToKg("11:11.4"), 0.0) // round3 HALF_UP
        assertEquals(63.503, UnitConverter.stLbToKg("10:0.0"), 0.0)
        assertEquals(0.0, UnitConverter.stLbToLb("garbage"), 0.0)
        assertEquals(0.0, UnitConverter.stLbToLb("11"), 0.0)
        assertEquals(0.0, UnitConverter.stLbToKg("garbage"), 0.0)
        // round trip
        val kg = UnitConverter.stLbToKg(UnitConverter.kgToStLb(80.0))
        assertEquals(80.0, kg, 0.03)
    }

    @Test
    fun length_usesOriginal0394Factor() {
        assertEquals(66.98, UnitConverter.cmToInches(170.0), 1e-9) // 0.394, not 0.3937
        assertEquals(5.581666666666667, UnitConverter.cmToFeet(170.0), 1e-9)
        assertEquals(170.0, UnitConverter.inchesToCm(66.98), 1e-9)
        assertEquals(170.0507614213198, UnitConverter.feetInchesToCm(5, 7), 1e-9)
    }

    @Test
    fun feetInchesFormatting() {
        assertEquals(5 to 7, UnitConverter.cmToFeetInches(170.0))
        assertEquals("5'7\"", UnitConverter.formatFeetInches(170.0))
        assertEquals("5'11\"", UnitConverter.formatFeetInches(180.0))
        assertEquals("4'11\"", UnitConverter.formatFeetInches(150.0))
        assertEquals(5 to 5, UnitConverter.cmToFeetInches(165.1))
    }

    @Test
    fun weightFormatting_byDigitPrecision() {
        assertEquals("70", UnitConverter.formatWeight(70.26, 0)) // int truncation
        assertEquals("70", UnitConverter.formatWeight(70.9, 0))
        assertEquals("70.0", UnitConverter.formatWeight(70.0, 1))
        assertEquals("70.3", UnitConverter.formatWeight(70.26, 1))
        assertEquals("70.2", UnitConverter.formatWeight(70.24, 1))
        assertEquals("70.00", UnitConverter.formatWeight(70.0, 2))
        assertEquals("70.26", UnitConverter.formatWeight(70.26, 2))
        assertEquals("70.26", UnitConverter.formatWeight(70.256, 2))
    }

    @Test
    fun weightFormatting_byUnit() {
        assertEquals("70.0", UnitConverter.formatWeight(70.0, WeightUnit.KG, 1))
        assertEquals("140.0", UnitConverter.formatWeight(70.0, WeightUnit.JIN, 1))
        assertEquals("154.4", UnitConverter.formatWeight(70.0, WeightUnit.LB, 1))
        assertEquals("11:0.4", UnitConverter.formatWeight(70.0, WeightUnit.ST_LB, 1))
    }
}
