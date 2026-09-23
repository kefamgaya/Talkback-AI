/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.google.android.accessibility.talkback.study

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.google.android.accessibility.talkback.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.Locale
import java.util.concurrent.Executors

/** Accessible note reader and entry point for the private, on-device Study Assistant. */
class StudyModeActivity : AppCompatActivity() {
  private val documentWorker = Executors.newSingleThreadExecutor()
  private val downloadWorker = Executors.newSingleThreadExecutor()
  private lateinit var repository: StudyDocumentRepository
  private lateinit var modelArtifacts: ModelArtifactManager
  private lateinit var documentTitle: TextView
  private lateinit var documentBody: TextView
  private lateinit var status: TextView
  private lateinit var question: EditText
  private lateinit var answer: TextView
  private lateinit var askButton: Button
  private lateinit var voiceButton: Button
  private lateinit var readButton: Button
  private lateinit var modelStatus: TextView
  private lateinit var pageTitle: TextView
  private lateinit var pageSubtitle: TextView
  private lateinit var bottomNavigation: BottomNavigationView
  private var currentDocument: StudyDocument? = null
  private var currentPage = AppPage.STUDY
  private var speechRecognizer: SpeechRecognizer? = null
  private var textToSpeech: TextToSpeech? = null
  private var offlineVoiceReady = false
  private val preferences by lazy { getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE) }

  private val openDocument =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        retainReadPermission(uri)
        loadDocument(uri)
      }
    }

  private val requestMicrophone =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      refreshSettingsStatus()
      if (granted) startVoiceRecognition()
      else showStatus(R.string.study_voice_permission_denied)
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    configureEdgeToEdge()
    setContentView(R.layout.activity_study_mode)
    repository = StudyDocumentRepository(contentResolver)
    modelArtifacts = ModelArtifactManager(this)

    documentTitle = findViewById(R.id.study_document_title)
    documentBody = findViewById(R.id.study_document_body)
    status = findViewById(R.id.study_status)
    question = findViewById(R.id.study_question)
    answer = findViewById(R.id.study_answer)
    askButton = findViewById(R.id.study_ask_button)
    voiceButton = findViewById(R.id.study_voice_question_button)
    readButton = findViewById(R.id.study_read_note_button)
    modelStatus = findViewById(R.id.model_status)
    pageTitle = findViewById(R.id.soma_page_title)
    pageSubtitle = findViewById(R.id.soma_page_subtitle)
    bottomNavigation = findViewById(R.id.soma_bottom_navigation)

    findViewById<Button>(R.id.study_open_note_button).setOnClickListener {
      openDocument.launch(
        arrayOf("text/plain", "text/markdown", "text/csv", "application/json")
      )
    }
    askButton.setOnClickListener { findRelevantPassage() }
    voiceButton.setOnClickListener { beginVoiceQuestion() }
    readButton.setOnClickListener { toggleReading() }

    applySafeAreas()
    setupNavigation()
    setupSettings()
    initializeOfflineReadingVoice()
    showHardwareRecommendation()
    handleIncomingDocument(intent)
  }

  override fun onResume() {
    super.onResume()
    refreshSettingsStatus()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIncomingDocument(intent)
  }

  override fun onDestroy() {
    speechRecognizer?.destroy()
    textToSpeech?.stop()
    textToSpeech?.shutdown()
    modelArtifacts.cancel()
    documentWorker.shutdownNow()
    downloadWorker.shutdownNow()
    super.onDestroy()
  }

  private fun handleIncomingDocument(intent: Intent?) {
    val uri = intent?.data ?: return
    if (intent.action == Intent.ACTION_VIEW) {
      bottomNavigation.selectedItemId = R.id.navigation_study
      loadDocument(uri)
    }
  }

  private fun loadDocument(uri: Uri) {
    setLoading(true)
    currentDocument = null
    askButton.isEnabled = false
    voiceButton.isEnabled = false
    readButton.isEnabled = false
    readButton.visibility = View.GONE
    stopReading()
    question.isEnabled = false
    question.text.clear()
    documentTitle.setText(R.string.study_loading_note_title)
    documentBody.visibility = View.GONE
    answer.visibility = View.GONE
    status.setText(R.string.study_loading_note)
    documentWorker.execute {
      runCatching { repository.load(uri) }
        .onSuccess { document -> runOnUiThread { showDocument(document) } }
        .onFailure { error ->
          runOnUiThread {
            setLoading(false)
            documentTitle.setText(R.string.study_empty_note_title)
            status.text = error.message ?: getString(R.string.study_open_note_error)
            status.announceForAccessibility(status.text)
          }
        }
    }
  }

  private fun showDocument(document: StudyDocument) {
    currentDocument = document
    documentTitle.text = document.displayName
    documentBody.text = document.text
    documentBody.visibility = View.VISIBLE
    question.isEnabled = true
    askButton.isEnabled = true
    voiceButton.isEnabled = true
    readButton.isEnabled = true
    readButton.visibility = View.VISIBLE
    setLoading(false)
    status.text =
      if (document.truncated) getString(R.string.study_note_truncated)
      else getString(R.string.study_note_ready)
    documentTitle.announceForAccessibility(
      getString(R.string.study_note_opened_announcement, document.displayName)
    )
  }

  private fun findRelevantPassage() {
    val document = currentDocument ?: return
    val prompt = question.text.toString().trim()
    if (prompt.isEmpty()) {
      question.error = getString(R.string.study_question_required)
      question.requestFocus()
      return
    }

    askButton.isEnabled = false
    status.setText(R.string.study_searching_note)
    documentWorker.execute {
      val passages = NoteRetriever.find(prompt, document.text)
      runOnUiThread {
        answer.text =
          if (passages.isEmpty()) {
            getString(R.string.study_no_relevant_passage)
          } else {
            passages.joinToString(separator = "\n\n") { it.text } +
              "\n\n" +
              getString(R.string.study_source_citation, document.displayName)
          }
        answer.visibility = View.VISIBLE
        askButton.isEnabled = true
        status.setText(R.string.study_note_ready)
        answer.announceForAccessibility(answer.text)
        if (preferences.getBoolean(KEY_READ_ANSWERS, false)) readAnswerAloud(answer.text.toString())
      }
    }
  }

  private fun showHardwareRecommendation() {
    val recommendation = ModelRecommendationEngine.recommend(DeviceProfiler.read(this))
    findViewById<TextView>(R.id.study_model_recommendation).text = recommendation.explanation
    configureModelControls(recommendation.model)
  }

  private fun configureModelControls(model: ModelDescriptor?) {
    val reviewLicense = findViewById<Button>(R.id.study_review_model_license)
    val acceptLicense = findViewById<CheckBox>(R.id.study_accept_model_license)
    val accessTokenContainer = findViewById<View>(R.id.study_hugging_face_token_container)
    val accessToken = findViewById<EditText>(R.id.study_hugging_face_token)
    val download = findViewById<Button>(R.id.study_download_model)
    val cancelDownload = findViewById<Button>(R.id.study_cancel_model_download)
    cancelDownload.visibility = View.GONE

    if (model == null) {
      reviewLicense.visibility = View.GONE
      acceptLicense.visibility = View.GONE
      accessTokenContainer.visibility = View.GONE
      download.visibility = View.GONE
      modelStatus.setText(R.string.models_not_available)
      return
    }
    if (modelArtifacts.isInstalled(model)) {
      findViewById<TextView>(R.id.study_model_recommendation)
        .setText(R.string.study_model_installed)
      reviewLicense.visibility = View.GONE
      acceptLicense.visibility = View.GONE
      accessTokenContainer.visibility = View.GONE
      download.visibility = View.GONE
      modelStatus.setText(R.string.study_model_installed)
      return
    }

    modelStatus.setText(R.string.models_ready_to_download)

    reviewLicense.visibility = if (model.requiresLicenseAcceptance) View.VISIBLE else View.GONE
    acceptLicense.visibility = if (model.requiresLicenseAcceptance) View.VISIBLE else View.GONE
    accessTokenContainer.visibility =
      if (model.requiresLicenseAcceptance) View.VISIBLE else View.GONE
    download.visibility = View.VISIBLE

    reviewLicense.setOnClickListener {
      startActivity(
        Intent(
          Intent.ACTION_VIEW,
          Uri.parse("https://huggingface.co/${model.repositoryId}"),
        )
      )
    }
    download.setOnClickListener {
      if (model.requiresLicenseAcceptance && !acceptLicense.isChecked) {
        acceptLicense.error = getString(R.string.study_model_license_required)
        acceptLicense.requestFocus()
        return@setOnClickListener
      }
      startModelDownload(model, accessToken.text.toString())
    }
  }

  private fun startModelDownload(model: ModelDescriptor, accessToken: String) {
    val progressBar = findViewById<ProgressBar>(R.id.study_model_download_progress)
    val downloadButton = findViewById<Button>(R.id.study_download_model)
    val cancelButton = findViewById<Button>(R.id.study_cancel_model_download)
    progressBar.visibility = View.VISIBLE
    progressBar.isIndeterminate = true
    downloadButton.isEnabled = false
    cancelButton.visibility = View.VISIBLE
    cancelButton.setOnClickListener {
      it.isEnabled = false
      modelArtifacts.cancel()
    }
    modelStatus.setText(R.string.study_model_download_started)

    downloadWorker.execute {
      val result =
        modelArtifacts.download(model, accessToken.takeIf { it.isNotBlank() }) { progress ->
          runOnUiThread {
            progress.percent?.let { percent ->
              progressBar.isIndeterminate = false
              progressBar.progress = percent
              modelStatus.text = getString(R.string.study_model_download_percent, percent)
            }
          }
        }
      runOnUiThread { showModelDownloadResult(model, result) }
    }
  }

  private fun showModelDownloadResult(model: ModelDescriptor, result: ModelDownloadResult) {
    val progressBar = findViewById<ProgressBar>(R.id.study_model_download_progress)
    val downloadButton = findViewById<Button>(R.id.study_download_model)
    val cancelButton = findViewById<Button>(R.id.study_cancel_model_download)
    progressBar.visibility = View.GONE
    cancelButton.visibility = View.GONE
    cancelButton.isEnabled = true
    findViewById<EditText>(R.id.study_hugging_face_token).text.clear()
    when (result) {
      is ModelDownloadResult.Success -> {
        modelStatus.setText(R.string.study_model_installed)
        configureModelControls(model)
      }
      is ModelDownloadResult.Failure -> {
        modelStatus.text = result.message
        downloadButton.isEnabled = result.canRetry
      }
      ModelDownloadResult.Cancelled -> {
        modelStatus.setText(R.string.study_model_download_cancelled)
        downloadButton.isEnabled = true
      }
    }
    modelStatus.announceForAccessibility(modelStatus.text)
  }

  private fun configureEdgeToEdge() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT
    val isDarkMode =
      resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    WindowInsetsControllerCompat(window, window.decorView).apply {
      isAppearanceLightStatusBars = !isDarkMode
      isAppearanceLightNavigationBars = !isDarkMode
    }
  }

  private fun applySafeAreas() {
    val root = findViewById<View>(R.id.soma_root)
    val topBar = findViewById<View>(R.id.soma_top_bar)
    val bottomBar = findViewById<View>(R.id.soma_bottom_navigation)
    val initialRootPadding =
      Insets.of(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
    val initialTopPadding = topBar.paddingTop
    val initialBottomPadding = bottomBar.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
      val safeInsets =
        windowInsets.getInsets(
          WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
      val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
      view.updatePadding(
        left = initialRootPadding.left + safeInsets.left,
        top = initialRootPadding.top,
        right = initialRootPadding.right + safeInsets.right,
        bottom = initialRootPadding.bottom + (imeInsets.bottom - safeInsets.bottom).coerceAtLeast(0),
      )
      topBar.updatePadding(top = initialTopPadding + safeInsets.top)
      bottomBar.updatePadding(bottom = initialBottomPadding + safeInsets.bottom)
      windowInsets
    }
    ViewCompat.requestApplyInsets(root)
  }

  private fun setupNavigation() {
    bottomNavigation.setOnItemSelectedListener { item ->
      when (item.itemId) {
        R.id.navigation_study -> showPage(AppPage.STUDY)
        R.id.navigation_models -> showPage(AppPage.MODELS)
        R.id.navigation_settings -> showPage(AppPage.SETTINGS)
        else -> return@setOnItemSelectedListener false
      }
      true
    }
    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          if (currentPage != AppPage.STUDY) {
            bottomNavigation.selectedItemId = R.id.navigation_study
          } else {
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
          }
        }
      },
    )
  }

  private fun showPage(page: AppPage) {
    currentPage = page
    findViewById<View>(R.id.study_page).visibility =
      if (page == AppPage.STUDY) View.VISIBLE else View.GONE
    findViewById<View>(R.id.models_page).visibility =
      if (page == AppPage.MODELS) View.VISIBLE else View.GONE
    findViewById<View>(R.id.settings_page).visibility =
      if (page == AppPage.SETTINGS) View.VISIBLE else View.GONE
    val titleAndSubtitle =
      when (page) {
        AppPage.STUDY -> R.string.nav_study to R.string.study_page_subtitle
        AppPage.MODELS -> R.string.nav_models to R.string.models_page_subtitle
        AppPage.SETTINGS -> R.string.nav_settings to R.string.settings_page_subtitle
      }
    pageTitle.setText(titleAndSubtitle.first)
    pageSubtitle.setText(titleAndSubtitle.second)
    currentFocus?.let { focusedView ->
      (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
        .hideSoftInputFromWindow(focusedView.windowToken, 0)
      focusedView.clearFocus()
    }
    pageTitle.announceForAccessibility(pageTitle.text)
    if (page == AppPage.SETTINGS) refreshSettingsStatus()
  }

  private fun setupSettings() {
    val readAnswers = findViewById<MaterialSwitch>(R.id.settings_read_answers_switch)
    val keepScreenOn = findViewById<MaterialSwitch>(R.id.settings_keep_screen_on_switch)
    readAnswers.isChecked = preferences.getBoolean(KEY_READ_ANSWERS, false)
    keepScreenOn.isChecked = preferences.getBoolean(KEY_KEEP_SCREEN_ON, false)
    applyKeepScreenOn(keepScreenOn.isChecked)
    readAnswers.setOnCheckedChangeListener { _, checked ->
      preferences.edit().putBoolean(KEY_READ_ANSWERS, checked).apply()
    }
    keepScreenOn.setOnCheckedChangeListener { _, checked ->
      preferences.edit().putBoolean(KEY_KEEP_SCREEN_ON, checked).apply()
      applyKeepScreenOn(checked)
    }
    findViewById<Button>(R.id.settings_open_voice_settings).setOnClickListener {
      val voiceSettings = Intent(ACTION_TTS_SETTINGS)
      runCatching { startActivity(voiceSettings) }
        .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
    findViewById<Button>(R.id.settings_manage_permissions).setOnClickListener {
      startActivity(
        Intent(
          Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
          Uri.parse("package:$packageName"),
        )
      )
    }
    findViewById<Button>(R.id.settings_manage_models).setOnClickListener {
      bottomNavigation.selectedItemId = R.id.navigation_models
    }
    findViewById<Button>(R.id.settings_clear_note).setOnClickListener { clearCurrentNote() }
    @Suppress("DEPRECATION")
    val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    findViewById<TextView>(R.id.settings_version).text =
      getString(R.string.settings_version, versionName)
  }

  private fun applyKeepScreenOn(enabled: Boolean) {
    if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  private fun refreshSettingsStatus() {
    if (!::status.isInitialized) return
    val microphoneAllowed =
      ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    findViewById<TextView>(R.id.settings_permission_status).setText(
      if (microphoneAllowed) R.string.settings_microphone_allowed
      else R.string.settings_microphone_not_allowed
    )
    val onDeviceRecognition =
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
    findViewById<TextView>(R.id.settings_speech_status).setText(
      if (onDeviceRecognition && offlineVoiceReady) R.string.settings_speech_ready
      else R.string.settings_speech_partial
    )
  }

  private fun clearCurrentNote() {
    currentDocument = null
    stopReading()
    documentTitle.setText(R.string.study_empty_note_title)
    documentBody.text = ""
    documentBody.visibility = View.GONE
    question.text.clear()
    question.isEnabled = false
    answer.text = ""
    answer.visibility = View.GONE
    askButton.isEnabled = false
    voiceButton.isEnabled = false
    readButton.isEnabled = false
    readButton.visibility = View.GONE
    status.setText(R.string.settings_note_cleared)
    status.announceForAccessibility(status.text)
  }

  private fun beginVoiceQuestion() {
    if (
      Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
    ) {
      showStatus(R.string.study_voice_unavailable)
      return
    }
    if (
      ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
      return
    }
    startVoiceRecognition()
  }

  private fun startVoiceRecognition() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      showStatus(R.string.study_voice_unavailable)
      return
    }
    speechRecognizer?.destroy()
    speechRecognizer =
      runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(this) }
        .getOrElse {
          showStatus(R.string.study_voice_unavailable)
          return
        }
        .also { recognizer ->
          recognizer.setRecognitionListener(
            object : RecognitionListener {
              override fun onReadyForSpeech(params: Bundle?) {
                showStatus(R.string.study_voice_listening)
              }

              override fun onBeginningOfSpeech() = Unit

              override fun onRmsChanged(rmsdB: Float) = Unit

              override fun onBufferReceived(buffer: ByteArray?) = Unit

              override fun onEndOfSpeech() {
                showStatus(R.string.study_voice_processing)
              }

              override fun onError(error: Int) {
                voiceButton.isEnabled = true
                val message =
                  when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                      R.string.study_voice_permission_denied
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> R.string.study_voice_unavailable
                    else -> R.string.study_voice_error
                  }
                showStatus(message)
              }

              override fun onResults(results: Bundle?) {
                voiceButton.isEnabled = true
                val spokenQuestion =
                  results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                if (spokenQuestion.isNullOrEmpty()) {
                  showStatus(R.string.study_voice_error)
                  return
                }
                question.setText(spokenQuestion)
                question.setSelection(spokenQuestion.length)
                showStatus(R.string.study_note_ready)
                question.announceForAccessibility(spokenQuestion)
              }

              override fun onPartialResults(partialResults: Bundle?) = Unit

              override fun onEvent(eventType: Int, params: Bundle?) = Unit
            }
          )
        }

    voiceButton.isEnabled = false
    val recognitionIntent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
      }
    speechRecognizer?.startListening(recognitionIntent)
  }

  private fun initializeOfflineReadingVoice() {
    textToSpeech =
      TextToSpeech(this) { result ->
        val engine = textToSpeech
        if (result == TextToSpeech.SUCCESS && engine != null) {
          val preferredLanguage = Locale.getDefault().language
          val offlineVoices = engine.voices.orEmpty().filterNot { it.isNetworkConnectionRequired }
          val selectedVoice =
            offlineVoices
              .filter { it.locale.language == preferredLanguage }
              .maxByOrNull { it.quality }
              ?: offlineVoices.maxByOrNull { it.quality }
          if (selectedVoice != null) {
            offlineVoiceReady = engine.setVoice(selectedVoice) == TextToSpeech.SUCCESS
          }
          runOnUiThread { refreshSettingsStatus() }
          engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
              override fun onStart(utteranceId: String?) = Unit

              override fun onDone(utteranceId: String?) {
                when (utteranceId) {
                  LAST_UTTERANCE_ID -> runOnUiThread { finishReading() }
                  LAST_ANSWER_UTTERANCE_ID ->
                    runOnUiThread { status.setText(R.string.study_note_ready) }
                }
              }

              override fun onError(utteranceId: String?) {
                runOnUiThread { finishReading() }
              }
            }
          )
        }
      }
  }

  private fun toggleReading() {
    if (textToSpeech?.isSpeaking == true) {
      stopReading()
      return
    }
    val document = currentDocument ?: return
    val engine = textToSpeech
    if (!offlineVoiceReady || engine == null) {
      showStatus(R.string.study_offline_voice_unavailable)
      return
    }
    val chunks = chunkForSpeech(document.text)
    if (chunks.isEmpty()) return
    readButton.setText(R.string.study_stop_reading)
    showStatus(R.string.study_reading_note)
    chunks.forEachIndexed { index, chunk ->
      val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
      val utteranceId = if (index == chunks.lastIndex) LAST_UTTERANCE_ID else "soma-note-$index"
      engine.speak(chunk, queueMode, Bundle(), utteranceId)
    }
  }

  private fun readAnswerAloud(text: String) {
    val engine = textToSpeech
    if (!offlineVoiceReady || engine == null) return
    val chunks = chunkForSpeech(text)
    if (chunks.isEmpty()) return
    showStatus(R.string.study_reading_answer)
    chunks.forEachIndexed { index, chunk ->
      val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
      val utteranceId =
        if (index == chunks.lastIndex) LAST_ANSWER_UTTERANCE_ID else "soma-answer-$index"
      engine.speak(chunk, queueMode, Bundle(), utteranceId)
    }
  }

  private fun chunkForSpeech(text: String): List<String> {
    val maximum = TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(1000)
    return text
      .lineSequence()
      .flatMap { paragraph ->
        paragraph.trim().chunked(maximum - 1).asSequence()
      }
      .filter { it.isNotBlank() }
      .toList()
  }

  private fun stopReading() {
    textToSpeech?.stop()
    finishReading()
  }

  private fun finishReading() {
    readButton.setText(R.string.study_read_note_aloud)
    if (currentDocument != null) status.setText(R.string.study_note_ready)
  }

  private fun showStatus(message: Int) {
    status.setText(message)
    status.announceForAccessibility(status.text)
  }

  private fun setLoading(loading: Boolean) {
    findViewById<Button>(R.id.study_open_note_button).isEnabled = !loading
  }

  private fun retainReadPermission(uri: Uri) {
    runCatching {
      contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }

  private companion object {
    const val PREFERENCES_NAME = "soma_ai_settings"
    const val KEY_READ_ANSWERS = "read_answers"
    const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    const val LAST_UTTERANCE_ID = "soma-note-last"
    const val LAST_ANSWER_UTTERANCE_ID = "soma-answer-last"
    const val ACTION_TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"
  }

  private enum class AppPage {
    STUDY,
    MODELS,
    SETTINGS,
  }
}
