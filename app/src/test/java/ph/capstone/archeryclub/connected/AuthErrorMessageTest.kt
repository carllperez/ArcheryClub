package ph.capstone.archeryclub.connected

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthErrorMessageTest {
    @Test fun credentialsDoNotDiscloseWhichAccountFieldFailed() {
        assertEquals("The email or password is incorrect. Please try again.", authErrorMessage("invalid_credentials"))
    }
    @Test fun unknownServerErrorsCannotExposeTheirPayload() {
        val message = authErrorMessage("internal_error secret-value")
        assertFalse(message.contains("secret-value"))
        assertEquals("Account access failed. Check your details and try again.", message)
    }
    @Test fun unverifiedEmailHasAnActionableExplanation() {
        assertEquals("Verify your email before signing in.", authErrorMessage("email_not_confirmed"))
    }
    @Test fun workflowConflictsGiveSafeRecoveryGuidance() {
        assertEquals("This record changed. Refresh before trying again.",workflowErrorMessage("PT409","headers and internals"))
        assertEquals("Missing requirement: Proof",workflowErrorMessage("P0001","Missing requirement: Proof"))
        assertEquals("The request could not be completed. Refresh and try again. (error 54000)",workflowErrorMessage("54000","x"))
        assertEquals("The request could not be completed. Refresh and try again.",workflowErrorMessage(null,"x"))
        assertTrue(workflowErrorMessage("22P05","\\u0000 cannot be converted").startsWith("A pasted character"))
        for(code in listOf("23514","23505","42501",null))assertFalse(workflowErrorMessage(code,"private table details").contains("private"))
    }
}
