package com.healthhub.sync.digest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SnapshotResponse(
    @SerialName("weight_kg") val weightKg: Double? = null,
    @SerialName("calories_kcal") val caloriesKcal: Double? = null,
    @SerialName("tdee_kcal") val tdeeKcal: Double? = null,
    val steps: Long? = null,
    @SerialName("sleep_min") val sleepMin: Double? = null,
)

@Serializable
data class WeightPoint(val t: String, val v: Double)

@Serializable
data class WeightResponse(
    val readings: List<WeightPoint> = emptyList(),
    val trend: List<WeightPoint> = emptyList(),
)

@Serializable
data class TdeeResponse(
    val dates: List<String> = emptyList(),
    val calories: List<Double?> = emptyList(),
    @SerialName("calculated_tdee") val calculatedTdee: List<Double?> = emptyList(),
    @SerialName("macrofactor_tdee") val macrofactorTdee: List<Double?> = emptyList(),
)

@Serializable
data class NutritionResponse(
    val days: List<String> = emptyList(),
    val calories: List<Double?> = emptyList(),
    val protein: List<Double?> = emptyList(),
    val fat: List<Double?> = emptyList(),
    val carbs: List<Double?> = emptyList(),
)

@Serializable
data class RecoveryPoint(val t: String, val v: Double? = null)

@Serializable
data class RecoveryResponse(
    val rhr: List<RecoveryPoint> = emptyList(),
    val hrv: List<RecoveryPoint> = emptyList(),
)

@Serializable
data class SleepResponse(
    val dates: List<String> = emptyList(),
    val deep: List<Double> = emptyList(),
    val rem: List<Double> = emptyList(),
    val core: List<Double> = emptyList(),
    val awake: List<Double> = emptyList(),
    val other: List<Double> = emptyList(),
)

@Serializable
data class WeeklyWorkout(val week: String, @SerialName("total_min") val totalMin: Double, val sessions: Int)

@Serializable
data class WorkoutsResponse(val weekly: List<WeeklyWorkout> = emptyList())

@Serializable
data class BodyTrendsResponse(
    val dates: List<String> = emptyList(),
    @SerialName("vo2_max") val vo2Max: List<Double?> = emptyList(),
    @SerialName("body_fat_percent") val bodyFatPercent: List<Double?> = emptyList(),
    @SerialName("bmr_kcal") val bmrKcal: List<Double?> = emptyList(),
)
