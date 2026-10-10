package com.hexnc.music.data.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI language preference. */
enum class AppLanguage(val key: String) {
    SYSTEM("system"),
    CHINESE("zh"),
    ENGLISH("en");

    companion object {
        fun fromKey(key: String?): AppLanguage = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** Light / dark handling; mirrors Miuix's `ColorSchemeMode` intent but stays decoupled from it. */
enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** How the now-playing list advances when a track ends. */
enum class PlayMode(val key: String) {
    SEQUENCE("sequence"),
    REPEAT_ALL("repeat_all"),
    REPEAT_ONE("repeat_one"),
    SHUFFLE("shuffle");

    companion object {
        fun fromKey(key: String?): PlayMode = entries.firstOrNull { it.key == key } ?: SEQUENCE
    }
}

/**
 * Immutable snapshot of every user-tunable setting. Held in a [StateFlow] so Compose screens
 * recompose on change without needing to re-read `SharedPreferences`.
 */
data class AppSettings(
    val language: AppLanguage = AppLanguage.SYSTEM,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val auroraThemeKey: String = "aurora",
    val amoledBlack: Boolean = false,
    val dynamicColor: Boolean = true,
    val seedFromCover: Boolean = true,
    // Lyrics
    val lyricFontSizeSp: Float = 22f,
    val lyricTranslationScale: Float = 0.78f,
    val lyricLineSpacing: Float = 1.35f,
    val lyricShowTranslation: Boolean = true,
    val lyricShowRoman: Boolean = false,
    val lyricBlur: Boolean = true,
    // Playback
    val playMode: PlayMode = PlayMode.SEQUENCE,
    val neteaseQuality: String = "exhigh",
    val crossfadeMs: Int = 0,
    val autoPlayNext: Boolean = true,
    val skipOnError: Boolean = true,
    /** Desktop-only: a phone had hardware volume keys, a PC window needs its own slider. */
    val volume: Float = 1f,
    // Library
    val lastTab: String = "library",
    val recentSearches: List<String> = emptyList(),
    /**
     * Song ids, most recently played first.
     *
     * Kept here rather than in the database on purpose: play order is a per-device convenience, and
     * storing it in `songs` would have forced a library migration on every existing install for
     * something that is allowed to be forgotten.
     */
    val recentlyPlayedIds: List<String> = emptyList(),
    val neteaseCookiePersisted: Boolean = false,
    val onboardingDone: Boolean = false
)

/**
 * `SharedPreferences`-backed settings store.
 *
 * Deliberately avoids DataStore: this project ships without any annotation processor or extra
 * codegen plugin, so a plain listener-driven store keeps the build surface small and predictable.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    val current: AppSettings get() = _state.value

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _state.value = read()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun close() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun read(): AppSettings = AppSettings(
        language = AppLanguage.fromKey(prefs.getString(KEY_LANGUAGE, null)),
        themeMode = ThemeMode.fromKey(prefs.getString(KEY_THEME_MODE, null)),
        auroraThemeKey = prefs.getString(KEY_AURORA, null) ?: "aurora",
        amoledBlack = prefs.getBoolean(KEY_AMOLED, false),
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, true),
        seedFromCover = prefs.getBoolean(KEY_SEED_FROM_COVER, true),
        lyricFontSizeSp = prefs.getFloat(KEY_LYRIC_SIZE, 22f),
        lyricTranslationScale = prefs.getFloat(KEY_LYRIC_TRANS_SCALE, 0.78f),
        lyricLineSpacing = prefs.getFloat(KEY_LYRIC_SPACING, 1.35f),
        lyricShowTranslation = prefs.getBoolean(KEY_LYRIC_TRANS, true),
        lyricShowRoman = prefs.getBoolean(KEY_LYRIC_ROMAN, false),
        lyricBlur = prefs.getBoolean(KEY_LYRIC_BLUR, true),
        playMode = PlayMode.fromKey(prefs.getString(KEY_PLAY_MODE, null)),
        neteaseQuality = prefs.getString(KEY_QUALITY, null) ?: "exhigh",
        crossfadeMs = prefs.getInt(KEY_CROSSFADE, 0),
        autoPlayNext = prefs.getBoolean(KEY_AUTO_NEXT, true),
        skipOnError = prefs.getBoolean(KEY_SKIP_ERROR, true),
        volume = prefs.getFloat(KEY_VOLUME, 1f),
        lastTab = prefs.getString(KEY_LAST_TAB, null) ?: "library",
        recentSearches = prefs.getString(KEY_RECENT_SEARCHES, null)
            ?.split(SEARCH_SEP)
            ?.filter { it.isNotBlank() }
            ?.take(12)
            ?: emptyList(),
        recentlyPlayedIds = prefs.getString(KEY_RECENT_PLAYED, null)
            ?.split(SEARCH_SEP)
            ?.filter { it.isNotBlank() }
            ?.take(RECENT_PLAYED_MAX)
            ?: emptyList(),
        neteaseCookiePersisted = prefs.getBoolean(KEY_COOKIE_PERSISTED, false),
        onboardingDone = prefs.getBoolean(KEY_ONBOARDING, false)
    )

    private inline fun mutate(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _state.value = read()
    }

    fun setLanguage(value: AppLanguage) = mutate { putString(KEY_LANGUAGE, value.key) }

    fun setThemeMode(value: ThemeMode) = mutate { putString(KEY_THEME_MODE, value.key) }

    fun setAuroraTheme(key: String) = mutate { putString(KEY_AURORA, key) }

    /** Pure-black backgrounds for OLED panels; only meaningful while the dark scheme is active. */
    fun setAmoledBlack(value: Boolean) = mutate { putBoolean(KEY_AMOLED, value) }

    fun setDynamicColor(value: Boolean) = mutate { putBoolean(KEY_DYNAMIC_COLOR, value) }

    fun setSeedFromCover(value: Boolean) = mutate { putBoolean(KEY_SEED_FROM_COVER, value) }

    fun setLyricFontSize(sp: Float) = mutate { putFloat(KEY_LYRIC_SIZE, sp.coerceIn(12f, 48f)) }

    fun setLyricTranslationScale(scale: Float) =
        mutate { putFloat(KEY_LYRIC_TRANS_SCALE, scale.coerceIn(0.5f, 1.2f)) }

    fun setLyricLineSpacing(scale: Float) =
        mutate { putFloat(KEY_LYRIC_SPACING, scale.coerceIn(1.0f, 2.4f)) }

    fun setLyricShowTranslation(value: Boolean) = mutate { putBoolean(KEY_LYRIC_TRANS, value) }

    fun setLyricShowRoman(value: Boolean) = mutate { putBoolean(KEY_LYRIC_ROMAN, value) }

    fun setLyricBlur(value: Boolean) = mutate { putBoolean(KEY_LYRIC_BLUR, value) }

    fun setPlayMode(value: PlayMode) = mutate { putString(KEY_PLAY_MODE, value.key) }

    fun setNeteaseQuality(level: String) = mutate { putString(KEY_QUALITY, level) }

    fun setCrossfadeMs(ms: Int) = mutate { putInt(KEY_CROSSFADE, ms.coerceIn(0, 12000)) }

    fun setAutoPlayNext(value: Boolean) = mutate { putBoolean(KEY_AUTO_NEXT, value) }

    fun setSkipOnError(value: Boolean) = mutate { putBoolean(KEY_SKIP_ERROR, value) }

    fun setVolume(value: Float) = mutate { putFloat(KEY_VOLUME, value.coerceIn(0f, 1f)) }

    fun setLastTab(tab: String) = mutate { putString(KEY_LAST_TAB, tab) }

    fun setOnboardingDone(value: Boolean) = mutate { putBoolean(KEY_ONBOARDING, value) }

    fun setCookiePersisted(value: Boolean) = mutate { putBoolean(KEY_COOKIE_PERSISTED, value) }

    fun addRecentSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val next = (listOf(q) + current.recentSearches.filterNot { it.equals(q, ignoreCase = true) })
            .take(12)
        mutate { putString(KEY_RECENT_SEARCHES, next.joinToString(SEARCH_SEP)) }
    }

