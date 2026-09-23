package ph.capstone.archeryclub

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import ph.capstone.archeryclub.data.LocalPreviewRepository
import ph.capstone.archeryclub.data.PreferencesSnapshotStore
import ph.capstone.archeryclub.ui.*

@Composable
fun AppEntry(activity: ComponentActivity) {
    val result = remember(activity) {
        runCatching {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ClubViewModel(LocalPreviewRepository(PreferencesSnapshotStore(activity.applicationContext))) as T
            }
            ViewModelProvider(activity, factory)[ClubViewModel::class.java]
        }
    }
    result.getOrNull()?.let { ClubApp(it) } ?: SetupScreen(
        "Preview data could not be read",
        "Your saved data has not been overwritten. Restart the app, or clear this preview app’s storage in Android Settings to start again.",
    )
}
