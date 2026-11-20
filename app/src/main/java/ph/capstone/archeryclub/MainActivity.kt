package ph.capstone.archeryclub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ph.capstone.archeryclub.ui.ArcheryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Club times are Philippine time whatever zone the phone is set to (see CLUB_ZONE).
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(ph.capstone.archeryclub.connected.CLUB_ZONE_ID))
        enableEdgeToEdge()
        setContent { ArcheryTheme { AppEntry(this) } }
    }
}
