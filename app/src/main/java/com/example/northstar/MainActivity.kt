package com.example.northstar

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.northstar.data.SyncRepository
import com.example.northstar.ui.navigation.AppNavigation
import com.example.northstar.ui.theme.Bg1
import com.example.northstar.ui.theme.NorthstarTheme
import com.example.northstar.viewmodel.RouteViewModel
import com.google.firebase.auth.FirebaseAuth

class MainActivity : ComponentActivity() {
    private val routeViewModel: RouteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Firestore sync follows sign-in: start mirroring/listening when signed in,
        // stop on sign-out. Only wired when Firebase is configured (bring-your-own-project);
        // otherwise the app runs fully local with no sync.
        val sync = SyncRepository.get(applicationContext)
        if (com.example.northstar.data.FirebaseGate.isConfigured(applicationContext)) {
            FirebaseAuth.getInstance().addAuthStateListener { fa ->
                if (fa.currentUser != null) sync.startSync() else sync.stopSync()
            }
        }

        // Maintenance reminders on app open (fires even if the Garage screen is never opened).
        Thread {
            com.example.northstar.data.MaintenanceNotifier.check(
                applicationContext, sync.maintenanceItems(), sync.odometer()
            )
        }.start()

        handleIntent(intent)
        setContent {
            NorthstarTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg1) {
                    AppNavigation(routeViewModel = routeViewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                    routeViewModel.handleSharedText(text)
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data?.toString() ?: return
                routeViewModel.handleSharedText(uri)
            }
        }
    }
}
