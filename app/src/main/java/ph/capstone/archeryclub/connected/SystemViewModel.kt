package ph.capstone.archeryclub.connected

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.BuildConfig

data class SystemState(val signedIn:Boolean=false,val busy:Boolean=false,val workspace:JsonObject?=null,val message:String?=null,val error:String?=null,val recovery:Boolean=false)
class SystemViewModel(val gateway:SupabaseGateway):ViewModel() {
    private val mutable=MutableStateFlow(SystemState(busy=true))
    val state=mutable.asStateFlow()
    private var operation:Job?=null
    init { viewModelScope.launch {
        gateway.client.auth.sessionStatus.collect { session ->
            when(session) {
                is SessionStatus.Authenticated -> {
                    val user=gateway.client.auth.currentUserOrNull()?.id
                    if(mutable.value.workspace?.get("user_id")?.jsonPrimitive?.content!=user) {
                        mutable.value=mutable.value.copy(signedIn=true,workspace=null)
                        refresh()
                    }
                }
                is SessionStatus.NotAuthenticated -> { operation?.cancel();mutable.value=SystemState() }
                is SessionStatus.RefreshFailure -> {
                    operation?.cancel()
                    mutable.value=SystemState(error="Your session could not be refreshed. Please sign in again.")
                }
                else -> Unit
            }
        }
    } }
    fun clearMessage(){mutable.value=mutable.value.copy(message=null,error=null)}
    fun error(message:String){mutable.value=mutable.value.copy(error=message)}
    fun auth(action:String,email:String,password:String,code:String="")=runAction {
        require(BuildConfig.EMAIL_ENABLED || action !in setOf("signup","recover","verify","verify_invite")){"Email services are not enabled yet. Use an existing account."}
        when(action){
            "signin"->{gateway.signIn(email,password);gateway.initialize();mutable.value=mutable.value.copy(signedIn=true,workspace=gateway.workspace())}
            "signup"->{require(password.length>=8){"Use at least 8 characters for your password."};gateway.signUp(email,password);mutable.value=mutable.value.copy(message="Check your email to verify your account, then sign in.")}
            "recover"->{gateway.recover(email);mutable.value=mutable.value.copy(message="If the account exists, a recovery email has been sent.",recovery=true)}
            "verify"->{gateway.verify(email,code,mutable.value.recovery);gateway.initialize();mutable.value=mutable.value.copy(signedIn=true,workspace=gateway.workspace(),message="Email verified.")}
            "verify_invite"->{gateway.verifyInvitation(email,code);gateway.initialize();mutable.value=mutable.value.copy(signedIn=true,workspace=gateway.workspace(),recovery=true,message="Invitation verified. Choose your password.")}
            "password"->{require(password.length>=8){"Use at least 8 characters."};gateway.changePassword(password);mutable.value=mutable.value.copy(recovery=false,message="Password updated.")}
            "signout"->{gateway.signOut();mutable.value=SystemState()}
        }
    }
    fun refresh()=runAction {
        if(mutable.value.workspace==null)gateway.initialize()
        try{mutable.value=mutable.value.copy(signedIn=true,workspace=gateway.workspace())}
        catch(e:Exception){mutable.value=mutable.value.copy(workspace=null);throw e}
    }
    fun command(action:String,data:JsonObject,onDone:()->Unit={})=runAction {
        require(BuildConfig.EMAIL_ENABLED || action!="invite_account"){"Email invitations are not enabled yet."}
        if(action=="invite_account")gateway.inviteAccount(data) else gateway.command(action,data)
        // Close a successfully saved form even if the following read fails. Retrying that
        // form could otherwise create a duplicate payment, event or equipment request.
        withContext(Dispatchers.Main){onDone()}
        refreshAfterSave("Saved to the club system.")
    }
    fun rpc(name:String,data:JsonObject,onDone:(JsonElement)->Unit)=runAction {val result=gateway.rpc(name,data);withContext(Dispatchers.Main){onDone(result)}}
    fun upload(context:Context,uri:Uri,module:Int,record:String,requirement:String,onDone:()->Unit={})=runAction {
        gateway.upload(context,uri,module,record,requirement)
        withContext(Dispatchers.Main){onDone()}
        refreshAfterSave("Document uploaded.")
    }
    private suspend fun refreshAfterSave(message:String){
        mutable.value=mutable.value.copy(message=message)
        try{mutable.value=mutable.value.copy(workspace=gateway.workspace())}
        catch(e:CancellationException){throw e}
        catch(e:Exception){mutable.value=mutable.value.copy(error="$message Refresh to load the updated records; do not submit it again.")}
    }
    fun openDocument(path:String,onReady:(String)->Unit)=runAction {val url=gateway.signedUrl(path);withContext(Dispatchers.Main){onReady(url)}}
    private fun runAction(block:suspend ()->Unit){
        if(operation?.isActive==true)return
        mutable.value=mutable.value.copy(busy=true,error=null,message=null)
        operation=viewModelScope.launch {
            try {withTimeout(45000){withContext(Dispatchers.IO){block()}}}
            catch(e:TimeoutCancellationException){mutable.value=mutable.value.copy(error="The connection timed out. Refresh and check whether your change was saved before submitting again.")}
            catch(e:CancellationException){throw e}
            catch(e:AuthRestException){mutable.value=mutable.value.copy(error=authErrorMessage(e.error))}
            catch(e:PostgrestRestException){mutable.value=mutable.value.copy(error=workflowErrorMessage(e.code,e.error))}
            catch(e:RestException){mutable.value=mutable.value.copy(error=if(e.statusCode==401)"Your session expired. Sign in again." else "The request could not be completed. Refresh and try again.")}
            catch(e:Exception){
                // Never expose request headers, access tokens or database internals in the UI/logs.
                val raw=e.message.orEmpty()
                val message=raw.substringBefore("\n").take(240)
                mutable.value=mutable.value.copy(error=if(raw.contains("Bearer",true)||raw.contains("apikey",true)) "Request failed. Check your connection and try again." else message.ifBlank{"Request failed. Please try again."})
            } finally {mutable.value=mutable.value.copy(busy=false)}
        }
    }
}
internal fun workflowErrorMessage(code:String?,message:String):String=when(code){
    "PT409"->"This record changed. Refresh before trying again."
    "42501"->"Your account does not have permission for this action."
    "23505"->"This record already exists. Refresh to view it."
    "23503"->"A related record is missing or no longer available. Refresh and choose it again."
    "23514","23502","22P02","22007","22008","22003"->"Check the required fields, amounts and dates before saving."
    "P0001"->message.lineSequence().firstOrNull()?.take(240)?.takeUnless{it.contains("Bearer",true)||it.contains("apikey",true)}?:"Check your entries and try again."
    else->"The request could not be completed. Refresh and try again."
}
internal fun authErrorMessage(code:String):String=when(code){
    "invalid_credentials"->"The email or password is incorrect. Please try again."
    "email_not_confirmed"->"Verify your email before signing in."
    "otp_expired"->"This verification code is invalid or expired. Request a new code."
    "over_email_send_rate_limit","over_request_rate_limit"->"Too many attempts. Please wait a moment and try again."
    "weak_password"->"Choose a stronger password with at least 8 characters."
    "user_banned"->"This account is disabled. Contact your club administrator."
    else->"Account access failed. Check your details and try again."
}
fun JsonObject.text(key:String):String=(get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonObject.records(key:String):List<JsonObject> = (get(key) as? JsonArray)?.mapNotNull {it as? JsonObject}.orEmpty()
