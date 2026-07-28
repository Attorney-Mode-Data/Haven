package sh.haven.app.backup

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.haven.app.MainActivity
import sh.haven.core.data.preferences.UserPreferencesRepository
import javax.inject.Inject

@AndroidEntryPoint
class PullBackupShortcutActivity : ComponentActivity() {

    @Inject
    lateinit var preferencesRepository: UserPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            val passphrase = preferencesRepository.backupSyncPassphrase()
            if (!passphrase.isNullOrEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "Pulling backup in background...", Toast.LENGTH_SHORT).show()
                }
                val request = OneTimeWorkRequestBuilder<BackupAutoPullWorker>()
                    .setInputData(workDataOf("is_manual" to true))
                    .build()
                WorkManager.getInstance(applicationContext).enqueue(request)
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "No password saved. Opening Haven...", Toast.LENGTH_SHORT).show()
                }
                val intent = Intent(applicationContext, MainActivity::class.java).apply {
                    action = ACTION_SHOW_BACKUP_PASSWORD_DIALOG
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
            }
            finish()
        }
    }

    companion object {
        const val ACTION_SHOW_BACKUP_PASSWORD_DIALOG = "sh.haven.app.ACTION_SHOW_BACKUP_PASSWORD_DIALOG"
    }
}
