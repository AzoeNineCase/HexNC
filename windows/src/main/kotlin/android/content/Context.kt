package android.content

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stand-in for Android's `Context`, so the whole `data/` layer keeps its Android source shape on
 * Windows.
 *
 * HexNC's data layer only ever asks a `Context` for three things — `applicationContext`, its files
 * directory, and named preferences — so re-implementing those three keeps
 * `di/AppContainer.kt`, `data/prefs/SettingsStore.kt`, `data/netease/NeteaseCookieStore.kt`,
 * `data/netease/NeteaseIdentity.kt` and `data/db/HexNCDatabase.kt` **byte-identical** to the Android
 * build. Everything genuinely platform-specific (playback, folder scanning, the login browser, the
 * file pickers) is rewritten for the desktop instead.
 */
open class Context(private val baseDir: File) {

    private val prefs = HashMap<String, SharedPreferences>()

    init {
        // Everything below (covers, the media cache, hexnc.db, prefs/) writes into here.
        baseDir.mkdirs()
    }

    val applicationContext: Context get() = this

    val filesDir: File get() = baseDir

    fun getDatabasePath(name: String): File = File(baseDir, name)

    @Synchronized
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefs.getOrPut(name) { FilePrefs(File(baseDir, "prefs/$name.properties")) }

    companion object {
        /** Kept only so call sites read like the Android original. */
        const val MODE_PRIVATE = 0
    }
}

/** The subset of `android.content.SharedPreferences` this app uses. */
interface SharedPreferences {

    fun getString(key: String, defValue: String?): String?
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun getFloat(key: String, defValue: Float): Float
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun contains(key: String): Boolean
    fun getAll(): Map<String, *>
    fun edit(): Editor
    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener)
    fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener)

    fun interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?)
    }

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
        fun commit(): Boolean
    }
}

/**
 * File-backed [SharedPreferences].
 *
 * Values are written to a `java.util.Properties` file with a one-character type tag in front of the
 * key, which keeps a value's Kotlin type across restarts (the Android original relies on the same
 * call site always using the same getter, and being explicit costs nothing here). `Properties`
 * escapes non-ASCII on write and unescapes on read, so Chinese search history round-trips.
 */
class FilePrefs(private val file: File) : SharedPreferences {

    private val lock = Any()
    private val values = LinkedHashMap<String, Any?>()
    private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    init {
        load()
    }

    private fun load() {
        if (!file.exists()) return
        val props = Properties()
        try {
            FileInputStream(file).use { props.load(it) }
        } catch (_: Exception) {
            return
        }
        props.forEach { (rawKey, rawValue) ->
            val key = rawKey as? String ?: return@forEach
            val value = rawValue as? String ?: return@forEach
            if (key.length < 2) return@forEach
            val name = key.substring(2)
            values[name] = when (key[0]) {
                'b' -> value.toBoolean()
                'i' -> value.toIntOrNull() ?: 0
                'l' -> value.toLongOrNull() ?: 0L
                'f' -> value.toFloatOrNull() ?: 0f
                else -> value
            }
        }
    }

    private fun persist() {
        val props = Properties()
        values.forEach { (name, value) ->
            val tag = when (value) {
                is Boolean -> 'b'
                is Int -> 'i'
                is Long -> 'l'
                is Float -> 'f'
                else -> 's'
            }
            props.setProperty("$tag:$name", value?.toString() ?: "")
        }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { props.store(it, "HexNC") }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    override fun getString(key: String, defValue: String?): String? = synchronized(lock) { values[key] as? String ?: defValue }

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        synchronized(lock) { values[key] as? Boolean ?: defValue }

    override fun getFloat(key: String, defValue: Float): Float =
        synchronized(lock) { (values[key] as? Number)?.toFloat() ?: defValue }

    override fun getInt(key: String, defValue: Int): Int =
        synchronized(lock) { (values[key] as? Number)?.toInt() ?: defValue }

    override fun getLong(key: String, defValue: Long): Long =
        synchronized(lock) { (values[key] as? Number)?.toLong() ?: defValue }

    override fun contains(key: String): Boolean = synchronized(lock) { values.containsKey(key) }

    override fun getAll(): Map<String, *> = synchronized(lock) { LinkedHashMap(values) }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.addIfAbsent(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(listener)
    }

    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removals = HashSet<String>()
        private var clear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = set(key, value)
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = set(key, value)
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = set(key, value)
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = set(key, value)
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = set(key, value)

        override fun remove(key: String): SharedPreferences.Editor {
            removals += key
            pending -= key
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clear = true
            pending.clear()
            removals.clear()
            return this
        }

        private fun set(key: String, value: Any?): SharedPreferences.Editor {
            pending[key] = value
            removals -= key
            return this
        }

        override fun apply() {
            commit()
        }

        override fun commit(): Boolean {
            val changed = synchronized(lock) {
                if (clear) values.clear()
                removals.forEach { values.remove(it) }
                pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                persist()
                (removals + pending.keys).toList()
            }
            changed.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this@FilePrefs, key) } }
            return true
        }
    }
}
