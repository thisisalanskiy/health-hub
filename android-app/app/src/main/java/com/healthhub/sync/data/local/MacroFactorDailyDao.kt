package com.healthhub.sync.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MacroFactorDailyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<MacroFactorDailyEntity>)

    @Query("SELECT * FROM MacroFactorDailyEntity WHERE date >= :startDate AND date <= :endDate ORDER BY date")
    suspend fun getInRange(startDate: String, endDate: String): List<MacroFactorDailyEntity>
}
