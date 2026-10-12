package ph.capstone.archeryclub.connected

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

@Composable
fun TrainingVideoCapture(record:String,vm:SystemViewModel,busy:Boolean) {
    val context=LocalContext.current
    var captureUri by rememberSaveable(record){mutableStateOf<String?>(null)}
    var ready by rememberSaveable(record){mutableStateOf(false)}
    val capture=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        ready=result.resultCode==Activity.RESULT_OK && captureUri!=null
        if(!ready)captureUri?.let{uri->runCatching{context.contentResolver.delete(Uri.parse(uri),null,null)};captureUri=null}
    }
    fun startCapture() {
        runCatching {
            val directory=File(context.cacheDir,"training-videos").apply{mkdirs()}
            val file=File.createTempFile("training-",".mp4",directory)
            val uri=FileProvider.getUriForFile(context,context.packageName+".trainingfiles",file)
            captureUri=uri.toString();ready=false
            capture.launch(Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT,uri)
                putExtra(MediaStore.EXTRA_DURATION_LIMIT,10)
                putExtra(MediaStore.EXTRA_SIZE_LIMIT,20L*1024*1024)
                putExtra(MediaStore.EXTRA_VIDEO_QUALITY,0)
                clipData=ClipData.newRawUri("Training video",uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { vm.error("A camera app is unavailable. You can upload an existing MP4 clip instead.") }
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        if(granted)startCapture() else vm.error("Camera access is required to record. You can still upload an existing clip.")
    }
    OutlinedButton(onClick={
        if(context.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCapture()
        else permission.launch(Manifest.permission.CAMERA)
    },enabled=!busy&&!ready){Text("Record training video (up to 10 seconds)")}
    if(ready){
        Text("The clip is ready. The server will check its duration before attaching it to this scorecard.")
        OutlinedButton(onClick={captureUri?.let{uri->
            vm.upload(context,Uri.parse(uri),11,record,"Training video") {
                runCatching{context.contentResolver.delete(Uri.parse(uri),null,null)}
                ready=false;captureUri=null
            }
        }},enabled=!busy){Text("Upload recorded video")}
        OutlinedButton(onClick={captureUri?.let{runCatching{context.contentResolver.delete(Uri.parse(it),null,null)}};captureUri=null;ready=false},enabled=!busy){Text("Discard recording")}
    }
}
