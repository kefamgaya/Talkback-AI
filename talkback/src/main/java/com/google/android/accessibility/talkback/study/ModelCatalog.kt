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
   * Deliberately small catalog of public, ungated Apache 2.0 artifacts. Revisions, file sizes, and
   * hashes are pinned so a repository update cannot silently replace a downloaded model.
   */
  val models =
    listOf(
      ModelDescriptor(
        id = "smollm2-360m-instruct-q8",
        displayName = "SmolLM2 360M (fast)",
        source = ModelSource.HUGGING_FACE,
        format = ModelFormat.GGUF,
        repositoryId = "HuggingFaceTB/SmolLM2-360M-Instruct-GGUF",
        revision = "593b5a2e04c8f3e4ee880263f93e0bd2901ad47f",
        fileName = "smollm2-360m-instruct-q8_0.gguf",
        licenseName = "Apache-2.0",
        downloadBytes = 386_404_992L,
        expectedWorkingSetBytes = 850L * MIB,
        minimumTotalMemoryBytes = 3L * GIB,
        minimumAvailableMemoryBytes = 750L * MIB,
        contextTokens = 2048,
        supportsVision = false,
        requiresLicenseAcceptance = false,
        sha256 = "48ab3034d0dd401fbc721eb1df3217902fee7dab9078992d66431f09b7750201",
      ),
      ModelDescriptor(
        id = "qwen2.5-0.5b-instruct-q4",
        displayName = "Qwen 2.5 0.5B (multilingual)",
        source = ModelSource.HUGGING_FACE,
        format = ModelFormat.GGUF,
        repositoryId = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
        revision = "9217f5db79a29953eb74d5343926648285ec7e67",
        fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        licenseName = "Apache-2.0",
        downloadBytes = 491_400_032L,
        expectedWorkingSetBytes = 1100L * MIB,
        minimumTotalMemoryBytes = 4L * GIB,
        minimumAvailableMemoryBytes = 950L * MIB,
        contextTokens = 4096,
        supportsVision = false,
        requiresLicenseAcceptance = false,
        sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
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
          "It is public, Apache 2.0 licensed, and needs no account or access token.",
    )
  }
}
