/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.google.android.accessibility.talkback.study

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs

/** Hardware facts used to select a model without sending device information off the phone. */
data class DeviceProfile(
  val totalMemoryBytes: Long,
  val availableMemoryBytes: Long,
  val freeStorageBytes: Long,
  val cpuCores: Int,
  val supportedAbis: List<String>,
  val is64Bit: Boolean,
  val hasVulkan: Boolean,
  val thermalStatus: Int?,
)

object DeviceProfiler {
  fun read(context: Context): DeviceProfile {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)

    val supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty()
    val powerManager = context.getSystemService(PowerManager::class.java)

    return DeviceProfile(
      totalMemoryBytes = memoryInfo.totalMem,
      availableMemoryBytes = memoryInfo.availMem,
      freeStorageBytes = StatFs(context.filesDir.absolutePath).availableBytes,
      cpuCores = Runtime.getRuntime().availableProcessors(),
      supportedAbis = supportedAbis,
      is64Bit = Build.SUPPORTED_64_BIT_ABIS?.isNotEmpty() == true,
      hasVulkan =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL),
      thermalStatus =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) powerManager.currentThermalStatus
        else null,
    )
  }
}
