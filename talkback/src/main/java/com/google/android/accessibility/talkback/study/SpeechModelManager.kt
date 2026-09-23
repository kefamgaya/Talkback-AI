/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.google.android.accessibility.talkback.study

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

data class SpeechDownloadProgress(val downloadedBytes: Long, val totalBytes: Long) {
  val percent: Int = ((downloadedBytes.coerceAtMost(totalBytes) * 100L) / totalBytes).toInt()
}

sealed class SpeechDownloadResult {
  data class Success(val directory: File) : SpeechDownloadResult()
  data class Failure(val message: String) : SpeechDownloadResult()
  data object Cancelled : SpeechDownloadResult()
}

class SpeechModelManager(context: Context) {
  private val root = File(context.filesDir, "speech_models").apply { mkdirs() }
  private val cancelled = AtomicBoolean(false)

  fun directory(model: SpeechModelDescriptor): File = File(root, safeId(model.id))

  fun isInstalled(model: SpeechModelDescriptor): Boolean =
    File(directory(model), COMPLETE_MARKER).readTextOrNull() == model.fingerprint()

  fun installedVoice(language: String = ""): SpeechModelDescriptor? {
    val recommended = SpeechModelCatalog.recommendedVoice(language)
    return if (isInstalled(recommended)) recommended
    else listOf(SpeechModelCatalog.swahiliVoice, SpeechModelCatalog.englishVoice)
      .firstOrNull(::isInstalled)
  }

  fun cancel() {
    cancelled.set(true)
  }

  fun delete(model: SpeechModelDescriptor): Boolean {
    cancel()
    val target = directory(model)
    return !target.exists() || target.deleteRecursively()
  }

  fun download(
    model: SpeechModelDescriptor,
    onProgress: (SpeechDownloadProgress) -> Unit,
  ): SpeechDownloadResult {
    cancelled.set(false)
    if (root.usableSpace < model.downloadBytes + STORAGE_HEADROOM_BYTES) {
      return SpeechDownloadResult.Failure("Not enough free storage for this speech model.")
    }
    val target = directory(model)
    if (target.exists()) target.deleteRecursively()
    if (!target.mkdirs()) return SpeechDownloadResult.Failure("Could not prepare model storage.")
    var completedBytes = 0L
    return try {
      model.artifacts.forEach { artifact ->
        val destination = File(target, artifact.fileName)
        val result = downloadArtifact(artifact, destination) { artifactBytes ->
          onProgress(SpeechDownloadProgress(completedBytes + artifactBytes, model.downloadBytes))
        }
        if (!result) {
          target.deleteRecursively()
          return if (cancelled.get()) SpeechDownloadResult.Cancelled
          else SpeechDownloadResult.Failure("A speech model file failed verification.")
        }
        completedBytes += artifact.bytes
      }
      model.archiveRoot?.let { archiveRoot ->
        val archive = File(target, model.artifacts.single().fileName)
        extractArchive(archive, target)
        val extracted = File(target, archiveRoot)
        if (!extracted.isDirectory) throw IllegalStateException("Speech archive is incomplete.")
        archive.delete()
      }
      File(target, COMPLETE_MARKER).writeText(model.fingerprint())
      SpeechDownloadResult.Success(target)
    } catch (error: Exception) {
      target.deleteRecursively()
      if (cancelled.get()) SpeechDownloadResult.Cancelled
      else SpeechDownloadResult.Failure(error.message ?: "Speech model download failed.")
    }
  }

  private fun downloadArtifact(
    artifact: SpeechArtifact,
    destination: File,
    onProgress: (Long) -> Unit,
  ): Boolean {
    val partial = File(destination.parentFile, "${artifact.fileName}.part")
    val connection = openConnection(artifact.url)
    try {
      if (connection.responseCode !in 200..299) {
        throw IllegalStateException("Speech download failed with HTTP ${connection.responseCode}.")
      }
      connection.inputStream.use { input ->
        FileOutputStream(partial).use { output ->
          val buffer = ByteArray(BUFFER_BYTES)
          var downloaded = 0L
          while (true) {
            if (cancelled.get()) return false
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            downloaded += count
            onProgress(downloaded)
          }
          output.fd.sync()
        }
      }
      if (partial.length() != artifact.bytes || sha256(partial) != artifact.sha256) return false
      return partial.renameTo(destination)
    } finally {
      connection.disconnect()
    }
  }

  private fun openConnection(url: String, redirects: Int = 0): HttpURLConnection {
    if (redirects > 5) throw IllegalStateException("Too many speech download redirects.")
    val connection =
      (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 30_000
        instanceFollowRedirects = false
        setRequestProperty("Accept-Encoding", "identity")
        setRequestProperty("User-Agent", "Soma-AI-Android/0.5")
      }
    connection.connect()
    if (connection.responseCode in 300..399) {
      val location = connection.getHeaderField("Location")
        ?: throw IllegalStateException("Invalid speech download redirect.")
      val redirected = URI(url).resolve(location).toString()
      connection.disconnect()
      return openConnection(redirected, redirects + 1)
    }
    return connection
  }

  private fun extractArchive(archive: File, destination: File) {
    val destinationPath = destination.canonicalFile.toPath()
    TarArchiveInputStream(
        BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))
      )
      .use { tar ->
        while (true) {
          if (cancelled.get()) throw InterruptedException("Speech model download cancelled.")
          val entry = tar.nextEntry ?: break
          if (entry.isSymbolicLink || entry.isLink) continue
          val output = File(destination, entry.name).canonicalFile
          if (!output.toPath().startsWith(destinationPath)) {
            throw SecurityException("Unsafe path in speech model archive.")
          }
          if (entry.isDirectory) output.mkdirs()
          else {
            output.parentFile?.mkdirs()
            FileOutputStream(output).use { tar.copyTo(it, BUFFER_BYTES) }
          }
        }
      }
  }

  private fun SpeechModelDescriptor.fingerprint(): String =
    artifacts.joinToString("|") { "${it.fileName}:${it.sha256}" }

  private fun safeId(value: String): String {
    require(value.matches(Regex("[a-z0-9][a-z0-9._-]*"))) { "Unsafe speech model identifier" }
    return value
  }

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

  private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

  private companion object {
    const val BUFFER_BYTES = 128 * 1024
    const val STORAGE_HEADROOM_BYTES = 256L * 1024L * 1024L
    const val COMPLETE_MARKER = ".complete"
  }
}
