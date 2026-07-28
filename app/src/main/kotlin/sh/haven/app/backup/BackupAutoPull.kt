package sh.haven.app.backup

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.haven.core.data.backup.BackupSyncManager
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionLogRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Automatic backup pull from the configured remote (#359 follow-up).
 *
 * Mirror structure of BackupAutoSyncWorker but performs a backup pull/restore.
 */
@HiltWorker
class BackupAutoPullWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val preferencesRepository: UserPreferencesRepository,
    private val backupSyncManager: BackupSyncManager,
    private val connectionLogRepository: ConnectionLogRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val isManual = inputData.getBoolean("is_manual", false)
        if (!isManual && !preferencesRepository.backupAutoPullEnabled.first()) return Result.success()
        val profileId = preferencesRepository.backupSyncProfileId.first() ?: return Result.success()
        val passphrase = preferencesRepository.backupSyncPassphrase() ?: return Result.success()
        val path = preferencesRepository.backupSyncPath.first()
        return try {
            val started = System.currentTimeMillis()
            val result = backupSyncManager.pull(profileId, path, passphrase)
            val msg = "Restored ${result.count} items" +
                if (result.errors.isNotEmpty()) " (${result.errors.size} errors)" else ""

            if (isManual) {
                showNotification(applicationContext, "Backup Sync Success", msg)
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "Backup restored successfully!", Toast.LENGTH_SHORT).show()
                }
            }

            connectionLogRepository.logEvent(
                profileId, ConnectionLog.Status.SYNC_OK,
                durationMs = System.currentTimeMillis() - started,
                details = (if (isManual) "Manual pull" else "Auto-pull") + " backup ← $path: $msg",
            )
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "auto-pull failed (attempt $runAttemptCount)", e)
            val errorMsg = e.message ?: e.javaClass.simpleName
            if (isManual) {
                showNotification(applicationContext, "Backup Sync Failed", errorMsg)
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "Backup pull failed: $errorMsg", Toast.LENGTH_LONG).show()
                }
            }
            if (runAttemptCount >= MAX_ATTEMPTS || isManual) {
                connectionLogRepository.logEvent(
                    profileId, ConnectionLog.Status.SYNC_FAILED,
                    details = (if (isManual) "Manual pull" else "Auto-pull") + " backup failed: $errorMsg",
                )
                Result.failure()
            } else Result.retry()
        }
    }

    companion object {
        private const val TAG = "BackupAutoPull"
        private const val UNIQUE_PERIODIC = "backup-auto-pull-periodic"
        private const val MAX_ATTEMPTS = 5
        private const val BACKUP_CHANNEL_ID = "backup_sync_channel"

        private val networked = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        private fun showNotification(context: Context, title: String, message: String) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (nm.getNotificationChannel(BACKUP_CHANNEL_ID) == null) {
                    val channel = android.app.NotificationChannel(
                        BACKUP_CHANNEL_ID,
                        "Backup Sync",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT
                    )
                    nm.createNotificationChannel(channel)
                }
            }
            val builder = NotificationCompat.Builder(context, BACKUP_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)

            try {
                nm.notify(424242, builder.build())
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not post notification: ${e.message}")
            }
        }

        /** Periodic pull. */
        fun schedulePeriodic(context: Context, intervalMinutes: Int) {
            val request = PeriodicWorkRequestBuilder<BackupAutoPullWorker>(intervalMinutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(networked)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(UNIQUE_PERIODIC)
            }
        }
    }
}

@Singleton
class BackupAutoPullScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: UserPreferencesRepository,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(
                preferencesRepository.backupAutoPullEnabled.distinctUntilChanged(),
                preferencesRepository.backupAutoPullIntervalMinutes.distinctUntilChanged()
            ) { enabled, interval ->
                enabled to interval
            }.collect { (enabled, interval) ->
                if (!enabled) {
                    BackupAutoPullWorker.cancelAll(context)
                    return@collect
                }
                BackupAutoPullWorker.schedulePeriodic(context, interval)
            }
        }
    }
}
