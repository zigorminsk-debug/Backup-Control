package com.zigorminsk.backupcontrol

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.zigorminsk.backupcontrol.ui.MainViewModel
import com.zigorminsk.backupcontrol.ui.screens.BackupControlRootScreen
import com.zigorminsk.backupcontrol.ui.theme.BackupControlTheme
import com.zigorminsk.backupcontrol.update.AppUpdateManager
import com.zigorminsk.backupcontrol.worker.MorningBannerNotifier

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        handleIncomingIntent(intent)

        setContent {
            BackupControlTheme {
                BackupControlRootScreen(
                    viewModel = viewModel,
                    onRequestNotificationPermission = ::requestNotificationPermissionIfNeeded
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppUpdateManager.refreshState(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(MorningBannerNotifier.EXTRA_FROM_MORNING_BANNER, false) == true) {
            viewModel.onOpenedFromMorningBanner()
        } else if (intent?.getBooleanExtra(MorningBannerNotifier.EXTRA_OPEN_ORG_EDITOR, false) == true) {
            viewModel.openOrganizationsTabForReview()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
