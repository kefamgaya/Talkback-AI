/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.google.android.accessibility.talkback.study

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

data class ModelDownloadProgress(
  val downloadedBytes: Long,
  val totalBytes: Long?,
) {
  val percent: Int?
    get() =
      totalBytes?.takeIf { it > 0L }?.let {
        ((downloadedBytes.coerceAtMost(it) * 100L) / it).toInt()
      }
}

sealed class ModelDownloadResult {
  data class Success(val modelFile: File, val sha256: String) : ModelDownloadResult()

  data class Failure(val message: String, val canRetry: Boolean) : ModelDownloadResult()

  data object Cancelled : ModelDownloadResult()
}

/**
 * Downloads curated model artifacts without executing repository code. Partial files are retained
 * for range-based resume and promoted to the final filename only after a complete response.
 */
class ModelArtifactManager(context: Context) {
  companion object {
    private const val BUFFER_BYTES = 128 * 1024
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val STORAGE_HEADROOM_BYTES = 256L * 1024L * 1024L
    private const val MAX_REDIRECTS = 5
  }

  private val modelRoot = File(context.filesDir, "study_models").apply { mkdirs() }
  private val cancelled = AtomicBoolean(false)

  fun modelFile(model: ModelDescriptor): File = File(modelDirectory(model), model.fileName)

  fun isInstalled(model: ModelDescriptor): Boolean {
    val file = modelFile(model)
    if (!file.isFile || file.length() <= 0L) return false
    if (model.sha256 == null) return true
    return runCatching { verificationFile(model).readText().trim() == model.sha256 }
      .getOrDefault(false)
  }

  fun cancel() {
    cancelled.set(true)
  }

