package ph.capstone.archeryclub.backend

import ph.capstone.archeryclub.BuildConfig

object SupabaseConfig {
    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()

    val url: String get() = BuildConfig.SUPABASE_URL
    val publishableKey: String get() = BuildConfig.SUPABASE_PUBLISHABLE_KEY
}
