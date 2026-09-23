/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.google.android.accessibility.talkback.study

data class RetrievedPassage(val text: String, val score: Int)

/** Lightweight offline retrieval used now and as grounded context for the local LLM later. */
object NoteRetriever {
  private const val MAX_PASSAGE_CHARACTERS = 1_200
  private val wordBoundary = Regex("[^\\p{L}\\p{N}]+")
  private val paragraphBoundary = Regex("\\n\\s*\\n+")
  private val ignoredWords =
    setOf(
      "about",
      "and",
      "are",
      "does",
      "for",
      "from",
      "how",
      "into",
      "the",
      "this",
      "what",
      "when",
      "where",
      "which",
      "with",
    )

  fun find(question: String, documentText: String, limit: Int = 3): List<RetrievedPassage> {
    val queryTerms = terms(question)
    if (queryTerms.isEmpty() || documentText.isBlank()) return emptyList()

    return candidatePassages(documentText)
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .map { paragraph ->
        val paragraphTerms = terms(paragraph)
        val score = queryTerms.sumOf { term -> paragraphTerms.count { it == term } }
        RetrievedPassage(paragraph.take(MAX_PASSAGE_CHARACTERS), score)
      }
      .filter { it.score > 0 }
      .sortedByDescending { it.score }
      .take(limit)
      .toList()
  }

  private fun candidatePassages(documentText: String): Sequence<String> =
    documentText.split(paragraphBoundary).asSequence().flatMap { paragraph ->
      if (paragraph.length <= MAX_PASSAGE_CHARACTERS) sequenceOf(paragraph)
      else paragraph.chunked(MAX_PASSAGE_CHARACTERS).asSequence()
    }

  private fun terms(value: String): List<String> =
    value
      .lowercase()
      .split(wordBoundary)
      .filter { it.length > 2 && it !in ignoredWords }
}