    fun clearRecentSearches() = mutate { remove(KEY_RECENT_SEARCHES) }

    /**
     * Moves a song to the front of the play history, keeping at most [RECENT_PLAYED_MAX] entries.
     *
     * Re-playing something already in the list re-orders it instead of duplicating it, which is what
     * makes the shelf on the library screen read as "what I have been listening to" rather than a log.
     */
    fun addRecentlyPlayedId(id: String) {
        if (id.isBlank()) return
        val next = (listOf(id) + current.recentlyPlayedIds.filterNot { it == id }).take(RECENT_PLAYED_MAX)
        mutate { putString(KEY_RECENT_PLAYED, next.joinToString(SEARCH_SEP)) }
    }

    fun clearRecentlyPlayed() = mutate { remove(KEY_RECENT_PLAYED) }

    companion object {
        private const val PREFS_NAME = "hexnc_settings"
        private const val SEARCH_SEP = "\u0001"
        private const val RECENT_PLAYED_MAX = 24

        private const val KEY_LANGUAGE = "language"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_AURORA = "aurora_theme"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_AMOLED = "amoled_black"
        private const val KEY_SEED_FROM_COVER = "seed_from_cover"
        private const val KEY_LYRIC_SIZE = "lyric_font_size"
        private const val KEY_LYRIC_TRANS_SCALE = "lyric_translation_scale"
        private const val KEY_LYRIC_SPACING = "lyric_line_spacing"
        private const val KEY_LYRIC_TRANS = "lyric_show_translation"
        private const val KEY_LYRIC_ROMAN = "lyric_show_roman"
        private const val KEY_LYRIC_BLUR = "lyric_blur"
        private const val KEY_PLAY_MODE = "play_mode"
        private const val KEY_QUALITY = "netease_quality"
        private const val KEY_CROSSFADE = "crossfade_ms"
        private const val KEY_AUTO_NEXT = "auto_play_next"
        private const val KEY_SKIP_ERROR = "skip_on_error"
        private const val KEY_VOLUME = "volume"
        private const val KEY_LAST_TAB = "last_tab"
        private const val KEY_RECENT_SEARCHES = "recent_searches"
        private const val KEY_RECENT_PLAYED = "recently_played"
        private const val KEY_COOKIE_PERSISTED = "netease_cookie_persisted"
        private const val KEY_ONBOARDING = "onboarding_done"
    }
}
