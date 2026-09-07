package com.healthhub.sync.dashboard

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
}
