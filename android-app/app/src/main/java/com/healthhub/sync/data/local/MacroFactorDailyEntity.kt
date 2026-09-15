package com.healthhub.sync.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class MacroFactorDailyEntity(
    @PrimaryKey val date: String,
    val expenditureKcal: Double?,
    val trendWeightKg: Double?,
    val weightKg: Double?,
    val caloriesKcal: Double?,
    val proteinG: Double?,
    val fatG: Double?,
    val carbsG: Double?,
)
