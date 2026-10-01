package com.autonoumus.ai.core.util

import java.util.Calendar

/**
 * Evaluates the common subset of the OpenStreetMap `opening_hours` syntax:
 *
 *  - `24/7`
 *  - `Mo-Fr 09:00-17:00; Sa 09:00-13:00; Su off`
 *  - several ranges per day (`09:00-13:00,15:00-19:00`), day lists (`Mo,We,Fr`)
 *  - ranges that cross midnight (`22:00-02:00`)
 *
 * Public-holiday rules (`PH`, `SH`) are ignored. Anything else (months, weeks, sunrise/sunset, ...)
 * is reported as unknown (`null`) rather than guessed.
 */
object OpeningHoursParser {

    private val DAY_NAMES = listOf("mo", "tu", "we", "th", "fr", "sa", "su")
    private val TIME_RANGE = Regex("""^(\d{1,2}):(\d{2})-(\d{1,2}):(\d{2})$""")
    private const val MINUTES_PER_DAY = 24 * 60

    /** A time range in minutes since midnight. `end < start` means it runs past midnight. */
    private data class Range(val start: Int, val end: Int)

    /** @return true / false when the schedule could be evaluated, null when it is missing or unsupported. */
    fun isOpenNow(spec: String?, now: Calendar = Calendar.getInstance()): Boolean? {
        val schedule = parse(spec) ?: return null

        val today = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Monday = 0
        val yesterday = (today + 6) % 7
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val openToday = schedule[today].any { r ->
            if (r.start < r.end) minute in r.start until r.end else r.start > r.end && minute >= r.start
        }
        val openFromYesterday = schedule[yesterday].any { r -> r.start > r.end && minute < r.end }
        return openToday || openFromYesterday
    }

    private fun parse(spec: String?): List<List<Range>>? {
        if (spec.isNullOrBlank()) return null
        val normalized = spec.trim()
            .replace('–', '-')
            .replace(Regex("""\s*-\s*"""), "-")
            .replace(Regex(""",\s+"""), ",")
        if (normalized.equals("24/7", ignoreCase = true)) {
            return List(7) { listOf(Range(0, MINUTES_PER_DAY)) }
        }

        val schedule = MutableList(7) { emptyList<Range>() }
        var anyRule = false

        for (rawRule in normalized.split(';')) {
            val rule = rawRule.trim()
            if (rule.isEmpty()) continue

            val tokens = rule.split(Regex("""\s+"""))
            val daysToken: String?
            val timesToken: String
            when (tokens.size) {
                1 -> { daysToken = null; timesToken = tokens[0] }
                2 -> { daysToken = tokens[0]; timesToken = tokens[1] }
                else -> return null
            }

            val days = if (daysToken == null) (0..6).toList() else parseDays(daysToken) ?: return null
            if (days.isEmpty()) continue // holiday-only rule

            val ranges = parseTimes(timesToken) ?: return null
            days.forEach { schedule[it] = ranges }
            anyRule = true
        }
        return if (anyRule) schedule else null
    }

    /** @return the day indexes (Monday = 0), an empty list for holiday-only rules, null when invalid. */
    private fun parseDays(token: String): List<Int>? {
        val days = mutableSetOf<Int>()
        for (item in token.lowercase().split(',')) {
            if (item == "ph" || item == "sh") continue
            val bounds = item.split('-')
            val from = DAY_NAMES.indexOf(bounds[0])
            if (from < 0 || bounds.size > 2) return null
            if (bounds.size == 1) {
                days.add(from)
            } else {
                val to = DAY_NAMES.indexOf(bounds[1])
                if (to < 0) return null
                var d = from
                while (true) {
                    days.add(d)
                    if (d == to) break
                    d = (d + 1) % 7
                }
            }
        }
        return days.toList()
    }

    private fun parseTimes(token: String): List<Range>? {
        when (token.lowercase()) {
            "off", "closed" -> return emptyList()
            "24/7", "open" -> return listOf(Range(0, MINUTES_PER_DAY))
        }
        val ranges = mutableListOf<Range>()
        for (part in token.split(',')) {
            val m = TIME_RANGE.matchEntire(part) ?: return null
            val (sh, sm, eh, em) = m.destructured
            val start = sh.toInt() * 60 + sm.toInt()
            val end = eh.toInt() * 60 + em.toInt()
            if (sh.toInt() > 24 || eh.toInt() > 24 || sm.toInt() > 59 || em.toInt() > 59) return null
            if (start > MINUTES_PER_DAY || end > MINUTES_PER_DAY) return null
            ranges.add(Range(start, end))
        }
        return ranges
    }
}
