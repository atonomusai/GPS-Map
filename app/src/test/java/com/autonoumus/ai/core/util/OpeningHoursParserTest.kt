package com.autonoumus.ai.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class OpeningHoursParserTest {

    /** 2024-01-01 is a Monday. */
    private fun at(dayOfWeek: Int, hour: Int, minute: Int = 0): Calendar =
        Calendar.getInstance().apply {
            clear()
            set(2024, Calendar.JANUARY, 1, hour, minute)
            add(Calendar.DAY_OF_MONTH, dayOfWeek) // 0 = Monday
        }

    @Test
    fun always_open() {
        assertEquals(true, OpeningHoursParser.isOpenNow("24/7", at(3, 3)))
    }

    @Test
    fun missing_or_unsupported_is_unknown() {
        assertNull(OpeningHoursParser.isOpenNow(null, at(0, 10)))
        assertNull(OpeningHoursParser.isOpenNow("", at(0, 10)))
        assertNull(OpeningHoursParser.isOpenNow("sunrise-sunset", at(0, 10)))
        assertNull(OpeningHoursParser.isOpenNow("Jan-Mar Mo-Fr 09:00-17:00", at(0, 10)))
        assertNull(OpeningHoursParser.isOpenNow("by appointment", at(0, 10)))
    }

    @Test
    fun weekday_range() {
        val spec = "Mo-Fr 09:00-17:00"
        assertEquals(true, OpeningHoursParser.isOpenNow(spec, at(0, 9)))
        assertEquals(true, OpeningHoursParser.isOpenNow(spec, at(4, 16, 59)))
        assertEquals(false, OpeningHoursParser.isOpenNow(spec, at(4, 17)))
        assertEquals(false, OpeningHoursParser.isOpenNow(spec, at(1, 8, 59)))
        assertEquals(false, OpeningHoursParser.isOpenNow(spec, at(5, 12))) // Saturday
    }

    @Test
    fun later_rules_override_earlier_ones() {
        val spec = "Mo-Su 08:00-22:00; Fr off"
        assertEquals(true, OpeningHoursParser.isOpenNow(spec, at(3, 12)))
        assertEquals(false, OpeningHoursParser.isOpenNow(spec, at(4, 12)))
    }

    @Test
    fun split_day_with_several_ranges() {
        val spec = "Mo-Sa 09:00-13:00, 15:00-19:00"
        assertEquals(true, OpeningHoursParser.isOpenNow(spec, at(1, 10)))
        assertEquals(false, OpeningHoursParser.isOpenNow(spec, at(1, 14)))
        assertEquals(true, OpeningHoursParser.isOpenNow(spec, at(1, 16)))
        assertEquals(false, OpeningHoursParser.isOpenNow(spec, at(6, 10))) // Sunday
    }

    @Test
    fun range_past_midnight_counts_for_the_next_morning() {
        val spec = "Fr,Sa 20:00-02:00"
        assertEquals(true, OpeningHoursParser.isOpenNow(spec, at(4, 23)))
        assertEquals(true, OpeningHoursParser.isOpenNow(spec, at(5, 1)))   // Saturday 01:00, still Friday's shift
        assertEquals(false, OpeningHoursParser.isOpenNow(spec, at(4, 1)))  // Friday 01:00, Thursday has no shift
        assertEquals(false, OpeningHoursParser.isOpenNow(spec, at(5, 3)))
    }

    @Test
    fun public_holiday_rules_are_ignored() {
        val spec = "Mo-Fr 09:00-17:00; PH off"
        assertEquals(true, OpeningHoursParser.isOpenNow(spec, at(2, 12)))
    }

    @Test
    fun time_without_days_applies_every_day() {
        assertEquals(true, OpeningHoursParser.isOpenNow("06:00-23:00", at(6, 7)))
        assertEquals(false, OpeningHoursParser.isOpenNow("06:00-23:00", at(6, 5)))
    }

    @Test
    fun wrapping_day_range() {
        val spec = "Sa-Mo 10:00-14:00"
        assertEquals(true, OpeningHoursParser.isOpenNow(spec, at(6, 11)))  // Sunday
        assertEquals(true, OpeningHoursParser.isOpenNow(spec, at(0, 11)))  // Monday
        assertEquals(false, OpeningHoursParser.isOpenNow(spec, at(2, 11))) // Wednesday
    }
}
