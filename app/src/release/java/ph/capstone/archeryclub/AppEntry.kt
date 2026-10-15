package ph.capstone.archeryclub

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import ph.capstone.archeryclub.backend.BackendApp
import ph.capstone.archeryclub.backend.SupabaseConfig
import ph.capstone.archeryclub.ui.SetupScreen

@Composable
fun AppEntry(activity: ComponentActivity) {
    if (SupabaseConfig.isConfigured) {
        BackendApp(activity)
    } else {
        SetupScreen("Service not connected", "Add SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY to local.properties, sync Gradle, and run again.")
    }
}
