package com.healthhub.sync.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase

// TODO(Task 2): Remove this placeholder once a real entity (e.g.
// MacroFactorDailyEntity) is registered on AppDatabase. Room's @Database
// annotation does not accept an empty entities list, so this stub exists
// only to keep the database skeleton compiling until Task 2 lands.
@Entity(tableName = "placeholder")
internal data class PlaceholderEntity(
    @PrimaryKey val id: Int = 0,
)

@Database(entities = [PlaceholderEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "health_hub.db",
                ).build().also { instance = it }
            }
    }
}
