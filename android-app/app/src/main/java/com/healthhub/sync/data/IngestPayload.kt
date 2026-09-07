package com.healthhub.sync.data

import kotlinx.serialization.Serializable

@Serializable
data class DataOriginDto(val packageName: String)

@Serializable
data class IngestMetadataDto(val id: String, val dataOrigin: DataOriginDto)

@Serializable
data class WeightValueDto(val kilograms: Double)

@Serializable
data class WeightRecordDto(
    val metadata: IngestMetadataDto,
    val time: Long,
    val weight: WeightValueDto,
)

@Serializable
data class WeightBucket(val records: List<WeightRecordDto>)

@Serializable
data class EnergyDto(val kilocalories: Double)

@Serializable
data class MassGramsDto(val grams: Double)

@Serializable
data class NutritionRecordDto(
    val metadata: IngestMetadataDto,
    val startTime: Long,
    val endTime: Long,
    val name: String? = null,
    val energy: EnergyDto? = null,
    val protein: MassGramsDto? = null,
    val totalCarbohydrate: MassGramsDto? = null,
    val totalFat: MassGramsDto? = null,
)

@Serializable
data class NutritionBucket(val records: List<NutritionRecordDto>)

@Serializable
data class ExerciseRecordDto(
    val metadata: IngestMetadataDto,
    val startTime: Long,
    val endTime: Long,
    val exerciseType: Int,
)

@Serializable
data class ExerciseBucket(val records: List<ExerciseRecordDto>)

@Serializable
data class HeartRateSampleDto(val time: Long, val beatsPerMinute: Long)

@Serializable
data class HeartRateRecordDto(
    val metadata: IngestMetadataDto,
    val samples: List<HeartRateSampleDto>,
)

@Serializable
data class HeartRateBucket(val records: List<HeartRateRecordDto>)

@Serializable
data class RestingHeartRateRecordDto(
    val metadata: IngestMetadataDto,
    val time: Long,
    val beatsPerMinute: Long,
)

@Serializable
data class RestingHeartRateBucket(val records: List<RestingHeartRateRecordDto>)

@Serializable
data class StepsRecordDto(
    val metadata: IngestMetadataDto,
    val startTime: Long,
    val endTime: Long,
    val count: Long,
)

@Serializable
data class StepsBucket(val records: List<StepsRecordDto>)

@Serializable
data class SleepStageDto(val startTime: Long, val endTime: Long, val stage: Int)

@Serializable
data class SleepRecordDto(
    val metadata: IngestMetadataDto,
    val startTime: Long,
    val endTime: Long,
    val stages: List<SleepStageDto> = emptyList(),
)

@Serializable
data class SleepBucket(val records: List<SleepRecordDto>)

@Serializable
data class Vo2MaxRecordDto(
    val metadata: IngestMetadataDto,
    val time: Long,
    val vo2MillilitersPerMinuteKilogram: Double,
)

@Serializable
data class Vo2MaxBucket(val records: List<Vo2MaxRecordDto>)

@Serializable
data class HeartRateVariabilityRecordDto(
    val metadata: IngestMetadataDto,
    val time: Long,
    val heartRateVariabilityMillis: Double,
)

@Serializable
data class HeartRateVariabilityBucket(val records: List<HeartRateVariabilityRecordDto>)

@Serializable
data class BodyFatRecordDto(
    val metadata: IngestMetadataDto,
    val time: Long,
    // A 0-1 fraction (e.g. 0.257 for 25.7%), NOT Health Connect's native 0-100
    // Percentage scale -- converted at the mapping boundary in SyncPayloadMapper.kt
    // to match the existing historical rows' convention. See this plan's Global
    // Constraints for why.
    val percentage: Double,
)

@Serializable
data class BodyFatBucket(val records: List<BodyFatRecordDto>)

@Serializable
data class BasalMetabolicRateRecordDto(
    val metadata: IngestMetadataDto,
    val time: Long,
    val kilocaloriesPerDay: Double,
)

@Serializable
data class BasalMetabolicRateBucket(val records: List<BasalMetabolicRateRecordDto>)

@Serializable
data class IngestPayload(
    val weight: WeightBucket? = null,
    val nutrition: NutritionBucket? = null,
    val exercise: ExerciseBucket? = null,
    val heartRate: HeartRateBucket? = null,
    val restingHeartRate: RestingHeartRateBucket? = null,
    val steps: StepsBucket? = null,
    val sleep: SleepBucket? = null,
    val vo2Max: Vo2MaxBucket? = null,
    val heartRateVariability: HeartRateVariabilityBucket? = null,
    val bodyFat: BodyFatBucket? = null,
    val bmr: BasalMetabolicRateBucket? = null,
)
