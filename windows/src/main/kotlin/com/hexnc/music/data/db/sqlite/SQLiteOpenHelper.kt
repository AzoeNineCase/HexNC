package com.hexnc.music.data.db.sqlite

import java.io.File
import java.sql.DriverManager

/**
 * Opens one SQLite file through JDBC and runs the Android-style create/upgrade callbacks.
 *
 * Schema versioning uses SQLite's own `PRAGMA user_version`, exactly like Android's
 * `SQLiteOpenHelper`, so `HexNCDatabase` needs no changes beyond its constructor and imports.
 */
abstract class SQLiteOpenHelper(
    private val file: File,
    private val name: String,
    private val version: Int,
) : AutoCloseable {
    @Volatile
    private var database: SQLiteDatabase? = null

    val readableDatabase: SQLiteDatabase get() = open()
    val writableDatabase: SQLiteDatabase get() = open()

    private fun open(): SQLiteDatabase {
        database?.let { return it }
        synchronized(this) {
            database?.let { return it }
            file.parentFile?.mkdirs()
            val connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
            val db = SQLiteDatabase(connection)
            val current = db.userVersion()
            // Android calls onConfigure on every open, before create/upgrade; foreign keys are
            // per-connection in SQLite, so it must run before any DDL or DML.
            onConfigure(db)
            when {
                current == 0 -> {
                    db.beginTransaction()
                    try {
                        onCreate(db)
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                    db.setUserVersion(version)
                }

                current < version -> {
                    db.beginTransaction()
                    try {
                        onUpgrade(db, current, version)
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                    db.setUserVersion(version)
                }

                current > version -> {
                    db.beginTransaction()
                    try {
                        onDowngrade(db, current, version)
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                    db.setUserVersion(version)
                }
            }
            database = db
            return db
        }
    }

    open fun onConfigure(db: SQLiteDatabase) = Unit

    abstract fun onCreate(db: SQLiteDatabase)

    abstract fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int)

    open fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun close() {
        synchronized(this) {
            database?.close()
            database = null
        }
    }

    @Suppress("unused")
    val databaseName: String get() = name
}
