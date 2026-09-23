package ph.capstone.archeryclub

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import ph.capstone.archeryclub.ui.SetupScreen

@Composable
fun AppEntry(@Suppress("UNUSED_PARAMETER") activity: ComponentActivity) {
    // Production has no sample-account chooser or local preview repository.
    SetupScreen("Service not connected", "This build is awaiting the club’s account and membership service. Please use the development preview for testing.")
}
