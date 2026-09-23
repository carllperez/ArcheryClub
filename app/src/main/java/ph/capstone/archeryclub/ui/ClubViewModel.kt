package ph.capstone.archeryclub.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ph.capstone.archeryclub.data.ClubRepository
import ph.capstone.archeryclub.domain.ApplicationForm

data class ActionState(val busy: Boolean = false, val message: String? = null, val error: String? = null)

class ClubViewModel(private val repository: ClubRepository) : ViewModel() {
    val snapshot = repository.snapshot
    private val mutable = MutableStateFlow(ActionState())
    val actions = mutable.asStateFlow()

    fun clearMessage() { mutable.value = mutable.value.copy(message = null, error = null) }
    fun reportError(message: String) { mutable.value = mutable.value.copy(error = message) }

    fun saveDraft(form: ApplicationForm) = act("Draft saved on this device.") {
        repository.saveApplicationDraft(form)
    }
    fun submit(form: ApplicationForm) = act("Preview submission saved. Nothing has been sent to a club.") {
        repository.submitApplication(form)
    }
    fun updateProfile(name: String, phone: String) = act("Profile saved on this device.") {
        repository.updateMemberContact(name, phone)
    }
    fun renew() = act("Preview renewal request saved. Membership status is unchanged.") {
        repository.requestRenewal()
    }

    private fun act(success: String, operation: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = ActionState(busy = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { operation() }
                mutable.value = ActionState(message = success)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                mutable.value = ActionState(error = exception.message ?: "Unable to save. Please try again.")
            }
        }
    }
}
