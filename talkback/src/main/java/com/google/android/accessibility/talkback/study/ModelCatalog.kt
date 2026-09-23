/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.google.android.accessibility.talkback.study

enum class ModelFormat {
  LITERT_LM,
  GGUF,
}

enum class ModelSource {
  HUGGING_FACE,
  OLLAMA,
  LOCAL_FILE,
}

/** A curated model artifact. Arbitrary executable code is never loaded from a model repository. */
data class ModelDescriptor(
  val id: String,
  val displayName: String,
  val source: ModelSource,
  val format: ModelFormat,
  val repositoryId: String,
  val revision: String,
  val fileName: String,
  val licenseName: String,
  val downloadBytes: Long,
  val expectedWorkingSetBytes: Long,
  val minimumTotalMemoryBytes: Long,
  val minimumAvailableMemoryBytes: Long,
  val contextTokens: Int,
  val supportsVision: Boolean,
  val requiresLicenseAcceptance: Boolean,
  val sha256: String? = null,
)

object StudyModelCatalog {
  private const val MIB = 1024L * 1024L
  private const val GIB = 1024L * MIB

  /**
   * Initial, deliberately small catalog. File names and sizes are pinned so the downloader can
   * verify the selected artifact before inference is enabled.
   */
  val models =
    listOf(
      ModelDescriptor(
        id = "gemma-3-270m-q8",
        displayName = "Gemma 3 270M (fast)",
        source = ModelSource.HUGGING_FACE,
        format = ModelFormat.LITERT_LM,
        repositoryId = "litert-community/gemma-3-270m-it",
        revision = "9d2093270fb5aa49a986b49b5779d763dde7b630",
        fileName = "gemma3-270m-it-q8.litertlm",
        licenseName = "Gemma",
        downloadBytes = 304_005_120L,
        expectedWorkingSetBytes = 900L * MIB,
        minimumTotalMemoryBytes = 3L * GIB,
        minimumAvailableMemoryBytes = 700L * MIB,
        contextTokens = 2048,
        supportsVision = false,
        requiresLicenseAcceptance = true,
        sha256 = "757e9119fa5bd667a2774fb470ac4afcd3190a21c677f8e69a5d6bc908abdd63",
      ),
      ModelDescriptor(
        id = "gemma-3-1b-q4",
        displayName = "Gemma 3 1B (recommended quality)",
        source = ModelSource.HUGGING_FACE,
        format = ModelFormat.LITERT_LM,
        repositoryId = "litert-community/Gemma3-1B-IT",
        revision = "a6306a4e292016480083b73b8dc6f3f939ae04c3",
        fileName = "gemma3-1b-it-int4.litertlm",
        licenseName = "Gemma",
        downloadBytes = 584_417_280L,
        expectedWorkingSetBytes = 1700L * MIB,
        minimumTotalMemoryBytes = 6L * GIB,
        minimumAvailableMemoryBytes = 1300L * MIB,
        contextTokens = 4096,
        supportsVision = false,
        requiresLicenseAcceptance = true,
        sha256 = "1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be",
      ),
    )
}

data class ModelRecommendation(
  val model: ModelDescriptor?,
  val explanation: String,
)

object ModelRecommendationEngine {
  private const val DOWNLOAD_HEADROOM_BYTES = 512L * 1024L * 1024L

  fun recommend(
    profile: DeviceProfile,
    models: List<ModelDescriptor> = StudyModelCatalog.models,
  ): ModelRecommendation {
    if (!profile.is64Bit) {
      return ModelRecommendation(
        model = null,
        explanation = "Local AI requires a 64-bit phone. The accessible note reader still works.",
      )
    }

    val eligible =
      models.filter { model ->
        profile.totalMemoryBytes >= model.minimumTotalMemoryBytes &&
          profile.availableMemoryBytes >= model.minimumAvailableMemoryBytes &&
          profile.freeStorageBytes >= model.downloadBytes + DOWNLOAD_HEADROOM_BYTES
      }

    val selected = eligible.maxByOrNull { it.expectedWorkingSetBytes }
    if (selected == null) {
      return ModelRecommendation(
        model = null,
        explanation =
          "No model currently has enough safe memory and storage headroom. " +
            "The app will continue in passage-finder mode.",
      )
    }

    val sizeMb = selected.downloadBytes / (1024L * 1024L)
    return ModelRecommendation(
      model = selected,
      explanation =
        "Recommended: ${selected.displayName}. Download size: $sizeMb MB. " +
          "A device benchmark will run after download before the model is enabled.",
    )
  }
}
