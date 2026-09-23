/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.google.android.accessibility.talkback.study

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedReader
import java.io.InputStreamReader

data class StudyDocument(
  val uri: Uri,
  val displayName: String,
  val text: String,
  val truncated: Boolean,
)

class UnsupportedStudyDocumentException(message: String) : Exception(message)

class StudyDocumentRepository(private val contentResolver: ContentResolver) {
  companion object {
    private const val MAX_CHARACTERS = 1_000_000
  }

  fun load(uri: Uri): StudyDocument {
    val displayName = queryDisplayName(uri)
    val mimeType = contentResolver.getType(uri).orEmpty()
    if (!isSupportedTextDocument(displayName, mimeType)) {
      throw UnsupportedStudyDocumentException(
        "This first Study Mode build supports TXT, Markdown, CSV, and JSON notes. " +
          "PDF, EPUB, and DOCX parsing is coming next."
      )
    }

    val text = StringBuilder()
    var truncated = false
    contentResolver.openInputStream(uri)?.use { input ->
      BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
        val buffer = CharArray(8_192)
        while (text.length < MAX_CHARACTERS) {
          val count = reader.read(buffer, 0, minOf(buffer.size, MAX_CHARACTERS - text.length))
          if (count < 0) break
          text.append(buffer, 0, count)
        }
        truncated = reader.read() >= 0
      }
    } ?: throw IllegalStateException("The selected document could not be opened.")

    return StudyDocument(uri, displayName, text.toString(), truncated)
  }

  private fun queryDisplayName(uri: Uri): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
      cursor ->
      if (cursor.moveToFirst()) {
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0) return cursor.getString(index)
      }
    }
    return uri.lastPathSegment ?: "Study note"
  }

  private fun isSupportedTextDocument(displayName: String, mimeType: String): Boolean {
    if (mimeType.startsWith("text/")) return true
    if (mimeType == "application/json") return true
    val extension = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in setOf("txt", "md", "markdown", "csv", "json")
  }
}
