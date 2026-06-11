package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "backups")
data class BackupEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val packageName: String,
    val appName: String,
    val originalFileName: String,
    val originalFilePath: String, // Can be SAF URI or internal path
    val backupFilePath: String,   // Path where the backed-up file is saved in app filesDir
    val timestamp: Long = System.currentTimeMillis(),
    val fileSize: Long,
    val note: String = ""
)
