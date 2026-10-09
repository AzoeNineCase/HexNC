package com.hexnc.music.data.db.sqlite

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.concurrent.locks.ReentrantLock

/**
 * A thin JDBC/SQLite implementation of the Android `SQLiteDatabase` methods HexNC's DAOs call.
 *
 * The point of this shim is that the files under `data/db` are byte-identical to the Android sources
 * apart from their imports, so the two builds cannot drift in SQL semantics.
 *
 * Two deliberate differences from Android:
 *  - every statement is serialised on one lock, because a `java.sql.Connection` is not thread-safe
 *    and this app writes from both the UI scope and `Dispatchers.IO`;
 *  - [beginTransaction] takes that lock and [endTransaction] releases it, so a transaction really is
 *    atomic against every other thread instead of only against the calling coroutine.
 */
class SQLiteDatabase internal constructor(private val connection: Connection) : AutoCloseable {
    private val lock = ReentrantLock()
    private var depth = 0
    private var successful = false

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    fun beginTransaction() {
        lock.lock()
        if (depth == 0) {
            connection.autoCommit = false
            successful = false
        }
        depth++
    }

    fun setTransactionSuccessful() {
        successful = true
    }

    fun endTransaction() {
        try {
            if (depth > 0) depth--
            if (depth == 0) {
                if (successful) connection.commit() else connection.rollback()
                connection.autoCommit = true
            }
        } finally {
            lock.unlock()
        }
    }

    fun setForeignKeyConstraintsEnabled(enabled: Boolean) = locked {
        connection.createStatement().use { it.execute("PRAGMA foreign_keys = ${if (enabled) "ON" else "OFF"}") }
    }

