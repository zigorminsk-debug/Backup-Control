package by.csl.backupcontrol

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
import by.csl.backupcontrol.ui.MainViewModel
import by.csl.backupcontrol.ui.screens.BackupControlRootScreen
import by.csl.backupcontrol.ui.theme.BackupControlTheme
import by.csl.backupcontrol.worker.MorningBannerNotifier

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        handleIncomingBannerIntent(intent)

        setContent {
            BackupControlTheme {
                BackupControlRootScreen(
                    viewModel = viewModel,
                    onRequestNotificationPermission = ::requestNotificationPermissionIfNeeded
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingBannerIntent(intent)
    }

    private fun handleIncomingBannerIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(MorningBannerNotifier.EXTRA_FROM_MORNING_BANNER, false) == true) {
            viewModel.onOpenedFromMorningBanner()
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
