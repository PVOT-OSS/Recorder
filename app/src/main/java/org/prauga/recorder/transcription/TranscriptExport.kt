/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.transcription

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import org.prauga.recorder.data.Segment

object TranscriptExport {
    enum class Format(val extension: String, val mimeType: String) {
        TEXT("txt", "text/plain"),
        SRT("srt", "application/x-subrip"),
        VTT("vtt", "text/vtt"),
    }

    fun render(segments: List<Segment>, format: Format, speakerName: (Int) -> String?): String =
        when (format) {
            Format.TEXT ->
                segments.joinToString("\n\n") { s ->
                    listOfNotNull(speakerName(s.speaker), clock(s.startMs, false))
                        .joinToString(" · ") + "\n" + s.text
                }
            Format.SRT ->
                segments
                    .mapIndexed { i, s ->
                        "${i + 1}\n${stamp(s.startMs, ',')} --> ${stamp(s.endMs, ',')}\n" +
                            line(s, speakerName)
                    }
                    .joinToString("\n\n") + "\n"
            Format.VTT ->
                "WEBVTT\n\n" +
                    segments.joinToString("\n\n") { s ->
                        "${stamp(s.startMs, '.')} --> ${stamp(s.endMs, '.')}\n" +
                            line(s, speakerName)
                    } +
                    "\n"
        }

    private fun line(s: Segment, speakerName: (Int) -> String?) =
        speakerName(s.speaker)?.let { "$it: ${s.text}" } ?: s.text

    private fun stamp(ms: Long, separator: Char) =
        String.format(
            Locale.ROOT,
            "%02d:%02d:%02d%c%03d",
            ms / 3_600_000,
            ms / 60_000 % 60,
            ms / 1000 % 60,
            separator,
            ms % 1000,
        )

    private fun clock(ms: Long, hours: Boolean) =
        if (hours || ms >= 3_600_000) stamp(ms, '.').substringBefore('.')
        else String.format(Locale.ROOT, "%02d:%02d", ms / 60_000, ms / 1000 % 60)

    /** A share sheet for the transcript as a file (plus the text itself for plain text). */
    fun shareIntent(
        context: Context,
        title: String,
        segments: List<Segment>,
        format: Format,
        speakerName: (Int) -> String?,
    ): Intent {
        val content = render(segments, format, speakerName)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "${title.replace('/', '_')}.${format.extension}")
        file.writeText(content)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".exports", file)
        val send =
            Intent(Intent.ACTION_SEND)
                .setType(format.mimeType)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        if (format == Format.TEXT) send.putExtra(Intent.EXTRA_TEXT, content)
        return Intent.createChooser(send, null)
    }
}
