/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.google.android.accessibility.talkback.study

enum class SpeechModelKind {
  RECOGNITION,
  VOICE,
}

data class SpeechArtifact(
  val fileName: String,
  val url: String,
  val bytes: Long,
  val sha256: String,
)

data class SpeechModelDescriptor(
  val id: String,
  val displayName: String,
  val kind: SpeechModelKind,
  val languageTag: String,
  val licenseName: String,
  val minimumTotalMemoryBytes: Long,
  val expectedWorkingSetBytes: Long,
  val artifacts: List<SpeechArtifact>,
  val archiveRoot: String? = null,
) {
  val downloadBytes: Long = artifacts.sumOf { it.bytes }
}

object SpeechModelCatalog {
  private const val MIB = 1024L * 1024L
  private const val GIB = 1024L * MIB
  private const val WHISPER_REPOSITORY =
    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main"
  private const val TTS_RELEASE =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

  val whisperTiny =
    SpeechModelDescriptor(
      id = "whisper-tiny-multilingual-int8",
      displayName = "Whisper Tiny multilingual",
      kind = SpeechModelKind.RECOGNITION,
      languageTag = "mul",
      licenseName = "MIT",
      minimumTotalMemoryBytes = 3L * GIB,
      expectedWorkingSetBytes = 500L * MIB,
      artifacts =
        listOf(
          SpeechArtifact(
            fileName = "tiny-encoder.int8.onnx",
            url = "$WHISPER_REPOSITORY/tiny-encoder.int8.onnx?download=true",
            bytes = 12_937_772L,
            sha256 = "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434",
          ),
          SpeechArtifact(
            fileName = "tiny-decoder.int8.onnx",
            url = "$WHISPER_REPOSITORY/tiny-decoder.int8.onnx?download=true",
            bytes = 89_855_401L,
            sha256 = "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925",
          ),
          SpeechArtifact(
            fileName = "tiny-tokens.txt",
            url = "$WHISPER_REPOSITORY/tiny-tokens.txt?download=true",
            bytes = 816_730L,
            sha256 = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
          ),
        ),
    )

  val englishVoice =
    piperVoice(
      id = "piper-amy-english-int8",
      displayName = "Amy — English",
      languageTag = "en-US",
      archiveName = "vits-piper-en_US-amy-low-int8.tar.bz2",
      archiveBytes = 21_099_246L,
      sha256 = "93070ac9fadf512e56c46bdd0c5d2ce96b424fdc4e683d560167410bd2c4df7d",
      archiveRoot = "vits-piper-en_US-amy-low-int8",
    )

  val swahiliVoice =
    piperVoice(
      id = "piper-lanfrica-swahili-int8",
      displayName = "Lanfrica — Kiswahili",
      languageTag = "sw-CD",
      archiveName = "vits-piper-sw_CD-lanfrica-medium-int8.tar.bz2",
      archiveBytes = 21_082_704L,
      sha256 = "eee0eda5a850e21169ebd79105a914d95d4aa34cccc49b417f071f05a0e2fa45",
      archiveRoot = "vits-piper-sw_CD-lanfrica-medium-int8",
    )

  val models = listOf(whisperTiny, englishVoice, swahiliVoice)

  fun recommendedVoice(language: String): SpeechModelDescriptor =
    if (language.equals("sw", ignoreCase = true)) swahiliVoice else englishVoice

  private fun piperVoice(
    id: String,
    displayName: String,
    languageTag: String,
    archiveName: String,
    archiveBytes: Long,
    sha256: String,
    archiveRoot: String,
  ) =
    SpeechModelDescriptor(
      id = id,
      displayName = displayName,
      kind = SpeechModelKind.VOICE,
      languageTag = languageTag,
      licenseName = "MIT",
      minimumTotalMemoryBytes = 2L * GIB,
      expectedWorkingSetBytes = 300L * MIB,
      artifacts =
        listOf(
          SpeechArtifact(
            fileName = archiveName,
            url = "$TTS_RELEASE/$archiveName",
            bytes = archiveBytes,
            sha256 = sha256,
          )
        ),
      archiveRoot = archiveRoot,
    )
}
