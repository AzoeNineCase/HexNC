package com.hexnc.music.data.db.sqlite

import java.util.Locale

/**
 * Read-only result set, shaped like the subset of Android's `Cursor` that HexNC's DAOs use.
 *
 * Rows are materialised when the query runs (see [SQLiteDatabase.rawQuery]) because every call site
 * in this project walks the whole result anyway; the DAO column readers live in
 * `com.hexnc.music.data.db.SqlExt`.
 */
interface Cursor : AutoCloseable {
    fun moveToFirst(): Boolean
    fun moveToNext(): Boolean
    fun getCount(): Int
    fun getColumnIndex(columnName: String): Int
    fun getColumnIndexOrThrow(columnName: String): Int
    fun isNull(columnIndex: Int): Boolean

    /** Never null: a SQL NULL reads back as the empty string, as `SqlExt.str` expects. */
    fun getString(columnIndex: Int): String
    fun getLong(columnIndex: Int): Long
    fun getInt(columnIndex: Int): Int
    fun getDouble(columnIndex: Int): Double

    override fun close()
}

internal class JdbcCursor(columns: List<String>, private val rows: List<Array<Any?>>) : Cursor {
    private val index = HashMap<String, Int>(columns.size * 2)
    private var position = -1

    init {
        columns.forEachIndexed { i, name -> index.putIfAbsent(name.lowercase(Locale.ROOT), i) }
    }

    private fun value(columnIndex: Int): Any? = rows[position][columnIndex]

    override fun moveToFirst(): Boolean {
        if (rows.isEmpty()) {
            position = -1
            return false
        }
        position = 0
        return true
    }

    override fun moveToNext(): Boolean {
        if (position + 1 >= rows.size) return false
        position++
        return true
    }

    override fun getCount(): Int = rows.size

    override fun getColumnIndex(columnName: String): Int = index[columnName.lowercase(Locale.ROOT)] ?: -1

    override fun getColumnIndexOrThrow(columnName: String): Int {
        val i = getColumnIndex(columnName)
        if (i < 0) throw IllegalArgumentException("column '$columnName' does not exist")
        return i
    }

    override fun isNull(columnIndex: Int): Boolean = value(columnIndex) == null

    override fun getString(columnIndex: Int): String = value(columnIndex)?.toString() ?: ""

    override fun getLong(columnIndex: Int): Long = when (val v = value(columnIndex)) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: 0L
        else -> 0L
    }

    override fun getInt(columnIndex: Int): Int = when (val v = value(columnIndex)) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: 0
        else -> 0
    }

    override fun getDouble(columnIndex: Int): Double = when (val v = value(columnIndex)) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    override fun close() {
        position = -1
    }
}
