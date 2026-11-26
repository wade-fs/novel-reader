package com.novel.reader.ui

import android.os.Bundle
import android.speech.tts.Voice
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.novel.reader.R
import com.novel.reader.databinding.BottomSheetSettingsBinding
import com.novel.reader.model.AppThemeMode
import com.novel.reader.model.ReadingSettings
import com.novel.reader.model.VoiceGender
import com.novel.reader.service.TtsReadingService
import kotlin.math.roundToInt

class SettingsBottomSheetDialogFragment : BottomSheetDialogFragment() {

    interface Host {
        fun getCurrentReadingSettings(): ReadingSettings
        fun getAvailableTtsVoices(): List<Voice>
        fun onReadingSettingsChanged(newSettings: ReadingSettings)
    }

    companion object {
        const val TAG = "SettingsSheet"

        fun newInstance(): SettingsBottomSheetDialogFragment {
            return SettingsBottomSheetDialogFragment()
        }
    }

    private var _binding: BottomSheetSettingsBinding? = null
    private val binding get() = _binding!!
    private var settings: ReadingSettings = ReadingSettings()
    private var availableVoices: List<Voice> = emptyList()
    private var filteredVoices: List<Voice> = emptyList()
    private var isUpdatingUi = false
    private var lastSpinnerPosition: Int = 0

    private val host: Host?
        get() = activity as? Host

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val currentHost = host
        if (currentHost == null) {
            dismissAllowingStateLoss()
            return
        }
        settings = currentHost.getCurrentReadingSettings().deepCopy()
        availableVoices = currentHost.getAvailableTtsVoices()

