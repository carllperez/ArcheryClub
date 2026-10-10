package ph.capstone.archeryclub

import androidx.activity.ComponentActivity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ph.capstone.archeryclub.connected.ConnectedEntry
import ph.capstone.archeryclub.ui.ScreenShell

@Composable
fun AppEntry(activity: ComponentActivity) {
    // Only the isolated local test build needs LAN access. Hosted builds use HTTPS.
    if (BuildConfig.LOCAL_BACKEND && Build.VERSION.SDK_INT >= 37) {
        val permission = "android.permission.ACCESS_LOCAL_NETWORK"
        fun allowed() = activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        var granted by remember { mutableStateOf(allowed()) }
        val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
        DisposableEffect(activity) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) granted = allowed()
            }
            activity.lifecycle.addObserver(observer)
            onDispose { activity.lifecycle.removeObserver(observer) }
        }
        if (!granted) {
            ScreenShell("Connect to your test backend") {
                Text("This local test app connects to the club database running on your computer. Allow nearby device access so Android can make that connection.")
                Button(onClick = { request.launch(permission) }) { Text("Allow local connection") }
                TextButton(onClick = {
                    activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
                }) { Text("Open app permissions") }
            }
            return
        }
    }
    ConnectedEntry(activity)
}
