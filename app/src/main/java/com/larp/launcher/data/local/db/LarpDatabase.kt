package com.larp.launcher.data.local.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "local_mods")
data class LocalModEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val dllPath: String,
    val coverPath: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val fileSize: Long,
    val checksum: String,
    val enabled: Boolean = false
)

@Entity(tableName = "installed_mods")
data class InstalledModEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: String,
    val checksum: String,
    val installedAt: Long,
    val enabled: Boolean = true
)

@Dao
interface LocalModDao {
    @Query("SELECT * FROM local_mods ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<LocalModEntity>>

    @Query("SELECT * FROM local_mods WHERE id = :id")
    suspend fun getById(id: String): LocalModEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mod: LocalModEntity)

    @Update
    suspend fun update(mod: LocalModEntity)

    @Query("DELETE FROM local_mods WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE local_mods SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
}

@Dao
interface InstalledModDao {
    @Query("SELECT * FROM installed_mods ORDER BY installedAt DESC")
    fun observeAll(): Flow<List<InstalledModEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mod: InstalledModEntity)

    @Query("DELETE FROM installed_mods WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT id FROM installed_mods")
    suspend fun allIds(): List<String>
}

@Database(
    entities = [LocalModEntity::class, InstalledModEntity::class],
    version = 1,
    exportSchema = false
)
abstract class LarpDatabase : RoomDatabase() {
    abstract fun localModDao(): LocalModDao
    abstract fun installedModDao(): InstalledModDao
}