    internal fun userVersion(): Int = locked {
        connection.createStatement().use { st ->
            st.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    internal fun setUserVersion(version: Int) = locked {
        connection.createStatement().use { it.execute("PRAGMA user_version = $version") }
    }

    internal fun lastInsertRowId(): Long = locked {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").use { rs -> if (rs.next()) rs.getLong(1) else -1L }
        }
    }

    fun rawQuery(sql: String, args: Array<out Any?>?): Cursor = locked {
        prepared(sql) { ps ->
            bindAll(ps, args)
            ps.executeQuery().use { rs -> materialise(rs) }
        }
    }

    fun execSQL(sql: String) = execSQL(sql, null)

    fun execSQL(sql: String, args: Array<out Any?>?) = locked {
        prepared(sql) { ps ->
            bindAll(ps, args)
            ps.executeUpdate()
        }
        Unit
    }

    fun insert(table: String, nullColumnHack: String?, values: ContentValues): Long = locked {
        val entries = values.entries()
        if (entries.isEmpty()) return@locked -1L
        val columns = entries.joinToString(", ") { it.first }
        val marks = entries.joinToString(", ") { "?" }
        prepared("INSERT INTO $table ($columns) VALUES ($marks)") { ps ->
            entries.forEachIndexed { i, (_, value) -> bind(ps, i + 1, value) }
            ps.executeUpdate().toLong()
        }
    }

    fun insertWithOnConflict(
        table: String,
        nullColumnHack: String?,
        values: ContentValues,
        conflictAlgorithm: Int,
    ): Long = locked {
        val conflict = when (conflictAlgorithm) {
            CONFLICT_REPLACE -> "OR REPLACE "
            CONFLICT_IGNORE -> "OR IGNORE "
            CONFLICT_FAIL -> "OR FAIL "
            CONFLICT_ROLLBACK -> "OR ROLLBACK "
            else -> "OR ABORT "
        }
        val entries = values.entries()
        if (entries.isEmpty()) return@locked -1L
        val columns = entries.joinToString(", ") { it.first }
        val marks = entries.joinToString(", ") { "?" }
        try {
            prepared("INSERT $conflict INTO $table ($columns) VALUES ($marks)") { ps ->
                entries.forEachIndexed { i, (_, value) -> bind(ps, i + 1, value) }
                ps.executeUpdate().toLong()
            }
        } catch (e: java.sql.SQLException) {
            -1L
        }
    }

    fun update(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<out Any?>?): Int = locked {
        val entries = values.entries()
        if (entries.isEmpty()) return@locked 0
        val assignments = entries.joinToString(", ") { "${it.first} = ?" }
        val where = if (whereClause.isNullOrBlank()) "" else " WHERE $whereClause"
        prepared("UPDATE $table SET $assignments$where") { ps ->
            entries.forEachIndexed { i, (_, value) -> bind(ps, i + 1, value) }
            bindAll(ps, whereArgs, entries.size)
            ps.executeUpdate()
        }
    }

    fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int = locked {
        val where = if (whereClause.isNullOrBlank()) "" else " WHERE $whereClause"
        prepared("DELETE FROM $table$where") { ps ->
            bindAll(ps, whereArgs)
            ps.executeUpdate()
        }
    }

    fun compileStatement(sql: String): SQLiteStatement = SQLiteStatement(this, sql)

    private fun <T> prepared(sql: String, block: (PreparedStatement) -> T): T =
        connection.prepareStatement(sql).use(block)

    internal fun <T> runStatement(sql: String, bindings: List<Pair<Int, Any?>>, block: (PreparedStatement) -> T): T = locked {
        prepared(sql) { ps ->
            bindings.forEach { (index, value) -> bind(ps, index, value) }
            block(ps)
        }
    }

    private fun bindAll(ps: PreparedStatement, args: Array<out Any?>?, offset: Int = 0) {
        args?.forEachIndexed { i, value -> bind(ps, offset + i + 1, value) }
    }

    private fun bind(ps: PreparedStatement, index: Int, value: Any?) {
        when (value) {
            null -> ps.setNull(index, Types.NULL)
            is String -> ps.setString(index, value)
            is Long -> ps.setLong(index, value)
            is Int -> ps.setInt(index, value)
            is Short -> ps.setInt(index, value.toInt())
            is Double -> ps.setDouble(index, value)
            is Float -> ps.setDouble(index, value.toDouble())
            is Boolean -> ps.setInt(index, if (value) 1 else 0)
            is ByteArray -> ps.setBytes(index, value)
            else -> ps.setObject(index, value)
        }
    }

    private fun materialise(rs: ResultSet): Cursor {
        val meta = rs.metaData
        val columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
        val rows = ArrayList<Array<Any?>>()
        while (rs.next()) {
            rows += Array(columns.size) { i -> rs.getObject(i + 1) }
        }
        return JdbcCursor(columns, rows)
    }

    override fun close() {
        locked { if (!connection.isClosed) connection.close() }
    }

    companion object {
        const val CONFLICT_ROLLBACK = 1
        const val CONFLICT_ABORT = 2
        const val CONFLICT_FAIL = 3
        const val CONFLICT_IGNORE = 4
        const val CONFLICT_REPLACE = 5
    }
}

/** Bound statement, mirroring the `SQLiteStatement` methods the DAOs use (1-based indexes). */
class SQLiteStatement internal constructor(
    private val db: SQLiteDatabase,
    private val sql: String,
) : AutoCloseable {
    private val bindings = HashMap<Int, Any?>()

    fun bindString(index: Int, value: String) {
        bindings[index] = value
    }

    fun bindLong(index: Int, value: Long) {
        bindings[index] = value
    }

    fun bindNull(index: Int) {
        bindings[index] = null
    }

    fun clearBindings() {
        bindings.clear()
    }

    fun executeInsert(): Long = db.runStatement(sql, bindings.toList()) { ps ->
        ps.executeUpdate()
        ps.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else db.lastInsertRowId() }
    }

    fun executeUpdateDelete(): Int = db.runStatement(sql, bindings.toList()) { it.executeUpdate() }

    fun execute() {
        db.runStatement(sql, bindings.toList()) { it.execute() }
    }

    override fun close() {
        bindings.clear()
    }
}
