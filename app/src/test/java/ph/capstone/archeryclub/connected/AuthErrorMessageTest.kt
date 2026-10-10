package ph.capstone.archeryclub.connected

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
