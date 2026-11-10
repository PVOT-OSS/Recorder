/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class RecordingsDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, NAME, null, VERSION) {
    private val _changes = MutableStateFlow(0L)

    /** Bumped on every write so observers can re-query. */
    val changes = _changes.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE recordings (
                id INTEGER PRIMARY KEY,
                favourite INTEGER NOT NULL DEFAULT 0,
                labels TEXT NOT NULL DEFAULT '',
                location_name TEXT,
                latitude REAL,
                longitude REAL,
                quality TEXT
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE segments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                recording_id INTEGER NOT NULL,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                text TEXT NOT NULL,
                words TEXT NOT NULL DEFAULT '',
                speaker INTEGER NOT NULL DEFAULT -1
            )
            """
        )
        db.execSQL("CREATE INDEX segments_recording ON segments(recording_id, start_ms)")
        db.execSQL(
            """
            CREATE TABLE speakers (
                recording_id INTEGER NOT NULL,
                speaker INTEGER NOT NULL,
                name TEXT NOT NULL,
                PRIMARY KEY (recording_id, speaker)
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE tags (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                recording_id INTEGER NOT NULL,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                label TEXT NOT NULL,
                score REAL NOT NULL
            )
            """
        )
        db.execSQL("CREATE INDEX tags_recording ON tags(recording_id, start_ms)")
        db.execSQL(
            "CREATE VIRTUAL TABLE search USING fts4(title, location, labels, transcript, tags)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun notifyChanged() = _changes.update { it + 1 }

    private inline fun <T> write(block: SQLiteDatabase.() -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            return db.block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
            notifyChanged()
        }
    }

    fun metadata(): Map<Long, RecordingMetadata> =
        readableDatabase
            .rawQuery(
                "SELECT id, favourite, labels, location_name, latitude, longitude, quality " +
                    "FROM recordings",
                null,
            )
            .use { c ->
                buildMap {
                    while (c.moveToNext()) {
                        val m = c.toMetadata()
                        put(m.id, m)
                    }
                }
            }

    fun metadata(id: Long): RecordingMetadata =
        readableDatabase
            .rawQuery(
                "SELECT id, favourite, labels, location_name, latitude, longitude, quality " +
                    "FROM recordings WHERE id = ?",
                arrayOf(id.toString()),
            )
            .use { c -> if (c.moveToFirst()) c.toMetadata() else RecordingMetadata(id) }

    fun upsert(metadata: RecordingMetadata) = write {
        insertWithOnConflict(
            "recordings",
            null,
            ContentValues().apply {
                put("id", metadata.id)
                put("favourite", if (metadata.favourite) 1 else 0)
                put("labels", metadata.labels.joinToString(LABEL_SEPARATOR))
                put("location_name", metadata.locationName)
                put("latitude", metadata.latitude)
                put("longitude", metadata.longitude)
                put("quality", metadata.quality)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun segments(id: Long): List<Segment> =
        readableDatabase
            .rawQuery(
                "SELECT id, start_ms, end_ms, text, words, speaker FROM segments " +
                    "WHERE recording_id = ? ORDER BY start_ms",
                arrayOf(id.toString()),
            )
            .use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            Segment(
                                id = c.getLong(0),
                                startMs = c.getLong(1),
                                endMs = c.getLong(2),
                                text = c.getString(3),
                                words = Word.decode(c.getString(4)),
                                speaker = c.getInt(5),
                            )
                        )
                    }
                }
            }

    fun replaceSegments(id: Long, segments: List<Segment>) = write {
        delete("segments", "recording_id = ?", arrayOf(id.toString()))
        for (s in segments) {
            insert("segments", null, s.toValues(id))
        }
    }

    fun updateSegment(id: Long, segment: Segment) = write {
        update("segments", segment.toValues(id), "id = ?", arrayOf(segment.id.toString()))
    }

    fun speakers(id: Long): Map<Int, String> =
        readableDatabase
            .rawQuery(
                "SELECT speaker, name FROM speakers WHERE recording_id = ?",
                arrayOf(id.toString()),
            )
            .use { c -> buildMap { while (c.moveToNext()) put(c.getInt(0), c.getString(1)) } }

    fun renameSpeaker(id: Long, speaker: Int, name: String) = write {
        insertWithOnConflict(
            "speakers",
            null,
            ContentValues().apply {
                put("recording_id", id)
                put("speaker", speaker)
                put("name", name)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun tags(id: Long): List<Tag> =
        readableDatabase
            .rawQuery(
                "SELECT start_ms, end_ms, label, score FROM tags WHERE recording_id = ? " +
                    "ORDER BY start_ms",
                arrayOf(id.toString()),
            )
            .use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(Tag(c.getLong(0), c.getLong(1), c.getString(2), c.getFloat(3)))
                    }
                }
            }

    fun replaceTags(id: Long, tags: List<Tag>) = write {
        delete("tags", "recording_id = ?", arrayOf(id.toString()))
        for (t in tags) {
            insert(
                "tags",
                null,
                ContentValues().apply {
                    put("recording_id", id)
                    put("start_ms", t.startMs)
                    put("end_ms", t.endMs)
                    put("label", t.label)
                    put("score", t.score)
                },
            )
        }
    }

    /** Rebuilds the full-text row of [id]; [title] comes from MediaStore. */
    fun reindex(id: Long, title: String) {
        val m = metadata(id)
        val transcript = segments(id).joinToString(" ") { it.text }
        val tags = tags(id).map { it.label }.distinct().joinToString(" ")
        write {
            delete("search", "docid = ?", arrayOf(id.toString()))
            insert(
                "search",
                null,
                ContentValues().apply {
                    put("docid", id)
                    put("title", title)
                    put("location", m.locationName ?: "")
                    put("labels", m.labels.joinToString(" "))
                    put("transcript", transcript)
                    put("tags", tags)
                },
            )
        }
    }

    /** Ids matching [query] (prefix match on every term), best first. */
    fun search(query: String): Set<Long> {
        val terms =
            query
                .split(Regex("\\s+"))
                .map { it.replace(Regex("[^\\p{L}\\p{N}]"), "") }
                .filter { it.isNotEmpty() }
        if (terms.isEmpty()) {
            return emptySet()
        }
        val match = terms.joinToString(" ") { "$it*" }
        return readableDatabase
            .rawQuery("SELECT docid FROM search WHERE search MATCH ?", arrayOf(match))
            .use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }
    }

    /** The first transcript line of [id] containing [term], cut down to the words around it. */
    fun snippet(id: Long, term: String): String? {
        val text =
            readableDatabase
                .rawQuery(
                    "SELECT text FROM segments WHERE recording_id = ? AND text LIKE ? " +
                        "ORDER BY start_ms LIMIT 1",
                    arrayOf(id.toString(), "%$term%"),
                )
                .use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return null
        val at = text.indexOf(term, ignoreCase = true).coerceAtLeast(0)
        val from =
            text.lastIndexOf(' ', (at - SNIPPET_CONTEXT).coerceAtLeast(0)).let {
                if (at - SNIPPET_CONTEXT <= 0 || it < 0) 0 else it + 1
            }
        val to =
            text.indexOf(' ', (at + term.length + SNIPPET_CONTEXT).coerceAtMost(text.length)).let {
                if (it < 0) text.length else it
            }
        return (if (from > 0) "…" else "") +
            text.substring(from, to) +
            (if (to < text.length) "…" else "")
    }

    /** Drops rows whose MediaStore entry is gone. */
    fun retainOnly(ids: Set<Long>) {
        val known = metadata().keys + indexedIds()
        val stale = known - ids
        if (stale.isEmpty()) {
            return
        }
        write {
            for (id in stale) {
                val args = arrayOf(id.toString())
                delete("recordings", "id = ?", args)
                delete("segments", "recording_id = ?", args)
                delete("speakers", "recording_id = ?", args)
                delete("tags", "recording_id = ?", args)
                delete("search", "docid = ?", args)
            }
        }
    }

    fun indexedIds(): Set<Long> =
        readableDatabase.rawQuery("SELECT docid FROM search", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }

    fun delete(id: Long) = retainOnly(metadata().keys + indexedIds() - id)

    private fun Cursor.toMetadata() =
        RecordingMetadata(
            id = getLong(0),
            favourite = getInt(1) != 0,
            labels = getString(2).split(LABEL_SEPARATOR).filter { it.isNotBlank() },
            locationName = getString(3),
            latitude = if (isNull(4)) null else getDouble(4),
            longitude = if (isNull(5)) null else getDouble(5),
            quality = getString(6),
        )

    private fun Segment.toValues(id: Long) =
        ContentValues().apply {
            put("recording_id", id)
            put("start_ms", startMs)
            put("end_ms", endMs)
            put("text", text)
            put("words", Word.encode(words))
            put("speaker", speaker)
        }

    companion object {
        private const val NAME = "recordings.db"
        private const val VERSION = 1
        private const val LABEL_SEPARATOR = "\u001f"
        private const val SNIPPET_CONTEXT = 40

        @Volatile private var instance: RecordingsDatabase? = null

        fun get(context: Context) =
            instance
                ?: synchronized(this) {
                    instance
                        ?: RecordingsDatabase(context.applicationContext).also { instance = it }
                }
    }
}