  fun download(
    model: ModelDescriptor,
    accessToken: String?,
    onProgress: (ModelDownloadProgress) -> Unit,
  ): ModelDownloadResult {
    cancelled.set(false)
    if (model.source != ModelSource.HUGGING_FACE) {
      return ModelDownloadResult.Failure("This downloader currently supports Hugging Face.", false)
    }

    val destination = modelFile(model)
    val destinationDirectory = requireNotNull(destination.parentFile)
    if (!destinationDirectory.exists() && !destinationDirectory.mkdirs()) {
      return ModelDownloadResult.Failure("The model storage directory could not be created.", true)
    }
    if (destination.isFile && destination.length() > 0L) {
      val existingDigest = sha256(destination)
      if (model.sha256 == null || existingDigest.equals(model.sha256, ignoreCase = true)) {
        writeVerification(model, existingDigest)
        return ModelDownloadResult.Success(destination, existingDigest)
      }
      destination.delete()
      verificationFile(model).delete()
    }

    val partial = File(destinationDirectory, "${destination.name}.part")
    val remainingEstimate = (model.downloadBytes - partial.length()).coerceAtLeast(0L)
    if (destinationDirectory.usableSpace < remainingEstimate + STORAGE_HEADROOM_BYTES) {
      return ModelDownloadResult.Failure(
        "Not enough free storage for this model and safe working space.",
        false,
      )
    }

    return try {
      val initialOffset = partial.takeIf { it.isFile }?.length() ?: 0L
      val connection = openConnection(model.downloadUrl(), accessToken, initialOffset)
      connection.useConnection { response ->
        when (response.responseCode) {
          HttpURLConnection.HTTP_UNAUTHORIZED,
          HttpURLConnection.HTTP_FORBIDDEN ->
            return ModelDownloadResult.Failure(
              "The public model could not be accessed. Check your connection and try again.",
              true,
            )
        }
        if (response.responseCode !in 200..299) {
          return ModelDownloadResult.Failure(
            "Model download failed with HTTP ${response.responseCode}.",
            response.responseCode >= 500,
          )
        }

        val append = response.responseCode == HttpURLConnection.HTTP_PARTIAL && initialOffset > 0L
        if (!append && partial.exists()) partial.delete()
        val startingBytes = if (append) initialOffset else 0L
        val totalBytes = response.totalResponseBytes(startingBytes)
        var downloadedBytes = startingBytes
        var lastProgressTime = 0L

        response.inputStream.use { input ->
          FileOutputStream(partial, append).use { output ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
              if (cancelled.get() || Thread.currentThread().isInterrupted) {
                return ModelDownloadResult.Cancelled
              }
              val count = input.read(buffer)
              if (count < 0) break
              output.write(buffer, 0, count)
              downloadedBytes += count
              val now = System.currentTimeMillis()
              if (now - lastProgressTime >= 250L) {
                onProgress(ModelDownloadProgress(downloadedBytes, totalBytes))
                lastProgressTime = now
              }
            }
            output.fd.sync()
          }
        }

        if (totalBytes != null && partial.length() != totalBytes) {
          return ModelDownloadResult.Failure("The model download ended before it was complete.", true)
        }
        onProgress(ModelDownloadProgress(partial.length(), totalBytes ?: partial.length()))
        if (!partial.renameTo(destination)) {
          return ModelDownloadResult.Failure("The downloaded model could not be installed.", true)
        }

        val digest = sha256(destination)
        if (model.sha256 != null && !digest.equals(model.sha256, ignoreCase = true)) {
          destination.delete()
          verificationFile(model).delete()
          return ModelDownloadResult.Failure("The downloaded model failed verification.", true)
        }
        writeVerification(model, digest)
        ModelDownloadResult.Success(destination, digest)
      }
    } catch (error: Exception) {
      if (cancelled.get()) ModelDownloadResult.Cancelled
      else ModelDownloadResult.Failure(error.message ?: "Model download failed.", true)
    }
  }

  private fun modelDirectory(model: ModelDescriptor): File {
    require(model.id.matches(Regex("[a-z0-9][a-z0-9._-]*"))) { "Unsafe model identifier" }
    return File(modelRoot, model.id)
  }

  private fun verificationFile(model: ModelDescriptor): File =
    File(modelDirectory(model), "${model.fileName}.sha256")

  private fun writeVerification(model: ModelDescriptor, digest: String) {
    verificationFile(model).writeText(digest)
  }

  private fun openConnection(
    url: URL,
    accessToken: String?,
    offset: Long,
    redirectCount: Int = 0,
  ): HttpURLConnection {
    if (redirectCount > MAX_REDIRECTS) throw IOException("Too many redirects while downloading.")
    val connection =
      (url.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        requestMethod = "GET"
        setRequestProperty("Accept-Encoding", "identity")
        setRequestProperty("User-Agent", "Soma-AI-Android/0.3")
        if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
        val isHuggingFaceHost =
          url.host == "huggingface.co" || url.host.endsWith(".huggingface.co")
        if (!accessToken.isNullOrBlank() && isHuggingFaceHost) {
          setRequestProperty("Authorization", "Bearer ${accessToken.trim()}")
        }
      }
    connection.connect()

    if (connection.responseCode in 300..399) {
      val location = connection.getHeaderField("Location")
        ?: throw IOException("The model server returned an invalid redirect.")
      val redirected = url.toURI().resolve(URI(location)).toURL()
      connection.disconnect()
      return openConnection(redirected, accessToken, offset, redirectCount + 1)
    }
    return connection
  }

  private fun HttpURLConnection.totalResponseBytes(startingBytes: Long): Long? {
    val contentRange = getHeaderField("Content-Range")
    val rangeTotal = contentRange?.substringAfterLast('/')?.toLongOrNull()
    if (rangeTotal != null) return rangeTotal
    return contentLengthLong.takeIf { it >= 0L }?.plus(startingBytes)
  }

  private fun ModelDescriptor.downloadUrl(): URL {
    val repositoryParts = repositoryId.split('/')
    require(repositoryParts.size == 2) { "Invalid Hugging Face repository identifier" }
    val path =
      "/${encodePathSegment(repositoryParts[0])}/${encodePathSegment(repositoryParts[1])}" +
        "/resolve/${encodePathSegment(revision)}/${encodePathSegment(fileName)}"
    return URI("https", "huggingface.co", path, "download=true", null).toURL()
  }

  private fun encodePathSegment(value: String): String =
    value.map { character ->
      if (character.isLetterOrDigit() || character in "-._~") character.toString()
      else "%%%02X".format(character.code)
    }.joinToString("")

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
      val buffer = ByteArray(BUFFER_BYTES)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private inline fun <T> HttpURLConnection.useConnection(
    block: (HttpURLConnection) -> T
  ): T = try {
    block(this)
  } finally {
    disconnect()
  }
}
