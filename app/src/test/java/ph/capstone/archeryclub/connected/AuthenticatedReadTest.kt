package ph.capstone.archeryclub.connected

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AuthenticatedReadTest {
    private class Expired : Exception()
    @Test fun expiredReadRefreshesBeforeOneRetry() = runTest {
        val calls=mutableListOf<String>()
        val result=authenticatedRead({calls.add("refresh")},{it is Expired}) {
            calls.add("read")
            if(calls.size==1)throw Expired()
            "workspace"
        }
        assertEquals("workspace",result)
        assertEquals(listOf("read","refresh","read"),calls)
    }
    @Test fun transportFailureDoesNotRotateSessionOrReplayRead() = runTest {
        var reads=0;var refreshes=0
        try { authenticatedRead({refreshes++},{it is Expired}) { reads++;throw IOException() };fail() }
        catch(_:IOException) { }
        assertEquals(1,reads);assertEquals(0,refreshes)
    }
    @Test fun repeatedAuthorizationFailureStopsAfterOneRefresh() = runTest {
        var reads=0;var refreshes=0
        try { authenticatedRead({refreshes++},{it is Expired}) { reads++;throw Expired() };fail() }
        catch(_:Expired) { }
        assertEquals(2,reads);assertEquals(1,refreshes)
    }
}
