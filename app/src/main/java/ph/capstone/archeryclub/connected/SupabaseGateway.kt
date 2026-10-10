package ph.capstone.archeryclub.connected

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

class SupabaseGateway {
    companion object {
        val configured: Boolean get() = (BuildConfig.SUPABASE_URL.startsWith("https://") ||
            (BuildConfig.DEBUG && BuildConfig.LOCAL_BACKEND && BuildConfig.SUPABASE_URL=="http://10.0.2.2:55421")) &&
            BuildConfig.SUPABASE_PUBLISHABLE_KEY.startsWith("sb_publishable_")
    }
    val client = createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY) {
        install(Auth)
        install(Postgrest)
        install(Storage)
    }
    suspend fun signIn(email: String, password: String) = client.auth.signInWith(Email) { this.email=email.trim();this.password=password }
    suspend fun signUp(email: String, password: String) = client.auth.signUpWith(Email) { this.email=email.trim();this.password=password }
    suspend fun verify(email: String, code: String, recovery: Boolean) = client.auth.verifyEmailOtp(
        type=if(recovery) OtpType.Email.RECOVERY else OtpType.Email.SIGNUP,email=email.trim(),token=code.trim())
    suspend fun verifyInvitation(email:String,code:String)=client.auth.verifyEmailOtp(type=OtpType.Email.INVITE,email=email.trim(),token=code.trim())
    fun inviteAccount(data:JsonObject){
        val connection=URL(BuildConfig.SUPABASE_URL.trimEnd('/')+"/functions/v1/create-account").openConnection() as HttpURLConnection
        try{
            connection.requestMethod="POST";connection.doOutput=true;connection.connectTimeout=15000;connection.readTimeout=30000
            connection.setRequestProperty("apikey",BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            connection.setRequestProperty("Authorization","Bearer "+(client.auth.currentSessionOrNull()?.accessToken?:error("Sign in again.")))
            connection.setRequestProperty("Content-Type","application/json")
            connection.outputStream.use{it.write(data.toString().toByteArray())}
            if(connection.responseCode !in 200..299){
                val body=connection.errorStream?.bufferedReader()?.use{it.readText()}.orEmpty()
                error(runCatching{Json.parseToJsonElement(body).jsonObject.text("error")}.getOrDefault("Account invitation failed. Check server email configuration."))
            }
        }finally{connection.disconnect()}
    }
    suspend fun recover(email: String) = client.auth.resetPasswordForEmail(email.trim())
    suspend fun changePassword(password: String) = client.auth.updateUser { this.password=password }
    suspend fun signOut() = client.auth.signOut()
    suspend fun initialize() { client.postgrest.rpc("initialize_account") }
    suspend fun workspace(): JsonObject = Json.parseToJsonElement(client.postgrest.rpc("club_workspace").data).jsonObject
    suspend fun command(action:String,data:JsonObject):JsonElement = rpc("club_command",buildJsonObject { put("p_action",action);put("p_data",data) })
    suspend fun rpc(name:String,data:JsonObject):JsonElement = Json.parseToJsonElement(client.postgrest.rpc(name,data).data)
    suspend fun signedUrl(path:String):String = client.storage.from("club-documents").createSignedUrl(path,5.minutes)
    suspend fun upload(context:Context,uri:Uri,module:Int,recordId:String,requirement:String) {
        val resolver=context.contentResolver
        var name="Document"
        resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) name=it.getString(0) }
        val mime=resolver.getType(uri) ?: error("This file type could not be identified.")
        require(mime in setOf("application/pdf","image/jpeg","image/png","text/csv","video/mp4")) { "Choose PDF, JPG, PNG, CSV, or an MP4 training video." }
        val bytes=resolver.openInputStream(uri)?.use { input ->
            val output=java.io.ByteArrayOutputStream()
            val buffer=ByteArray(8192)
            while(true) {
                val count=input.read(buffer)
                if(count<0)break
                require(output.size()+count<=20*1024*1024){"Choose a file no larger than 20 MB."}
                output.write(buffer,0,count)
            }
            output.toByteArray()
        } ?: error("Cannot read this file.")
        require(bytes.isNotEmpty() && bytes.size<=20*1024*1024) { "Choose a file between 1 byte and 20 MB." }
        val user=client.auth.currentUserOrNull()?.id ?: error("Please sign in again.")
        val extension=mapOf("application/pdf" to "pdf","image/jpeg" to "jpg","image/png" to "png","text/csv" to "csv","video/mp4" to "mp4").getValue(mime)
        val path="$user/$module/$recordId/${UUID.randomUUID()}.$extension"
        client.storage.from("club-documents").upload(path,bytes) { upsert=false;contentType=ContentType.parse(mime) }
        if(mime=="video/mp4") verifyVideo(path)
        rpc("register_document",buildJsonObject { put("p_data",buildJsonObject {
            put("module",module);put("record_id",recordId);put("requirement",requirement);put("name",name);put("path",path)
        }) })
    }
    private fun verifyVideo(path:String) {
        val connection=URL(BuildConfig.SUPABASE_URL.trimEnd('/')+"/functions/v1/verify-training-video").openConnection() as HttpURLConnection
        try {
            connection.requestMethod="POST";connection.doOutput=true;connection.connectTimeout=15000;connection.readTimeout=30000
            connection.setRequestProperty("apikey",BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            connection.setRequestProperty("Authorization","Bearer "+(client.auth.currentSessionOrNull()?.accessToken ?: error("Sign in again.")))
            connection.setRequestProperty("Content-Type","application/json")
            connection.outputStream.use { it.write(buildJsonObject { put("path",path) }.toString().toByteArray()) }
            if(connection.responseCode !in 200..299) error("Video verification failed. Use an MP4 clip no longer than 10 seconds and check the server configuration.")
        } finally { connection.disconnect() }
    }
}
