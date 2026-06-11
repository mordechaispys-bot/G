package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupDao {
    @Query("SELECT * FROM backups ORDER BY timestamp DESC")
    fun getAllBackups(): Flow<List<BackupEntity>>

    @Query("SELECT * FROM backups WHERE packageName = :packageName ORDER BY timestamp DESC")
    fun getBackupsByPackage(packageName: String): Flow<List<BackupEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBackup(backup: BackupEntity): Long

    @Delete
    suspend fun deleteBackup(backup: BackupEntity)

    @Query("DELETE FROM backups WHERE id = :id")
    suspend fun deleteBackupById(id: Int)

    @Query("DELETE FROM backups")
    suspend fun deleteAllBackups()
}
