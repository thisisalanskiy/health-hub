package com.healthhub.sync.data

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata

private fun Metadata.toDto() = IngestMetadataDto(id = id, dataOrigin = DataOriginDto(dataOrigin.packageName))

private fun WeightRecord.toDto() = WeightRecordDto(
    metadata = metadata.toDto(),
    time = time.toEpochMilli(),
    weight = WeightValueDto(weight.inKilograms),
)

private fun NutritionRecord.toDto() = NutritionRecordDto(
    metadata = metadata.toDto(),
    startTime = startTime.toEpochMilli(),
    endTime = endTime.toEpochMilli(),
    name = name,
    energy = energy?.let { EnergyDto(it.inKilocalories) },
    protein = protein?.let { MassGramsDto(it.inGrams) },
    totalCarbohydrate = totalCarbohydrate?.let { MassGramsDto(it.inGrams) },
    totalFat = totalFat?.let { MassGramsDto(it.inGrams) },
)

private fun ExerciseSessionRecord.toDto() = ExerciseRecordDto(
    metadata = metadata.toDto(),
    startTime = startTime.toEpochMilli(),
    endTime = endTime.toEpochMilli(),
    exerciseType = exerciseType,
)

private fun HeartRateRecord.toDto() = HeartRateRecordDto(
    metadata = metadata.toDto(),
    samples = samples.map { HeartRateSampleDto(it.time.toEpochMilli(), it.beatsPerMinute) },
)

private fun RestingHeartRateRecord.toDto() = RestingHeartRateRecordDto(
    metadata = metadata.toDto(),
    time = time.toEpochMilli(),
    beatsPerMinute = beatsPerMinute,
)

private fun StepsRecord.toDto() = StepsRecordDto(
    metadata = metadata.toDto(),
    startTime = startTime.toEpochMilli(),
    endTime = endTime.toEpochMilli(),
    count = count,
)

private fun SleepSessionRecord.toDto() = SleepRecordDto(
    metadata = metadata.toDto(),
    startTime = startTime.toEpochMilli(),
    endTime = endTime.toEpochMilli(),
    stages = stages.map { SleepStageDto(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.stage) },
)

fun buildIngestPayload(
    weights: List<WeightRecord> = emptyList(),
    nutrition: List<NutritionRecord> = emptyList(),
    exercises: List<ExerciseSessionRecord> = emptyList(),
    heartRates: List<HeartRateRecord> = emptyList(),
    restingHeartRates: List<RestingHeartRateRecord> = emptyList(),
    steps: List<StepsRecord> = emptyList(),
    sleeps: List<SleepSessionRecord> = emptyList(),
): IngestPayload? {
    if (weights.isEmpty() && nutrition.isEmpty() && exercises.isEmpty() &&
        heartRates.isEmpty() && restingHeartRates.isEmpty() && steps.isEmpty() && sleeps.isEmpty()
    ) {
        return null
    }
    return IngestPayload(
        weight = weights.takeIf { it.isNotEmpty() }?.let { WeightBucket(it.map(WeightRecord::toDto)) },
        nutrition = nutrition.takeIf { it.isNotEmpty() }?.let { NutritionBucket(it.map(NutritionRecord::toDto)) },
        exercise = exercises.takeIf { it.isNotEmpty() }?.let { ExerciseBucket(it.map(ExerciseSessionRecord::toDto)) },
        heartRate = heartRates.takeIf { it.isNotEmpty() }?.let { HeartRateBucket(it.map(HeartRateRecord::toDto)) },
        restingHeartRate = restingHeartRates.takeIf { it.isNotEmpty() }
            ?.let { RestingHeartRateBucket(it.map(RestingHeartRateRecord::toDto)) },
        steps = steps.takeIf { it.isNotEmpty() }?.let { StepsBucket(it.map(StepsRecord::toDto)) },
        sleep = sleeps.takeIf { it.isNotEmpty() }?.let { SleepBucket(it.map(SleepSessionRecord::toDto)) },
    )
}
