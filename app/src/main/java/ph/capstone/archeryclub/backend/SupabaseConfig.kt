package ph.capstone.archeryclub.backend

import ph.capstone.archeryclub.BuildConfig

object SupabaseConfig {
    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() &&
                BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank() &&
                !BuildConfig.SUPABASE_PUBLISHABLE_KEY.contains("placeholder", ignoreCase = true) &&
                !BuildConfig.SUPABASE_PUBLISHABLE_KEY.contains("REPLACE_ME", ignoreCase = true)

    val url: String get() = BuildConfig.SUPABASE_URL
    val publishableKey: String get() = BuildConfig.SUPABASE_PUBLISHABLE_KEY
}
