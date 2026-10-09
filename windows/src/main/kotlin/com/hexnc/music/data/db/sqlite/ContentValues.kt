package com.hexnc.music.data.db.sqlite

/**
 * Column-name to value bag, shaped like Android's `ContentValues`.
 *
 * A `null` value and an absent key are deliberately the same thing here (both mean "leave it
 * alone" for `update`, "NULL" for `insert`), which is how every `toValues()` in the DAOs uses it.
 */
class ContentValues {
    private val map = LinkedHashMap<String, Any?>()

    fun put(key: String, value: String?) {
        map[key] = value
    }

    fun put(key: String, value: Long?) {
        map[key] = value
    }

    fun put(key: String, value: Int?) {
        map[key] = value
    }

    fun put(key: String, value: Double?) {
        map[key] = value
    }

    fun put(key: String, value: Float?) {
        map[key] = value
    }

    fun put(key: String, value: Boolean?) {
        map[key] = value
    }

    fun put(key: String, value: ByteArray?) {
        map[key] = value
    }

    fun putNull(key: String) {
        map[key] = null
    }

    fun remove(key: String) {
        map.remove(key)
    }

    fun containsKey(key: String): Boolean = map.containsKey(key)

    fun size(): Int = map.size

    fun keySet(): Set<String> = map.keys

    fun getAsString(key: String): String? = map[key]?.toString()

    fun getAsLong(key: String): Long? = (map[key] as? Number)?.toLong()

    fun getAsInteger(key: String): Int? = (map[key] as? Number)?.toInt()

    fun getAsDouble(key: String): Double? = (map[key] as? Number)?.toDouble()

    fun getAsBoolean(key: String): Boolean? = (map[key] as? Number)?.toInt()?.let { it != 0 }

    /** Insertion-ordered entries; the shim binds columns in this order. */
    internal fun entries(): List<Pair<String, Any?>> = map.entries.map { it.key to it.value }
}
