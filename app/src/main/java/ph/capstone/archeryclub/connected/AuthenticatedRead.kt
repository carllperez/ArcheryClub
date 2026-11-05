package ph.capstone.archeryclub.connected

import io.github.jan.supabase.exceptions.RestException

/** Only reads may be replayed after refreshing an expired access token. */
internal suspend fun <T> authenticatedRead(
    refreshSession: suspend () -> Unit,
    isUnauthorized: (Exception) -> Boolean = { it is RestException && it.statusCode == 401 },
    read: suspend () -> T,
): T = try {
    read()
} catch (error: Exception) {
    if (!isUnauthorized(error)) throw error
    refreshSession()
    read() // A second failure is surfaced; never enter an unbounded retry loop.
}
