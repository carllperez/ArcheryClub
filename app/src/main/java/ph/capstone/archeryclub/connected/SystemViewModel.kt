package ph.capstone.archeryclub.connected

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.exception.AuthRestException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

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
                is SessionStatus.RefreshFailure -> mutable.value=SystemState(error="Your session could not be refreshed. Please sign in again.")
                else -> Unit
            }
        }
    } }
    fun clearMessage(){mutable.value=mutable.value.copy(message=null,error=null)}
    fun error(message:String){mutable.value=mutable.value.copy(error=message)}
    fun auth(action:String,email:String,password:String,code:String="")=runAction {
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
        if(action=="invite_account")gateway.inviteAccount(data) else gateway.command(action,data)
        mutable.value=mutable.value.copy(workspace=gateway.workspace(),message="Saved to the club system.")
        withContext(Dispatchers.Main){onDone()}
    }
    fun rpc(name:String,data:JsonObject,onDone:(JsonElement)->Unit)=runAction {val result=gateway.rpc(name,data);withContext(Dispatchers.Main){onDone(result)}}
    fun upload(context:Context,uri:Uri,module:Int,record:String,requirement:String)=runAction {
        gateway.upload(context,uri,module,record,requirement);mutable.value=mutable.value.copy(workspace=gateway.workspace(),message="Document uploaded.")
    }
    fun openDocument(path:String,onReady:(String)->Unit)=runAction {val url=gateway.signedUrl(path);withContext(Dispatchers.Main){onReady(url)}}
    private fun runAction(block:suspend ()->Unit){
        if(operation?.isActive==true)return
        mutable.value=mutable.value.copy(busy=true,error=null,message=null)
        operation=viewModelScope.launch {
            try {withTimeout(45000){withContext(Dispatchers.IO){block()}}}
            catch(e:TimeoutCancellationException){mutable.value=mutable.value.copy(error="The connection timed out. Check your connection and retry.")}
            catch(e:CancellationException){throw e}
            catch(e:AuthRestException){mutable.value=mutable.value.copy(error=authErrorMessage(e.error))}
            catch(e:Exception){
                // Never expose request headers, access tokens or database internals in the UI/logs.
                val raw=e.message.orEmpty()
                val message=raw.substringBefore("\n").take(240)
                mutable.value=mutable.value.copy(error=if(raw.contains("Bearer",true)||raw.contains("apikey",true)) "Request failed. Check your connection and try again." else message.ifBlank{"Request failed. Please try again."})
            } finally {mutable.value=mutable.value.copy(busy=false)}
        }
    }
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
