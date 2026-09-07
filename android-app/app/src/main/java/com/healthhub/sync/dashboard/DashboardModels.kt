package com.healthhub.sync.dashboard

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord

data class DashboardRawData(
    val weights: List<WeightRecord> = emptyList(),
    val nutrition: List<NutritionRecord> = emptyList(),
    val exercises: List<ExerciseSessionRecord> = emptyList(),
    val heartRates: List<HeartRateRecord> = emptyList(),
    val restingHeartRates: List<RestingHeartRateRecord> = emptyList(),
    val steps: List<StepsRecord> = emptyList(),
    val sleeps: List<SleepSessionRecord> = emptyList(),
)

data class ChartSeries(val labels: List<String>, val values: List<Float>)
