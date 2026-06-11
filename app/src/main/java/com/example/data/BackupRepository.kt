package com.example.data

import kotlinx.coroutines.flow.Flow

class BackupRepository(private val backupDao: BackupDao) {
    val allBackups: Flow<List<BackupEntity>> = backupDao.getAllBackups()

    fun getBackupsByPackage(packageName: String): Flow<List<BackupEntity>> {
        return backupDao.getBackupsByPackage(packageName)
    }

    suspend fun insert(backup: BackupEntity): Long {
        return backupDao.insertBackup(backup)
    }

    suspend fun delete(backup: BackupEntity) {
        backupDao.deleteBackup(backup)
    }

    suspend fun deleteById(id: Int) {
        backupDao.deleteBackupById(id)
    }

    suspend fun clearAll() {
        backupDao.deleteAllBackups()
    }
}
