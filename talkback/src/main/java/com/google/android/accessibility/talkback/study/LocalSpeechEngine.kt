/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.google.android.accessibility.talkback.study

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

data class SynthesizedSpeech(val samples: FloatArray, val sampleRate: Int)

/** Thin, short-lived wrapper around sherpa-onnx. All model input stays on the device. */
class LocalSpeechEngine(private val models: SpeechModelManager) {
  fun transcribe(samples: FloatArray, sampleRate: Int, language: String): String {
    val model = SpeechModelCatalog.whisperTiny
    check(models.isInstalled(model)) { "Offline dictation is not installed." }
    val directory = models.directory(model)
    val recognizer =
      OfflineRecognizer(
        config =
          OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
            modelConfig =
              OfflineModelConfig(
                whisper =
                  OfflineWhisperModelConfig(
                    encoder = File(directory, "tiny-encoder.int8.onnx").absolutePath,
                    decoder = File(directory, "tiny-decoder.int8.onnx").absolutePath,
                    language = language.ifBlank { "en" },
                    task = "transcribe",
                  ),
                numThreads = recommendedThreads(),
                provider = "cpu",
                modelType = "whisper",
                tokens = File(directory, "tiny-tokens.txt").absolutePath,
              ),
          )
      )
    return try {
      val stream = recognizer.createStream()
      try {
        stream.acceptWaveform(samples, sampleRate)
        recognizer.decode(stream)
        recognizer.getResult(stream).text.trim()
      } finally {
        stream.release()
      }
    } finally {
      recognizer.release()
    }
  }

  fun synthesize(text: String, model: SpeechModelDescriptor): SynthesizedSpeech {
    var result: SynthesizedSpeech? = null
    synthesizeAll(listOf(text), model) { result = it }
    return requireNotNull(result)
  }

  fun synthesizeAll(
    chunks: List<String>,
    model: SpeechModelDescriptor,
    onAudio: (SynthesizedSpeech) -> Unit,
  ) {
    check(models.isInstalled(model)) { "Offline reading voice is not installed." }
    val root = File(models.directory(model), requireNotNull(model.archiveRoot))
    val onnx =
      root.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".onnx") }
        ?: error("Voice model is incomplete.")
    val tts =
      OfflineTts(
        config =
          OfflineTtsConfig(
            model =
              OfflineTtsModelConfig(
                vits =
                  OfflineTtsVitsModelConfig(
                    model = onnx.absolutePath,
                    tokens = File(root, "tokens.txt").absolutePath,
                    dataDir = File(root, "espeak-ng-data").absolutePath,
                  ),
                numThreads = recommendedThreads(),
                provider = "cpu",
              ),
          )
      )
    try {
      chunks.forEach { text ->
        val audio = tts.generate(text)
        onAudio(SynthesizedSpeech(audio.samples, audio.sampleRate))
      }
    } finally {
      tts.release()
    }
  }

  private fun recommendedThreads(): Int =
    Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
}
