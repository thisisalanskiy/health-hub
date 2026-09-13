package com.healthhub.sync.dashboard

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields

private val DAY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE

private fun Instant.toDayString(zone: ZoneId): String = atZone(zone).toLocalDate().format(DAY_FORMAT)

object DashboardAggregator {

    fun dailyWeightTrend(data: DashboardRawData, zone: ZoneId): ChartSeries {
        val byDay = data.weights
            .groupBy { it.time.toDayString(zone) }
            .toSortedMap()
            .mapValues { (_, records) -> records.map { it.weight.inKilograms }.average().toFloat() }
        return ChartSeries(byDay.keys.toList(), byDay.values.toList())
    }

    fun dailySteps(data: DashboardRawData, zone: ZoneId): ChartSeries {
        val byDay = data.steps
            .groupBy { it.startTime.toDayString(zone) }
            .toSortedMap()
            .mapValues { (_, records) -> records.sumOf { it.count }.toFloat() }
        return ChartSeries(byDay.keys.toList(), byDay.values.toList())
    }

    fun dailyHeartRate(data: DashboardRawData, zone: ZoneId): Triple<ChartSeries, ChartSeries, ChartSeries> {
        val samplesByDay = data.heartRates
            .flatMap { record -> record.samples.map { it.time.toDayString(zone) to it.beatsPerMinute } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
        val labels = samplesByDay.keys.toList()
        val min = ChartSeries(labels, samplesByDay.values.map { it.min().toFloat() })
        val avg = ChartSeries(labels, samplesByDay.values.map { it.average().toFloat() })
        val max = ChartSeries(labels, samplesByDay.values.map { it.max().toFloat() })
        return Triple(min, avg, max)
    }

    fun nightlySleepStages(data: DashboardRawData, zone: ZoneId): Map<String, ChartSeries> {
        data class StageMinutes(val day: String, val stage: String, val minutes: Float)

        val entries = data.sleeps.flatMap { session ->
            session.stages.map { stage ->
                val minutes = (stage.endTime.toEpochMilli() - stage.startTime.toEpochMilli()) / 60_000f
                StageMinutes(stage.startTime.toDayString(zone), normalizeStage(stage.stage), minutes)
            }
        }

        val allDays = entries.map { it.day }.distinct().sorted()
        val stageNames = listOf("deep", "rem", "core", "awake", "other")

        return stageNames.associateWith { stageName ->
            val byDay = entries.filter { it.stage == stageName }.groupBy { it.day }
            ChartSeries(
                labels = allDays,
                values = allDays.map { day -> byDay[day]?.sumOf { it.minutes.toDouble() }?.toFloat() ?: 0f },
            )
        }
    }

    /** Mirrors the HC sleep-stage int -> normalized-name mapping already used server-side in api/main.py. */
    private fun normalizeStage(stageInt: Int): String = when (stageInt) {
        5 -> "deep"
        6 -> "rem"
        4 -> "core"
        1, 3 -> "awake"
        else -> "other"
    }

    fun weeklyWorkoutMinutes(data: DashboardRawData, zone: ZoneId): ChartSeries {
        val byWeek = data.exercises
            .groupBy { weekLabel(it.startTime, zone) }
            .toSortedMap()
            .mapValues { (_, sessions) ->
                sessions.sumOf { (it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 60_000.0 }.toFloat()
            }
        return ChartSeries(byWeek.keys.toList(), byWeek.values.toList())
    }

    fun dailyNutritionMacros(data: DashboardRawData, zone: ZoneId): Triple<ChartSeries, ChartSeries, ChartSeries> {
        val byDay = data.nutrition.groupBy { it.startTime.toDayString(zone) }.toSortedMap()
        val labels = byDay.keys.toList()
        fun sumGrams(selector: (androidx.health.connect.client.records.NutritionRecord) -> Double?): List<Float> =
            labels.map { day -> byDay.getValue(day).sumOf { selector(it) ?: 0.0 }.toFloat() }

        val protein = ChartSeries(labels, sumGrams { it.protein?.inGrams })
        val fat = ChartSeries(labels, sumGrams { it.totalFat?.inGrams })
        val carbs = ChartSeries(labels, sumGrams { it.totalCarbohydrate?.inGrams })
        return Triple(protein, fat, carbs)
    }

    fun dailyRestingHeartRate(data: DashboardRawData, zone: ZoneId): ChartSeries {
        val byDay = data.restingHeartRates
            .groupBy { it.time.toDayString(zone) }
            .toSortedMap()
            .mapValues { (_, records) -> records.map { it.beatsPerMinute }.average().toFloat() }
        return ChartSeries(byDay.keys.toList(), byDay.values.toList())
    }

    /** ISO-8601 week label (e.g. "2026-W37") so sessions in the same week group together. */
    private fun weekLabel(instant: Instant, zone: ZoneId): String {
        val date = instant.atZone(zone).toLocalDate()
        val week = date.get(WeekFields.ISO.weekOfWeekBasedYear())
        val year = date.get(WeekFields.ISO.weekBasedYear())
        return "%04d-W%02d".format(year, week)
    }
}
