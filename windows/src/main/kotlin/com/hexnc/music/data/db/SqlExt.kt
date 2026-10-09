package com.hexnc.music.data.db

import com.hexnc.music.data.db.sqlite.Cursor
import com.hexnc.music.data.db.sqlite.SQLiteDatabase

/** Run [block] inside a transaction, rolling back on any exception. */
internal inline fun <T> SQLiteDatabase.tx(block: () -> T): T {
    beginTransaction()
    return try {
        val result = block()
        setTransactionSuccessful()
        result
    } finally {
        endTransaction()
    }
}

/**
 * Bind arguments for `execSQL`.
 *
 * `arrayOf` is an inline function with a **reified** type parameter, so passing a mix of `Int`/`Long`
 * and `String` makes it infer the intersection type `Comparable<*> & Serializable`, which the Kotlin
 * compiler rejects. Pinning the element type to `Any?` here keeps every call site simple.
 */
internal fun sqlArgs(vararg values: Any?): Array<Any?> = arrayOf<Any?>(*values)

internal fun Cursor.str(name: String): String {
    val i = getColumnIndex(name)
    return if (i < 0 || isNull(i)) "" else getString(i)
}

internal fun Cursor.strOrNull(name: String): String? {
    val i = getColumnIndex(name)
    return if (i < 0 || isNull(i)) null else getString(i)
}

internal fun Cursor.long(name: String, fallback: Long = 0L): Long {
    val i = getColumnIndex(name)
    return if (i < 0 || isNull(i)) fallback else getLong(i)
}

internal fun Cursor.longOrNull(name: String): Long? {
    val i = getColumnIndex(name)
    return if (i < 0 || isNull(i)) null else getLong(i)
}

internal fun Cursor.int(name: String, fallback: Int = 0): Int {
    val i = getColumnIndex(name)
    return if (i < 0 || isNull(i)) fallback else getInt(i)
}

internal fun Cursor.bool(name: String): Boolean = int(name) != 0

/** Read a single value with [sql], returning null when the query has no rows. */
internal fun SQLiteDatabase.scalarLong(sql: String, vararg args: String): Long? =
    rawQuery(sql, args).use { if (it.moveToFirst()) it.getLong(0) else null }
