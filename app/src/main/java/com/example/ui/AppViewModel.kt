package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.utils.AppManager
import com.example.utils.AppPackageReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val database = AppDatabase.getDatabase(context)
    private val backupRepository = BackupRepository(database.backupDao())

    private val sharedPrefs: SharedPreferences = context.getSharedPreferences("appmod_prefs", Context.MODE_PRIVATE)

    // UI state for installed apps
    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    // Selected app details
    private val _selectedApp = MutableStateFlow<InstalledApp?>(null)
    val selectedApp: StateFlow<InstalledApp?> = _selectedApp.asStateFlow()

    // Selected App persistent SAF Tree Uri String
    private val _persistedSafUri = MutableStateFlow<String?>(null)
    val persistedSafUri: StateFlow<String?> = _persistedSafUri.asStateFlow()

    // Explorer files (APK or SAF)
    private val _apkAssets = MutableStateFlow<List<AppFile.ApkAsset>>(emptyList())
    val apkAssets: StateFlow<List<AppFile.ApkAsset>> = _apkAssets.asStateFlow()

    private val _safFiles = MutableStateFlow<List<AppFile.SafDocument>>(emptyList())
    val safFiles: StateFlow<List<AppFile.SafDocument>> = _safFiles.asStateFlow()

    private val _fileSearchQuery = MutableStateFlow("")
    val fileSearchQuery: StateFlow<String> = _fileSearchQuery.asStateFlow()

    private val _isLoadingFiles = MutableStateFlow(false)
    val isLoadingFiles: StateFlow<Boolean> = _isLoadingFiles.asStateFlow()

    // Editor details
    private val _editingFile = MutableStateFlow<AppFile?>(null)
    val editingFile: StateFlow<AppFile?> = _editingFile.asStateFlow()

    private val _editingFileContent = MutableStateFlow("")
    val editingFileContent: StateFlow<String> = _editingFileContent.asStateFlow()

    private val _isSavingFile = MutableStateFlow(false)
    val isSavingFile: StateFlow<Boolean> = _isSavingFile.asStateFlow()

    // Global Operation messages
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    // Backups History flow (Reactive from Room DB)
    val backupHistory: StateFlow<List<BackupEntity>> = backupRepository.allBackups
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        loadInstalledApps()
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    /**
     * Loads the list of installed applications
     */
    fun loadInstalledApps() {
        _isLoadingApps.value = true
        viewModelScope.launch(Dispatchers.Default) {
            val apps = AppManager.getInstalledApps(context, _searchQuery.value)
            _installedApps.value = apps
            _isLoadingApps.value = false
        }
    }

    /**
     * Updates app search query and filters the apps list
     */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        loadInstalledApps()
    }

    /**
     * Selects an app and loads its corresponding files (both APK assets & persistent SAF directory if available)
     */
    fun selectApp(app: InstalledApp?) {
        _selectedApp.value = app
        _apkAssets.value = emptyList()
        _safFiles.value = emptyList()
        _fileSearchQuery.value = ""
        _editingFile.value = null

        if (app != null) {
            // Load persistent SAF Tree URI if previously saved for this package
            val savedUri = sharedPrefs.getString("saf_${app.packageName}", null)
            _persistedSafUri.value = savedUri

            loadAppFiles(app, savedUri)
        } else {
            _persistedSafUri.value = null
        }
    }

    /**
     * Loads/refreshes the files for the selected app
     */
    private fun loadAppFiles(app: InstalledApp, safUriString: String?) {
        _isLoadingFiles.value = true
        viewModelScope.launch(Dispatchers.Default) {
            // 1. Read files inside APK Assets
            val assets = AppPackageReader.readApkAssets(context, app.packageName)
            _apkAssets.value = assets

            // 2. Read SAF files if permission is granted
            if (safUriString != null) {
                val safDocs = AppPackageReader.listFilesFromSaf(context, safUriString)
                _safFiles.value = safDocs
            }
            _isLoadingFiles.value = false
        }
    }

    /**
     * Set persistent SAF Tree Uri for selected package and reload files
     */
    fun persistSafPermission(uri: Uri) {
        val app = _selectedApp.value ?: return
        try {
            // Persist permission across device reboots
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val uriString = uri.toString()
        sharedPrefs.edit().putString("saf_${app.packageName}", uriString).apply()
        _persistedSafUri.value = uriString

        _statusMessage.value = "פולדר הנתונים החיצוני חובר בהצלחה!"
        loadAppFiles(app, uriString)
    }

    /**
     * Disconnects/removes SAF storage mapping for selected application
     */
    fun disconnectSaf() {
        val app = _selectedApp.value ?: return
        sharedPrefs.edit().remove("saf_${app.packageName}").apply()
        _persistedSafUri.value = null
        _safFiles.value = emptyList()
        _statusMessage.value = "חיבור תיקיית הנתונים הוסר"
    }

    /**
     * Simple file filter query (filters resources/assets list)
     */
    fun setFileSearchQuery(query: String) {
        _fileSearchQuery.value = query
    }

    /**
     * Request to edit a file (loads its content asynchronously)
     */
    fun startEditingFile(file: AppFile) {
        _editingFile.value = file
        _editingFileContent.value = "טוען..."
        viewModelScope.launch(Dispatchers.IO) {
            val content = when (file) {
                is AppFile.ApkAsset -> AppPackageReader.readAssetContent(context, file.appPackageName, file.relativePath)
                is AppFile.SafDocument -> AppPackageReader.readSafFileContent(context, file.uriString)
            }
            _editingFileContent.value = content
        }
    }

    fun updateEditingContent(newContent: String) {
        _editingFileContent.value = newContent
    }

    fun cancelEditing() {
        _editingFile.value = null
        _editingFileContent.value = ""
    }

    /**
     * Saves the edited file contents back to its source, auto-backing up to Room/Private storage if SAF
     */
    fun saveEditedFile() {
        val file = _editingFile.value as? AppFile.SafDocument ?: return
        val app = _selectedApp.value ?: return
        val content = _editingFileContent.value

        _isSavingFile.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val success = AppPackageReader.backupAndWriteSafFileContent(
                context = context,
                fileUriString = file.uriString,
                content = content,
                appName = app.name,
                packageName = app.packageName,
                backupRepository = backupRepository
            )

            withContext(Dispatchers.Main) {
                _isSavingFile.value = false
                if (success) {
                    _statusMessage.value = "הקובץ נשמר בהצלחה! גיבוי אוטומטי נוצר."
                    _editingFile.value = null
                    // Reload file systems
                    loadAppFiles(app, _persistedSafUri.value)
                } else {
                    _statusMessage.value = "שגיאה בשמירת הקובץ. ודא הרשאות כתיבה."
                }
            }
        }
    }

    /**
     * Overwrites a SAF document file with another file selected from device storage (file swapping)
     */
    fun replaceSafFileWithSource(targetFile: AppFile.SafDocument, sourceUri: Uri) {
        val app = _selectedApp.value ?: return
        _isLoadingFiles.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val success = AppPackageReader.backupAndReplaceSafFile(
                context = context,
                fileUriString = targetFile.uriString,
                sourceUri = sourceUri,
                appName = app.name,
                packageName = app.packageName,
                backupRepository = backupRepository
            )

            withContext(Dispatchers.Main) {
                _isLoadingFiles.value = false
                if (success) {
                    _statusMessage.value = "הקובץ הוחלף בהצלחה! גיבוי של המקור נשמר."
                    loadAppFiles(app, _persistedSafUri.value)
                } else {
                    _statusMessage.value = "שגיאה בביצוע החלפת הקובץ."
                }
            }
        }
    }

    /**
     * Restores a previously created backup
     */
    fun restoreBackupFile(backup: BackupEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = AppPackageReader.restoreBackup(context, backup)
            withContext(Dispatchers.Main) {
                if (success) {
                    _statusMessage.value = "קובץ הנתונים שוחזר בהצלחה למצב המקורי!"
                    // Reload if current app is same as backup app
                    val selected = _selectedApp.value
                    if (selected != null && selected.packageName == backup.packageName) {
                        loadAppFiles(selected, _persistedSafUri.value)
                    }
                } else {
                    _statusMessage.value = "שגיאה בשחזור הגיבוי. בדוק האם תיקיית היעד עדיין מחוברת."
                }
            }
        }
    }

    /**
     * Deletes a backup record and its associated physical file
     */
    fun deleteBackupFile(backup: BackupEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = java.io.File(backup.backupFilePath)
                if (file.exists()) {
                    file.delete()
                }
                backupRepository.delete(backup)
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "הגיבוי נמחק לצמיתות."
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Clears all backup records and logs
     */
    fun clearAllBackups() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Delete actual backup directory recursively
                val backupsDir = java.io.File(context.filesDir, "backups")
                if (backupsDir.exists()) {
                    backupsDir.deleteRecursively()
                }
                backupRepository.clearAll()
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "כל קבצי הגיבויים נמחקו בהצלחה."
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