        setupViews()
        syncUiFromSettings()
    }

    fun updateAvailableVoices(voices: List<Voice>) {
        availableVoices = voices
        if (_binding != null) {
            bindVoicesSpinner()
        }
    }

    private fun setupViews() {
        // 0. 外觀主題切換（跟隨系統 / 淺色 / 深色）
        binding.chipGroupTheme.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isUpdatingUi) return@setOnCheckedStateChangeListener
            val newTheme = when (checkedIds.firstOrNull()) {
                R.id.chipThemeLight -> AppThemeMode.LIGHT
                R.id.chipThemeDark -> AppThemeMode.DARK
                R.id.chipThemeSystem -> AppThemeMode.SYSTEM
                else -> return@setOnCheckedStateChangeListener
            }
            if (settings.themeMode != newTheme) {
                settings.themeMode = newTheme
                notifyChange()
            }
        }

        // 0.5 閱讀字體大小調整（A- / Slider / A+ / 快速尺寸）
        binding.sliderFontSize.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val clamped = ReadingSettings.clampFontSizeSp(value)
                settings.fontSizeSp = clamped
                updateFontSizeDisplay(clamped, updateSlider = false)
                notifyChange()
            }
        }
        binding.btnFontSizeDecrease.setOnClickListener {
            setFontSize(settings.fontSizeSp - ReadingSettings.FONT_SIZE_STEP_SP)
        }
        binding.btnFontSizeIncrease.setOnClickListener {
            setFontSize(settings.fontSizeSp + ReadingSettings.FONT_SIZE_STEP_SP)
        }
        binding.btnFontSize14.setOnClickListener { setFontSize(14f) }
        binding.btnFontSize17.setOnClickListener { setFontSize(17f) }
        binding.btnFontSize20.setOnClickListener { setFontSize(20f) }
        binding.btnFontSize24.setOnClickListener { setFontSize(24f) }
        binding.btnFontSize28.setOnClickListener { setFontSize(28f) }

        // 1. 朗讀模式切換（讀章名+正文 / 只讀正文）
        binding.chipGroupPresetModes.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isUpdatingUi) return@setOnCheckedStateChangeListener
            when (checkedIds.firstOrNull()) {
                R.id.chipModeTitleAndBody -> {
                    settings.filterConfig.setTitleAndBodyMode()
                    notifyChange()
                    syncFilterUi()
                }
                R.id.chipModeBodyOnly -> {
                    settings.filterConfig.setBodyOnlyMode()
                    notifyChange()
                    syncFilterUi()
                }
            }
        }

        // 2. 語速 Slider 與快速按鈕
        binding.sliderSpeed.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                settings.speechRate = value
                binding.tvSpeedValue.text = String.format("%.2fx", value)
                notifyChange()
            }
        }
        binding.btnSpeed075.setOnClickListener { setSpeed(0.75f) }
        binding.btnSpeed100.setOnClickListener { setSpeed(1.00f) }
        binding.btnSpeed125.setOnClickListener { setSpeed(1.25f) }
        binding.btnSpeed150.setOnClickListener { setSpeed(1.50f) }
        binding.btnSpeed200.setOnClickListener { setSpeed(2.00f) }

        // 3. 語音性別 (男生 / 女生 / 預設)
        binding.chipGroupGender.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isUpdatingUi) return@setOnCheckedStateChangeListener
            val newGender = when (checkedIds.firstOrNull()) {
                R.id.chipGenderMale -> VoiceGender.MALE
                R.id.chipGenderFemale -> VoiceGender.FEMALE
                else -> VoiceGender.DEFAULT
            }
            if (settings.gender != newGender) {
                settings.gender = newGender
                if (newGender == VoiceGender.DEFAULT) {
                    settings.selectedVoiceName = null
                } else if (settings.selectedVoiceName != null) {
                    val currentVoice = availableVoices.find { it.name == settings.selectedVoiceName }
                    val excluded = if (newGender == VoiceGender.MALE) VoiceGender.FEMALE else VoiceGender.MALE
                    if (currentVoice == null || TtsReadingService.detectVoiceGender(currentVoice) == excluded) {
                        settings.selectedVoiceName = null
                    }
                }
                bindVoicesSpinner()
                notifyChange()
            }
        }

        // 語調 Slider
        binding.sliderPitch.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                settings.pitch = value
                binding.tvPitchValue.text = String.format("%.2f", value)
                notifyChange()
            }
        }

        // 系統語音下拉清單
        bindVoicesSpinner()
        binding.spinnerVoices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingUi || position == lastSpinnerPosition) return
                lastSpinnerPosition = position
                if (position == 0) {
                    settings.selectedVoiceName = null
                } else {
                    settings.selectedVoiceName = filteredVoices.getOrNull(position - 1)?.name
                }
                notifyChange()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 開啟系統 TTS 語音設定
        binding.btnOpenSystemTtsSettings.setOnClickListener {
            openSystemTtsSettings()
        }

        // 4. 自動換下一章
        binding.switchAutoNextChapter.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            settings.autoNextChapter = isChecked
            notifyChange()
        }

        // 5. 是否朗讀章名
        binding.switchReadTitle.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            settings.filterConfig.readChapterTitle = isChecked
            notifyChange()
            updatePresetChipsSelection()
        }

        binding.btnDone.setOnClickListener {
            dismiss()
        }
    }

    private fun bindVoicesSpinner() {
        val wasUpdating = isUpdatingUi
        isUpdatingUi = true

        // 系統預設時不必再選語音，直接隱藏下拉清單
        if (settings.gender == VoiceGender.DEFAULT) {
            filteredVoices = emptyList()
            binding.spinnerVoices.visibility = View.GONE
            isUpdatingUi = wasUpdating
            return
        }

        binding.spinnerVoices.visibility = View.VISIBLE
        val excludedGender = if (settings.gender == VoiceGender.MALE) VoiceGender.FEMALE else VoiceGender.MALE
        filteredVoices = availableVoices.filter { v ->
            TtsReadingService.detectVoiceGender(v) != excludedGender
        }

        val defaultLabel = if (settings.gender == VoiceGender.MALE) {
            "🇹🇼 自動選取男聲 (優先台灣口音)"
        } else {
            "🇹🇼 自動選取女聲 (優先台灣口音)"
        }
        val voiceNames = mutableListOf(defaultLabel)
        voiceNames.addAll(filteredVoices.map { formatVoiceLabel(it) })
        val voiceAdapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner_voice,
            android.R.id.text1,
            voiceNames
        )
        voiceAdapter.setDropDownViewResource(R.layout.item_spinner_voice)
        binding.spinnerVoices.adapter = voiceAdapter

        val selectedIdx = filteredVoices.indexOfFirst { it.name == settings.selectedVoiceName }
        val targetPos = if (selectedIdx != -1) selectedIdx + 1 else 0
        lastSpinnerPosition = targetPos
        binding.spinnerVoices.setSelection(targetPos, false)
        isUpdatingUi = wasUpdating
    }

    private fun setFontSize(sizeSp: Float) {
        val clamped = ReadingSettings.clampFontSizeSp(sizeSp).roundToInt().toFloat()
        if (settings.fontSizeSp == clamped) return
        settings.fontSizeSp = clamped
        updateFontSizeDisplay(clamped, updateSlider = true)
        notifyChange()
    }

    private fun updateFontSizeDisplay(sizeSp: Float, updateSlider: Boolean) {
        val clamped = ReadingSettings.clampFontSizeSp(sizeSp)
        if (updateSlider) {
            binding.sliderFontSize.value = clamped
        }
        binding.tvFontSizeValue.text = "${clamped.roundToInt()} sp"
        binding.tvFontSizePreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, clamped)
        binding.btnFontSizeDecrease.isEnabled = clamped > ReadingSettings.MIN_FONT_SIZE_SP
        binding.btnFontSizeIncrease.isEnabled = clamped < ReadingSettings.MAX_FONT_SIZE_SP
    }

    private fun setSpeed(speed: Float) {
        settings.speechRate = speed
        binding.sliderSpeed.value = speed
        binding.tvSpeedValue.text = String.format("%.2fx", speed)
        notifyChange()
    }

    private fun syncUiFromSettings() {
        isUpdatingUi = true

        // 外觀主題
        when (settings.themeMode) {
            AppThemeMode.SYSTEM -> binding.chipThemeSystem.isChecked = true
            AppThemeMode.LIGHT -> binding.chipThemeLight.isChecked = true
            AppThemeMode.DARK -> binding.chipThemeDark.isChecked = true
        }

        // 字體大小
        updateFontSizeDisplay(settings.fontSizeSp, updateSlider = true)

        // 語速
        binding.sliderSpeed.value = settings.speechRate.coerceIn(0.5f, 2.5f)
        binding.tvSpeedValue.text = String.format("%.2fx", settings.speechRate)

        // 性別
        when (settings.gender) {
            VoiceGender.DEFAULT -> binding.chipGenderDefault.isChecked = true
            VoiceGender.MALE -> binding.chipGenderMale.isChecked = true
            VoiceGender.FEMALE -> binding.chipGenderFemale.isChecked = true
        }

        // 語調
        binding.sliderPitch.value = settings.pitch.coerceIn(0.6f, 1.6f)
        binding.tvPitchValue.text = String.format("%.2f", settings.pitch)

        // 自動換章
        binding.switchAutoNextChapter.isChecked = settings.autoNextChapter

        syncFilterUi()
        isUpdatingUi = false
    }

    private fun syncFilterUi() {
        val wasUpdating = isUpdatingUi
        isUpdatingUi = true
        binding.switchReadTitle.isChecked = settings.filterConfig.readChapterTitle
        updatePresetChipsSelection()
        isUpdatingUi = wasUpdating
    }

    private fun updatePresetChipsSelection() {
        if (settings.filterConfig.readChapterTitle) {
            binding.chipModeTitleAndBody.isChecked = true
        } else {
            binding.chipModeBodyOnly.isChecked = true
        }
    }

    private fun formatVoiceLabel(v: Voice): String {
        val n = v.name.lowercase()
        val loc = v.locale
        val isTw = loc.country.equals("TW", true) || loc.toLanguageTag().contains("TW", true) || n.contains("tw")
        val gender = when (TtsReadingService.detectVoiceGender(v)) {
            VoiceGender.FEMALE -> "女聲"
            VoiceGender.MALE -> "男聲"
            VoiceGender.DEFAULT -> "預設"
        }
        return if (isTw) {
            "🇹🇼 台灣 ($gender) · ${v.name}"
        } else {
            "🌐 ${loc.displayCountry.ifEmpty { loc.language }} ($gender) · ${v.name}"
        }
    }

    private fun openSystemTtsSettings() {
        try {
            val intent = android.content.Intent("com.android.settings.TTS_SETTINGS")
            intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            } catch (e2: Exception) {
                android.widget.Toast.makeText(
                    requireContext(),
                    "請至 Android「系統設定 ➔ 系統 ➔ 語言與輸入 ➔ 文字轉語音輸出」下載台灣語音",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun notifyChange() {
        host?.onReadingSettingsChanged(settings.deepCopy())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
