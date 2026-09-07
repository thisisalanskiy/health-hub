package com.healthhub.sync.dashboard

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord

data class DashboardRawData(
    val weights: List<WeightRecord> = emptyList(),
    val heartRates: List<HeartRateRecord> = emptyList(),
    val steps: List<StepsRecord> = emptyList(),
    val sleeps: List<SleepSessionRecord> = emptyList(),
)

data class ChartSeries(val labels: List<String>, val values: List<Float>)
