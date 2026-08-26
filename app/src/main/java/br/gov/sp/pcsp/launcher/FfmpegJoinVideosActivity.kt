package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class FfmpegJoinVideosActivity : AppCompatActivity() {

    private lateinit var joinScroll: ScrollView
    private lateinit var timelineScroll: HorizontalScrollView
    private lateinit var timeline: FfmpegJoinTimelineView
    private lateinit var joinPlaybackContainer: View
    private lateinit var joinPlaybackTimeline: FfmpegJoinPlaybackTimelineView
    private lateinit var joinPlayPause: ImageButton
    private lateinit var joinSpeedDown: ImageButton
    private lateinit var joinSpeedUp: ImageButton
    private lateinit var joinCurrentTime: TextView
    private lateinit var resultPreviewContainer: View
    private lateinit var resultVideoPreview: TextureView
    private lateinit var resultTimeline: FfmpegRangeSlider
    private lateinit var resultCurrentTime: TextView
    private lateinit var resultPlayPause: ImageButton
    private lateinit var resultSpeedDown: ImageButton
    private lateinit var resultSpeedUp: ImageButton
    private lateinit var selectedCount: TextView
    private lateinit var controls: View
    private lateinit var buttonTransition: TextView
    private lateinit var inputTransitionTime: EditText
    private lateinit var checkReencode: CheckBox
    private lateinit var checkSmartJoin: CheckBox
    private lateinit var buttonVideoEncoder: TextView
    private lateinit var buttonVideoQuality: TextView
    private lateinit var videoEncodingControls: View
    private lateinit var buttonJoin: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var outputFileName: TextView
    private lateinit var outputActions: View
    private lateinit var buttonSaveToFolder: ImageButton
    private lateinit var buttonOutputFolder: ImageButton
    private lateinit var buttonOutputShare: ImageButton
    private lateinit var buttonSelectOutputFolder: ImageButton
    private lateinit var arrowInputOutput: View

    private val handler = Handler(Looper.getMainLooper())
    private val clips = mutableListOf<JoinClip>()
    private val tempOutputFiles = mutableListOf<File>()
    private var tempSmartJoinDiagnosticFile: File? = null
    private var selectedTransition = TRANSITION_FADE_IN_OUT
    private var isProcessing = false
    private var currentSessionId: Long? = null
    private var preSelectedOutputDirUri: Uri? = null
    private var finalOutputDirUri: Uri? = null
    private var lastOutputUri: Uri? = null
    private var lastOutputName = ""
    private val processingSteps = mutableListOf<ProcessingStep>()
    private var availableVideoEncoders: List<FfmpegVideoEncoder> = emptyList()
    private var selectedVideoEncoder: FfmpegVideoEncoder? = null
    private var selectedVideoQuality = FfmpegVideoQuality.default
    private var updatingJoinModeChecks = false
    private var resultPreviewPlayer: MediaPlayer? = null
    private var resultPreviewSurface: Surface? = null
    private var pendingResultPreviewFile: File? = null
    private var resultPreviewDurationMs = 0L
    private var resultPlaybackSpeed = 1f
    private val resultSpeedSteps = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f)
    private var joinPreviewPlayer: MediaPlayer? = null
    private var joinPreviewClipIndex = -1
    private var joinPreviewPrepared = false
    private var joinPreviewPositionMs = 0L
    private var joinPreviewSpeed = 1f
    private val joinPreviewSpeedSteps = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f)
    private val joinPreviewTicker = object : Runnable {
        override fun run() {
            val player = joinPreviewPlayer ?: return
            val index = joinPreviewClipIndex
            if (index < 0 || index >= clips.size || !player.isPlaying) return
            val position = joinClipOffset(index) + player.currentPosition.toLong()
            joinPlaybackTimeline.setCurrent(position)
            joinCurrentTime.text = formatTime(position)
            handler.postDelayed(this, 50L)
        }
    }
    private val resultPreviewTicker = object : Runnable {
        override fun run() {
            val player = resultPreviewPlayer ?: return
            if (player.isPlaying) {
                val position = player.currentPosition.toLong().coerceIn(0L, resultPreviewDurationMs)
                resultTimeline.setCurrent(position)
                resultCurrentTime.text = formatTime(position)
                handler.postDelayed(this, 100L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_ffmpeg_join_videos)

        joinScroll = findViewById(R.id.join_scroll)
        timelineScroll = findViewById(R.id.timeline_scroll)
        timeline = findViewById(R.id.join_timeline)
        joinPlaybackContainer = findViewById(R.id.join_playback_container)
        joinPlaybackTimeline = findViewById(R.id.join_playback_timeline)
        joinPlayPause = findViewById(R.id.join_play_pause)
        joinSpeedDown = findViewById(R.id.join_speed_down)
        joinSpeedUp = findViewById(R.id.join_speed_up)
        joinCurrentTime = findViewById(R.id.join_current_time)
        resultPreviewContainer = findViewById(R.id.result_preview_container)
        resultVideoPreview = findViewById(R.id.result_video_preview)
        resultTimeline = findViewById(R.id.result_timeline)
        resultCurrentTime = findViewById(R.id.result_current_time)
        resultPlayPause = findViewById(R.id.result_play_pause)
        resultSpeedDown = findViewById(R.id.result_speed_down)
        resultSpeedUp = findViewById(R.id.result_speed_up)
        selectedCount = findViewById(R.id.selected_count)
        controls = findViewById(R.id.join_controls)
        buttonTransition = findViewById(R.id.button_transition)
        inputTransitionTime = findViewById(R.id.input_transition_time)
        checkReencode = findViewById(R.id.check_reencode)
        checkSmartJoin = findViewById(R.id.check_smart_join)
        buttonVideoEncoder = findViewById(R.id.button_video_encoder)
        buttonVideoQuality = findViewById(R.id.button_video_quality)
        videoEncodingControls = findViewById(R.id.video_encoding_controls)
        buttonJoin = findViewById(R.id.button_join)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        outputFileName = findViewById(R.id.output_file_name)
        outputActions = findViewById(R.id.output_actions)
        buttonSaveToFolder = findViewById(R.id.button_save_to_folder)
        buttonOutputFolder = findViewById(R.id.button_output_folder)
        buttonOutputShare = findViewById(R.id.button_output_share)
        buttonSelectOutputFolder = findViewById(R.id.button_select_output_folder)
        arrowInputOutput = findViewById(R.id.arrow_input_output)

        val exitHandler = installCancelAndExitGuard(
            isTaskRunning = { isProcessing },
            cancelTask = { cancelJoin() }
        )
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { exitHandler() }
        findViewById<View>(R.id.button_select_videos).setOnClickListener { openMediaPicker() }
        buttonSelectOutputFolder.setOnClickListener { openOutputFolderPicker(REQUEST_CHOOSE_PRE_OUTPUT_DIR) }
        buttonTransition.setOnClickListener { showTransitionMenu() }
        buttonVideoEncoder.setOnClickListener { showVideoEncoderMenu() }
        findViewById<TextView>(R.id.help_video_encoder).setOnClickListener {
            FfmpegVideoEncoderRegistry.showHelp(this)
        }
        buttonVideoQuality.setOnClickListener { showVideoQualityMenu() }
        findViewById<TextView>(R.id.help_video_quality).setOnClickListener { selectedVideoQuality.showHelp(this) }
        findViewById<TextView>(R.id.help_transition).setOnClickListener { showTransitionHelp() }
        findViewById<TextView>(R.id.help_reencode).setOnClickListener { showReencodeHelp() }
        findViewById<TextView>(R.id.help_smart_join).setOnClickListener { showSmartJoinHelp() }
        buttonJoin.setOnClickListener { if (isProcessing) cancelJoin() else startJoin() }
        buttonSaveToFolder.setOnClickListener {
            val preUri = preSelectedOutputDirUri
            if (preUri != null) saveTempOutputsToUri(preUri) else openOutputFolderPicker(REQUEST_CHOOSE_OUTPUT_DIR)
        }
        buttonOutputFolder.setOnClickListener { openOutputFolder() }
        buttonOutputShare.setOnClickListener { shareOutputFile() }
        outputFileName.setOnClickListener { openOutputFile() }
        joinPlayPause.setOnClickListener { toggleJoinPlayback() }
        joinSpeedDown.setOnClickListener { changeJoinPlaybackSpeed(-1) }
        joinSpeedUp.setOnClickListener { changeJoinPlaybackSpeed(1) }
        joinPlaybackTimeline.onSeek = { position -> seekJoinPlayback(position) }
        resultPlayPause.setOnClickListener { toggleResultPlayback() }
        resultSpeedDown.setOnClickListener { changeResultPlaybackSpeed(-1) }
        resultSpeedUp.setOnClickListener { changeResultPlaybackSpeed(1) }
        resultTimeline.setRangeMarkersVisible(false)
        resultTimeline.isEnabled = false
        resultTimeline.onPositionChanged = { position, fromUser ->
            if (fromUser) seekResultPreview(position)
        }
        resultVideoPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                resultPreviewSurface = Surface(surfaceTexture)
                pendingResultPreviewFile?.let(::prepareJoinedPreview)
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                applyResultPreviewTransform()
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                resultPreviewPlayer?.setSurface(null)
                resultPreviewSurface?.release()
                resultPreviewSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
        }

        checkReencode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !updatingJoinModeChecks) {
                updatingJoinModeChecks = true
                checkSmartJoin.isChecked = false
                updatingJoinModeChecks = false
            }
            normalizeVideoTransitionForCurrentMode()
            updateReencodeControls()
        }
        checkSmartJoin.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !updatingJoinModeChecks) {
                updatingJoinModeChecks = true
                checkReencode.isChecked = false
                updatingJoinModeChecks = false
            }
            normalizeVideoTransitionForCurrentMode()
            updateReencodeControls()
        }
        timeline.onOrderChanged = { ids ->
            stopJoinPlayback()
            val byId = clips.associateBy { it.id }
            clips.clear()
            clips.addAll(ids.mapNotNull { byId[it] })
            updateSelectionUi()
        }
        detectVideoEncoders()
        updateReencodeControls()
        updateSelectionUi()
        updateJoinSpeedButtons()
    }

    @Deprecated("Deprecated Android callback kept for this legacy XML activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return

        when (requestCode) {
            REQUEST_PICK_VIDEOS -> handlePickedMedia(data)
            REQUEST_CHOOSE_PRE_OUTPUT_DIR -> data?.data?.let {
                takeTreePermission(it)
                preSelectedOutputDirUri = it
                buttonSelectOutputFolder.setBackgroundResource(R.drawable.ffmpeg_outline_green_button_bg)
                Toast.makeText(this, "Pasta de saída selecionada.", Toast.LENGTH_SHORT).show()
            }
            REQUEST_CHOOSE_OUTPUT_DIR -> data?.data?.let {
                takeTreePermission(it)
                saveTempOutputsToUri(it)
            }
        }
    }

    private fun openMediaPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PICK_VIDEOS)
    }

    private fun handlePickedMedia(data: Intent?) {
        val flags = data?.flags ?: 0
        val uris = mutableListOf<Uri>()
        data?.clipData?.let { clipData ->
            for (index in 0 until clipData.itemCount) {
                uris += clipData.getItemAt(index).uri
            }
        }
        data?.data?.let { uris += it }
        if (uris.isEmpty()) return

        val loaded = uris.distinct().mapNotNull { uri ->
            try {
                if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (_: SecurityException) {
            }
            loadClip(uri)
        }
        val combinedKinds = (clips + loaded).map { it.isAudio }.distinct()
        if (combinedKinds.size > 1) {
            Toast.makeText(this, "Selecione apenas áudios ou apenas vídeos por vez.", Toast.LENGTH_LONG).show()
            return
        }
        clearOutputResult()
        clips += loaded
        updateSelectionUi()
    }

    private fun loadClip(uri: Uri): JoinClip? {
        val name = queryDisplayName(uri) ?: "midia_${clips.size + 1}"
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(1L) ?: 1L
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val width = rawWidth?.coerceAtLeast(2) ?: 1280
            val height = rawHeight?.coerceAtLeast(2) ?: 720
            val rotationDegrees = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val thumbnail = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            val isAudio = rawWidth == null && rawHeight == null && thumbnail == null && hasAudio
            if (!hasAudio && isAudio) error("Arquivo sem faixa de áudio")
            JoinClip(System.nanoTime(), uri, name, durationMs, width, height, rotationDegrees, hasAudio, isAudio, thumbnail)
        } catch (e: Throwable) {
            Log.e(TAG, "Could not load clip $name", e)
            Toast.makeText(this, "Não consegui ler $name.", Toast.LENGTH_SHORT).show()
            null
        } finally {
            retriever.release()
        }
    }

    private fun updateSelectionUi() {
        val noun = if (currentJoinIsAudio()) "áudio" else "vídeo"
        selectedCount.text = when (clips.size) {
            0 -> "Nenhum áudio ou vídeo selecionado"
            1 -> "1 $noun selecionado"
            else -> "${clips.size} ${noun}s selecionados"
        }
        val allowedTransitions = if (currentJoinIsAudio()) {
            AUDIO_TRANSITIONS.keys
        } else {
            TRANSITIONS.filter { it != TRANSITION_FADE_IN_OUT || checkSmartJoin.isChecked }
        }
        if (selectedTransition !in allowedTransitions) {
            selectedTransition = if (currentJoinIsAudio() || checkSmartJoin.isChecked) {
                TRANSITION_FADE_IN_OUT
            } else {
                TRANSITION_DEFAULT_VIDEO
            }
            buttonTransition.text = "Transição: $selectedTransition"
        }
        controls.visibility = if (clips.isNotEmpty()) View.VISIBLE else View.GONE
        arrowInputOutput.visibility = if (clips.isNotEmpty()) View.VISIBLE else View.GONE
        buttonSelectOutputFolder.visibility = if (clips.isNotEmpty()) View.VISIBLE else View.GONE
        timeline.setClips(clips.map { FfmpegJoinTimelineView.Clip(it.id, it.name, it.durationMs, it.thumbnail, it.isAudio) })
        joinPlaybackTimeline.setSegments(clips.map {
            FfmpegJoinPlaybackTimelineView.Segment(it.name, it.durationMs, it.isAudio)
        })
        joinPlaybackTimeline.setCurrent(currentJoinPlaybackPosition())
        joinPlaybackContainer.visibility = if (clips.isNotEmpty() && resultPreviewContainer.visibility != View.VISIBLE) {
            View.VISIBLE
        } else {
            View.GONE
        }
        joinCurrentTime.text = formatTime(currentJoinPlaybackPosition())
        videoEncodingControls.visibility = if (currentJoinIsAudio()) View.GONE else View.VISIBLE
        setJoinEnabled(clips.size >= 2 && !isProcessing)
    }

    private fun updateReencodeControls() {
        val transitionEnabled = (checkReencode.isChecked || checkSmartJoin.isChecked) && !isProcessing
        val enabled = transitionEnabled
        buttonTransition.isEnabled = enabled
        inputTransitionTime.isEnabled = enabled
        val alpha = if (enabled) 1f else 0.42f
        buttonTransition.alpha = alpha
        inputTransitionTime.alpha = alpha
        val encoderEnabled = !currentJoinIsAudio() && (checkReencode.isChecked || checkSmartJoin.isChecked) &&
            selectedVideoEncoder != null && !isProcessing
        buttonVideoEncoder.isEnabled = encoderEnabled
        buttonVideoEncoder.alpha = if (encoderEnabled) 1f else 0.42f
        buttonVideoQuality.isEnabled = encoderEnabled
        buttonVideoQuality.alpha = if (encoderEnabled) 1f else 0.42f
    }

    private fun detectVideoEncoders() {
        availableVideoEncoders = FfmpegVideoEncoderRegistry.detect()
        selectedVideoEncoder = availableVideoEncoders.firstOrNull()
        updateVideoEncoderButton()
    }

    private fun showVideoEncoderMenu() {
        if (availableVideoEncoders.isEmpty() || isProcessing) return
        PopupMenu(this, buttonVideoEncoder).apply {
            availableVideoEncoders.forEach { menu.add(it.displayName) }
            setOnMenuItemClickListener { item ->
                selectedVideoEncoder = availableVideoEncoders.firstOrNull { it.displayName == item.title.toString() }
                updateVideoEncoderButton()
                true
            }
            show()
        }
    }

    private fun updateVideoEncoderButton() {
        val encoder = selectedVideoEncoder
        buttonVideoEncoder.text = if (encoder == null) "Encoder indisponível" else encoder.shortName
        buttonVideoQuality.text = selectedVideoQuality.label
        updateReencodeControls()
    }

    private fun normalizeVideoTransitionForCurrentMode() {
        if (currentJoinIsAudio() || checkSmartJoin.isChecked || selectedTransition != TRANSITION_FADE_IN_OUT) return
        selectedTransition = TRANSITION_DEFAULT_VIDEO
        buttonTransition.text = "Transição: $selectedTransition"
    }

    private fun showVideoQualityMenu() {
        if (selectedVideoEncoder == null || isProcessing) return
        PopupMenu(this, buttonVideoQuality).apply {
            FfmpegVideoQuality.entries.forEach { menu.add(it.menuLabel) }
            setOnMenuItemClickListener { item ->
                selectedVideoQuality = FfmpegVideoQuality.entries.first { it.menuLabel == item.title.toString() }
                updateVideoEncoderButton()
                true
            }
            show()
        }
    }

    private fun setJoinEnabled(enabled: Boolean) {
        buttonJoin.alpha = if (enabled) 1f else 0.45f
        buttonJoin.isClickable = enabled
        buttonJoin.isFocusable = enabled
    }

    private fun showTransitionMenu() {
        PopupMenu(this, buttonTransition).apply {
            val transitions = if (currentJoinIsAudio()) {
                AUDIO_TRANSITIONS.keys
            } else {
                TRANSITIONS.filter { it != TRANSITION_FADE_IN_OUT || checkSmartJoin.isChecked }
            }
            transitions.forEach { transition ->
                menu.add(transition)
            }
            setOnMenuItemClickListener { item ->
                selectedTransition = item.title.toString()
                buttonTransition.text = "Transição: $selectedTransition"
                true
            }
            show()
        }
    }

    private fun showReencodeHelp() {
        val message = if (currentJoinIsAudio()) {
            "Sem reencodar, a junção usa -c copy e é rápida e sem perda, mas exige áudios compatíveis.\n\nCom reencode, o app normaliza taxa de amostragem, canais e bitrate, permitindo aplicar fade ou crossfade."
        } else {
            "Reencode Completo reencoda todos os vídeos usando o encoder e a qualidade escolhidos. É mais previsível para arquivos incompatíveis, mas pode demorar bastante e consumir mais bateria.\n\nA opção Fade in/out fica disponível no Smart Join, que tenta preservar os trechos longos e reencoda apenas as regiões necessárias."
        }
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showTransitionHelp() {
        val message = if (currentJoinIsAudio()) {
            "Fade in/out reduz o volume no fim de um áudio e aumenta no começo do seguinte, sem sobreposição.\n\nAs demais opções usam crossfade, sobrepondo suavemente o fim e o começo dos áudios pelo tempo escolhido."
        } else {
            "Fade in/out escurece o fim de um vídeo até preto e clareia o começo do próximo vídeo a partir do preto.\n\nAs outras opções são transições do FFmpeg. Elas podem ficar mais sofisticadas, mas exigem reencodar a saída de forma mais pesada."
        }
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showSmartJoinHelp() {
        AlertDialog.Builder(this)
            .setMessage("Tenta copiar a maior parte dos vídeos e reencodar apenas os trechos próximos à transição.\n\nAntes de começar, o app faz um teste curto com o encoder escolhido. Se ele falhar, testa alternativas compatíveis e oferece as opções de continuar com outro encoder, fazer Reencode Completo ou cancelar.\n\nVantagem: acelera muito o processo. Desvantagem: pode gerar pequenos bugs no vídeo.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun startJoin() {
        if (clips.size < 2) return
        if (!currentJoinIsAudio() && (checkReencode.isChecked || checkSmartJoin.isChecked) && selectedVideoEncoder == null) {
            status.text = "Nenhum encoder de vídeo compatível está disponível."
            return
        }
        clearOutputResult()
        initProcessingSteps()
        setProcessing(true)
        val processingStartMs = SystemClock.elapsedRealtime()
        Thread {
            val copiedInputs = mutableListOf<File>()
            try {
                updateStep("Preparar arquivos de entrada", 0, StepState.RUNNING)
                clips.forEachIndexed { index, clip ->
                    updateStep("Preparar arquivos de entrada", ((index.toDouble() / clips.size) * 100.0).toInt(), StepState.RUNNING)
                    copiedInputs += copyUriToCache(clip.uri, "join_input_${index}_${clip.name}")
                }
                updateStep("Preparar arquivos de entrada", 100, StepState.DONE)
                val audioOnly = currentJoinIsAudio()
                val audioNeedsNormalization = audioOnly && !audioInputsAreCopyCompatible(copiedInputs)
                val audioWillStandardizeToWav = audioNeedsNormalization &&
                    !checkReencode.isChecked && !checkSmartJoin.isChecked
                val outputName = buildJoinedOutputName(forceAudioStandardization = audioWillStandardizeToWav)
                val tempOutput = File(cacheDir, "join_${System.currentTimeMillis()}_$outputName")
                val sourceProfile = detectOutputProfile(copiedInputs.firstOrNull(), clips.firstOrNull())
                val originalEncoder = selectedVideoEncoder
                val fastEncoderCompatible = originalEncoder != null &&
                    SmartJoinPlanner.normalizeCodecFamily(originalEncoder.codecFamily) ==
                    SmartJoinPlanner.normalizeCodecFamily(sourceProfile.videoCodec)
                val orientationMismatch = !audioOnly && shouldUseOrientationSafeReencode()
                val smartJoinProfiles = if (!audioOnly && checkSmartJoin.isChecked) {
                    copiedInputs.mapIndexed { index, input ->
                        val profile = detectOutputProfile(input, clips.getOrNull(index))
                        SmartJoinMediaProfile(
                            codecFamily = profile.videoCodec,
                            width = profile.width,
                            height = profile.height,
                            fps = profile.fps.toDoubleOrNull() ?: 30.0,
                            rotationDegrees = profile.rotationDegrees,
                            audioSampleRate = profile.audioSampleRate,
                            audioChannels = profile.audioChannels,
                            hasAudio = clips.getOrNull(index)?.hasAudio == true
                        )
                    }
                } else {
                    emptyList()
                }
                val smartJoinInputsCompatible = smartJoinProfiles.firstOrNull()?.let { base ->
                    smartJoinProfiles.drop(1).all { candidate ->
                        SmartJoinPlanner.profilesCompatible(base, candidate)
                    }
                } ?: true
                val smartJoinPlan = if (!audioOnly && checkSmartJoin.isChecked) {
                    SmartJoinPlanner.plan(
                        sourceCodecFamily = sourceProfile.videoCodec,
                        selectedEncoder = originalEncoder?.toSmartJoinOption(),
                        availableEncoders = availableVideoEncoders.map { it.toSmartJoinOption() },
                        inputsCompatible = smartJoinInputsCompatible,
                        orientationMismatch = orientationMismatch
                    )
                } else {
                    null
                }
                var effectiveSmartJoinEncoder: FfmpegVideoEncoder? = null
                var forceSmartJoinFullReencode = false

                if (!audioOnly) {
                    if (checkSmartJoin.isChecked) {
                        configureVideoProcessingPlan(listOf(SMART_JOIN_VALIDATION_LABEL))
                        if (smartJoinPlan?.mode == SmartJoinMode.SMART_JOIN) {
                            val preflight = runSmartJoinPreflight(
                                copiedInputs,
                                smartJoinPlan,
                                originalEncoder
                            )
                            val selectedPassed = originalEncoder != null &&
                                preflight.approvedEncoders.any { it.ffmpegName == originalEncoder.ffmpegName }
                            if (selectedPassed) {
                                effectiveSmartJoinEncoder = originalEncoder
                            } else {
                                val decision = awaitSmartJoinDecision(
                                    selectedEncoder = originalEncoder,
                                    approvedEncoders = preflight.approvedEncoders,
                                    failureMessages = preflight.failureMessages,
                                    reason = smartJoinPlan.reason
                                )
                                when (decision.type) {
                                    SmartJoinDecisionType.USE_ENCODER -> {
                                        val chosenEncoder = requireNotNull(decision.encoder)
                                        effectiveSmartJoinEncoder = chosenEncoder
                                        adoptVideoEncoder(chosenEncoder)
                                    }
                                    SmartJoinDecisionType.FULL_REENCODE -> {
                                        forceSmartJoinFullReencode = true
                                    }
                                    SmartJoinDecisionType.CANCEL -> {
                                        runOnUiThread {
                                            setProcessing(false)
                                            failActiveStep("Operação cancelada pelo usuário.")
                                        }
                                        return@Thread
                                    }
                                }
                            }
                        } else {
                            updateStep(SMART_JOIN_VALIDATION_LABEL, 100, StepState.DONE)
                            val decision = awaitSmartJoinDecision(
                                selectedEncoder = originalEncoder,
                                approvedEncoders = emptyList(),
                                failureMessages = emptyList(),
                                reason = smartJoinPlan?.reason
                            )
                            when (decision.type) {
                                SmartJoinDecisionType.USE_ENCODER -> {
                                    val chosenEncoder = requireNotNull(decision.encoder)
                                    effectiveSmartJoinEncoder = chosenEncoder
                                    adoptVideoEncoder(chosenEncoder)
                                }
                                SmartJoinDecisionType.FULL_REENCODE -> {
                                    forceSmartJoinFullReencode = true
                                }
                                SmartJoinDecisionType.CANCEL -> {
                                    runOnUiThread {
                                        setProcessing(false)
                                        failActiveStep("Operação cancelada pelo usuário.")
                                    }
                                    return@Thread
                                }
                            }
                        }
                    } else {
                        configureVideoProcessingPlan(regularVideoProcessingLabels())
                    }

                    configureVideoProcessingPlan(
                        when {
                            checkSmartJoin.isChecked && !forceSmartJoinFullReencode -> smartJoinProcessingLabels()
                            checkSmartJoin.isChecked -> listOf(SMART_JOIN_VALIDATION_LABEL, "Reencodando vídeo completo")
                            else -> regularVideoProcessingLabels()
                        }
                    )
                }

                var result = if (audioOnly) {
                    executeAudioJoin(copiedInputs, tempOutput, sourceProfile, forceNormalization = audioNeedsNormalization)
                } else if (checkSmartJoin.isChecked) {
                    if (forceSmartJoinFullReencode || effectiveSmartJoinEncoder == null) {
                        executeFullReencodeJoin(
                            copiedInputs,
                            tempOutput,
                            "Reencodando vídeo completo"
                        )
                    } else {
                        executeSmartJoinExperiment(copiedInputs, tempOutput, effectiveSmartJoinEncoder)
                    }
                } else if (checkReencode.isChecked) {
                    if (isFadeInOutTransition() && fastEncoderCompatible) {
                        executeMinimalTransitionJoin(copiedInputs, tempOutput)
                    } else {
                        executeFullReencodeJoin(
                            copiedInputs,
                            tempOutput,
                            "Aplicando transições"
                        )
                    }
                } else {
                    val session = executeFfmpegWithProgress(
                        buildDirectConcatArguments(copiedInputs, tempOutput),
                        totalDurationMs(),
                        "Juntando sem reencodar"
                    )
                    JoinExecutionResult(
                        success = ReturnCode.isSuccess(session.returnCode) && tempOutput.exists() && tempOutput.length() > 0L,
                        cancelled = ReturnCode.isCancel(session.returnCode),
                        failureMessage = ffmpegFailureMessage("Juntando sem reencodar", session)
                    )
                }

                if (!audioOnly && checkSmartJoin.isChecked && !forceSmartJoinFullReencode &&
                    !result.success && !result.cancelled && smartJoinPlan?.mode == SmartJoinMode.SMART_JOIN &&
                    effectiveSmartJoinEncoder != null
                ) {
                    val retryPreflight = runSmartJoinPreflight(
                        copiedInputs,
                        smartJoinPlan,
                        selectedEncoder = effectiveSmartJoinEncoder,
                        excludedEncoderName = effectiveSmartJoinEncoder.ffmpegName,
                        testAllCandidates = true
                    )
                    val decision = awaitSmartJoinDecision(
                        selectedEncoder = effectiveSmartJoinEncoder,
                        approvedEncoders = retryPreflight.approvedEncoders,
                        failureMessages = retryPreflight.failureMessages +
                            "Falha na tentativa principal: ${result.failureMessage.take(180)}",
                        reason = "O teste curto passou, mas a execução real falhou."
                    )
                    when (decision.type) {
                        SmartJoinDecisionType.USE_ENCODER -> {
                            val chosenEncoder = requireNotNull(decision.encoder)
                            adoptVideoEncoder(chosenEncoder)
                            configureVideoProcessingPlan(
                                smartJoinProcessingLabels(),
                                preserveExistingTaskState = false
                            )
                            tempOutput.delete()
                            result = executeSmartJoinExperiment(copiedInputs, tempOutput, chosenEncoder)
                        }
                        SmartJoinDecisionType.FULL_REENCODE -> {
                            configureVideoProcessingPlan(
                                listOf(SMART_JOIN_VALIDATION_LABEL, "Reencodando vídeo completo"),
                                preserveExistingTaskState = false
                            )
                            tempOutput.delete()
                            result = executeFullReencodeJoin(
                                copiedInputs,
                                tempOutput,
                                "Reencodando vídeo completo"
                            )
                        }
                        SmartJoinDecisionType.CANCEL -> {
                            result = JoinExecutionResult(false, true, "")
                        }
                    }
                }

                runOnUiThread {
                    setProcessing(false)
                    if (result.cancelled) {
                        failActiveStep("Operação cancelada")
                        return@runOnUiThread
                    }
                    if (!result.success) {
                        failActiveStep("Erro: ${result.failureMessage.take(160)}")
                        return@runOnUiThread
                    }

                    tempOutputFiles.clear()
                    tempOutputFiles.add(tempOutput)
                    tempSmartJoinDiagnosticFile = result.diagnosticFile
                    lastOutputName = outputName
                    updateStep("Preparar arquivo para salvar", 100, StepState.DONE)
                    
                    val elapsedMs = SystemClock.elapsedRealtime() - processingStartMs
                    val elapsedSeconds = (elapsedMs / 1000.0).coerceAtLeast(0.001)
                    val mediaSeconds = totalDurationMs() / 1000.0
                    val efficiency = String.format(Locale.US, "%.2fx", mediaSeconds / elapsedSeconds)
                    successStep("Tempo de processamento: ${formatTime(elapsedMs)}\nMídia processada: ${formatTime(totalDurationMs())}\nEficiência: $efficiency")

                    outputActions.visibility = View.VISIBLE
                    buttonSaveToFolder.visibility = View.VISIBLE
                    buttonOutputFolder.visibility = View.GONE
                    buttonOutputShare.visibility = View.GONE
                    showJoinedPreview(tempOutput)
                    joinScroll.post { joinScroll.smoothScrollTo(0, outputActions.bottom) }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to join media", e)
                runOnUiThread {
                    setProcessing(false)
                    failActiveStep("Erro: ${e.message ?: "falha inesperada"}")
                }
            } finally {
                copiedInputs.forEach { it.delete() }
            }
        }.start()
    }

    private fun runSmartJoinPreflight(
        inputs: List<File>,
        plan: SmartJoinPlan,
        selectedEncoder: FfmpegVideoEncoder?,
        excludedEncoderName: String? = null,
        testAllCandidates: Boolean = false
    ): SmartJoinPreflightResult {
        val options = plan.compatibleEncoders
            .mapNotNull { option -> availableVideoEncoders.firstOrNull { it.ffmpegName == option.ffmpegName } }
            .filter { it.ffmpegName != excludedEncoderName }
        if (options.isEmpty()) {
            updateStep(SMART_JOIN_VALIDATION_LABEL, 100, StepState.DONE)
            return SmartJoinPreflightResult(emptyList(), emptyList(), emptyList())
        }

        restartProcessingStep(SMART_JOIN_VALIDATION_LABEL)
        val approved = mutableListOf<FfmpegVideoEncoder>()
        val failures = mutableListOf<String>()
        for ((index, candidate) in options.withIndex()) {
            updateStep(
                SMART_JOIN_VALIDATION_LABEL,
                ((index.toDouble() / options.size.toDouble()) * 100.0).toInt(),
                StepState.RUNNING,
                "teste curto",
                encoderName = candidate.shortName
            )
            val probe = probeSmartJoinEncoder(inputs, candidate)
            if (probe.success) {
                approved += candidate
                // Se o encoder escolhido passou no preflight, não há motivo para
                // fazer testes extras antes de iniciar o trabalho real.
                if (!testAllCandidates && candidate.ffmpegName == selectedEncoder?.ffmpegName) break
            } else {
                failures += "${candidate.displayName}: ${probe.detail}"
            }
        }
        updateStep(SMART_JOIN_VALIDATION_LABEL, 100, StepState.DONE)
        return SmartJoinPreflightResult(
            approvedEncoders = approved,
            failedEncoders = options.filter { candidate -> approved.none { it.ffmpegName == candidate.ffmpegName } },
            failureMessages = failures
        )
    }

    private fun probeSmartJoinEncoder(inputs: List<File>, encoder: FfmpegVideoEncoder): SmartJoinEncoderProbe {
        if (inputs.size < 2 || clips.size < 2) {
            return SmartJoinEncoderProbe(false, "Não há dois arquivos para testar.")
        }
        val transitionSeconds = safeTransitionSeconds()
            .coerceAtMost(SMART_JOIN_PREFLIGHT_MAX_SECONDS)
        if (transitionSeconds <= 0.0) {
            return SmartJoinEncoderProbe(false, "A duração da transição precisa ser maior que zero.")
        }

        val outputFile = File(cacheDir, "smartjoin_preflight_${System.nanoTime()}.ts")
        return try {
            val profile = detectOutputProfile(inputs.first(), clips.first(), encoder)
            val arguments = buildTransitionArgumentsMkv(
                firstInput = inputs[0],
                secondInput = inputs[1],
                firstClip = clips[0],
                secondClip = clips[1],
                outputFile = outputFile,
                transitionSeconds = transitionSeconds,
                profile = profile,
                encoder = encoder
            )
            val probeArguments = arrayOf("-hide_banner", "-loglevel", "error") + arguments
            val session = FFmpegKit.executeWithArguments(probeArguments)
            val success = ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0L
            if (success) {
                SmartJoinEncoderProbe(true, "teste curto aprovado")
            } else {
                SmartJoinEncoderProbe(false, ffmpegFailureMessage("preflight", session).take(180))
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Smart Join preflight failed for ${encoder.ffmpegName}", error)
            SmartJoinEncoderProbe(false, error.message?.take(180) ?: "falha ao testar encoder")
        } finally {
            outputFile.delete()
        }
    }

    private fun awaitSmartJoinDecision(
        selectedEncoder: FfmpegVideoEncoder?,
        approvedEncoders: List<FfmpegVideoEncoder>,
        failureMessages: List<String>,
        reason: String?
    ): SmartJoinDecision {
        val choices = mutableListOf<SmartJoinDecision>()
        val labels = mutableListOf<String>()
        approvedEncoders
            .filter { it.ffmpegName != selectedEncoder?.ffmpegName }
            .forEach { encoder ->
                choices += SmartJoinDecision(SmartJoinDecisionType.USE_ENCODER, encoder)
                labels += "Usar ${encoder.displayName} no Smart Join"
            }
        selectedEncoder?.let { encoder ->
            choices += SmartJoinDecision(SmartJoinDecisionType.FULL_REENCODE, encoder)
            labels += "Reencode completo com ${encoder.displayName}"
        }

        val result = AtomicReference(SmartJoinDecision(SmartJoinDecisionType.CANCEL))
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = CountDownLatch(1)
        fun finish(decision: SmartJoinDecision) {
            if (finished.compareAndSet(false, true)) {
                result.set(decision)
                latch.countDown()
            }
        }
        val message = buildString {
            append("O Smart Join não conseguiu usar o encoder escolhido.")
            reason?.takeIf { it.isNotBlank() }?.let { append("\n\n$it") }
            if (approvedEncoders.isNotEmpty()) {
                append("\n\nEncoders aprovados no teste curto:")
                approvedEncoders.forEach { append("\n• ${it.displayName}") }
            }
            if (failureMessages.isNotEmpty()) {
                append("\n\nFalhas detectadas:")
                failureMessages.take(3).forEach { append("\n• $it") }
            }
            append("\n\nEscolha como deseja continuar.")
        }

        val show: () -> Unit = {
            lateinit var dialog: AlertDialog
            val dp: (Int) -> Int = { value ->
                (value * resources.displayMetrics.density + 0.5f).toInt()
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(4), dp(20), dp(4))
            }
            body.addView(
                TextView(this).apply {
                    text = message
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    setPadding(0, dp(4), 0, dp(8))
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            choices.forEachIndexed { index, decision ->
                body.addView(
                    Button(this).apply {
                        text = labels[index]
                        isAllCaps = false
                        setTextColor(Color.WHITE)
                        minHeight = dp(48)
                        setOnClickListener {
                            dialog.dismiss()
                            finish(decision)
                        }
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(4)
                    }
                )
            }
            val scroll = ScrollView(this).apply {
                addView(body)
            }
            dialog = AlertDialog.Builder(this)
                .setTitle("Smart Join precisa de uma decisão")
                .setView(scroll)
                .setNegativeButton("Cancelar") { _, _ ->
                    finish(SmartJoinDecision(SmartJoinDecisionType.CANCEL))
                }
                .setOnCancelListener {
                    finish(SmartJoinDecision(SmartJoinDecisionType.CANCEL))
                }
                .create()
            dialog.show()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) show() else runOnUiThread(show)
        latch.await()
        return result.get()
    }

    private fun adoptVideoEncoder(encoder: FfmpegVideoEncoder) {
        selectedVideoEncoder = encoder
        runOnUiThread { updateVideoEncoderButton() }
    }

    private fun executeFullReencodeJoin(inputs: List<File>, outputFile: File, taskLabel: String): JoinExecutionResult {
        val session = executeFfmpegWithProgress(
            buildReencodeArguments(inputs, outputFile, withTransition = true),
            totalDurationMs(),
            taskLabel,
            selectedVideoEncoder?.shortName
        )
        return JoinExecutionResult(
            success = ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0L,
            cancelled = ReturnCode.isCancel(session.returnCode),
            failureMessage = ffmpegFailureMessage(taskLabel, session)
        )
    }

    private fun executeAudioJoin(
        inputs: List<File>,
        outputFile: File,
        profile: OutputProfile,
        forceNormalization: Boolean
    ): JoinExecutionResult {
        val requestedReencode = checkReencode.isChecked || checkSmartJoin.isChecked
        val normalize = requestedReencode || forceNormalization
        val label = when {
            requestedReencode -> "Aplicando transição de áudio"
            forceNormalization -> "Normalizando pelo primeiro áudio"
            else -> "Juntando áudios sem reencodar"
        }
        if (forceNormalization && !requestedReencode) {
            renameProcessingStep("Juntando áudios sem reencodar", label)
        }
        val arguments = if (normalize) {
            buildAudioReencodeArguments(
                inputs,
                outputFile,
                profile,
                withTransition = requestedReencode,
                standardizeToWav = forceNormalization && !requestedReencode
            )
        } else {
            buildDirectConcatArguments(inputs, outputFile)
        }
        val encoderName = when {
            !normalize -> null
            forceNormalization && !requestedReencode -> "pcm_s16le"
            else -> "aac"
        }
        val session = executeFfmpegWithProgress(arguments, totalDurationMs(), label, encoderName)
        return JoinExecutionResult(
            success = ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0L,
            cancelled = ReturnCode.isCancel(session.returnCode),
            failureMessage = ffmpegFailureMessage(label, session)
        )
    }

    private fun buildAudioReencodeArguments(
        inputs: List<File>,
        outputFile: File,
        profile: OutputProfile,
        withTransition: Boolean = true,
        standardizeToWav: Boolean = false
    ): Array<String> {
        val transitionSeconds = safeTransitionSeconds()
            .coerceAtMost((clips.minOfOrNull { it.durationMs } ?: 1L) / 2000.0)
            .coerceAtLeast(0.01)
        val normalizeFilter = audioJoinNormalizeFilter(profile)
        val parts = mutableListOf<String>()
        clips.forEachIndexed { index, clip ->
            val clipSeconds = (clip.durationMs / 1000.0).coerceAtLeast(0.01)
            val fades = mutableListOf<String>()
            if (withTransition && isFadeInOutTransition()) {
                if (index > 0) fades += "afade=t=in:st=0:d=${formatDecimal(transitionSeconds)}"
                if (index < clips.lastIndex) {
                    fades += "afade=t=out:st=${formatDecimal((clipSeconds - transitionSeconds).coerceAtLeast(0.0))}:d=${formatDecimal(transitionSeconds)}"
                }
            }
            parts += buildString {
                append("[$index:a]")
                append(normalizeFilter)
                fades.forEach { append(',').append(it) }
                append(",asetpts=PTS-STARTPTS[a$index]")
            }
        }
        if (withTransition && isFadeInOutTransition()) {
            parts += clips.indices.joinToString("") { "[a$it]" } + "concat=n=${clips.size}:v=0:a=1[aout]"
        } else if (withTransition) {
            var previous = "a0"
            for (index in 1 until clips.size) {
                val output = "ax$index"
                val curve = audioCrossfadeCurve()
                parts += "[$previous][a$index]acrossfade=d=${formatDecimal(transitionSeconds)}:c1=$curve:c2=$curve[$output]"
                previous = output
            }
            parts += "[$previous]anull[aout]"
        } else {
            parts += clips.indices.joinToString("") { "[a$it]" } + "concat=n=${clips.size}:v=0:a=1[aout]"
        }
        val args = mutableListOf("-y")
        inputs.forEach { args += listOf("-i", it.absolutePath) }
        args += listOf(
            "-filter_complex", parts.joinToString(";"),
            "-map", "[aout]",
            "-vn"
        )
        if (standardizeToWav) {
            args += listOf(
                "-c:a", "pcm_s16le",
                "-ar", "16000",
                "-ac", "1",
                outputFile.absolutePath
            )
        } else {
            args += listOf(
                "-c:a", "aac",
                "-b:a", profile.audioBitrate,
                "-ar", profile.audioSampleRate.toString(),
                "-ac", profile.audioChannels.toString(),
                "-movflags", "+faststart",
                outputFile.absolutePath
            )
        }
        return args.toTypedArray()
    }

    private fun executeFadeInOutReencodeJoin(
        inputs: List<File>,
        outputFile: File,
        taskLabel: String = "Aplicando Fade in/out"
    ): JoinExecutionResult {
        val session = executeFfmpegWithProgress(
            buildFadeInOutReencodeArguments(inputs, outputFile),
            totalDurationMs(),
            taskLabel,
            selectedVideoEncoder?.shortName
        )
        return JoinExecutionResult(
            success = ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0L,
            cancelled = ReturnCode.isCancel(session.returnCode),
            failureMessage = ffmpegFailureMessage(taskLabel, session)
        )
    }

    private fun buildDirectConcatArguments(inputs: List<File>, outputFile: File): Array<String> {
        val listFile = File(cacheDir, "join_list_${System.currentTimeMillis()}.txt")
        listFile.writeText(inputs.joinToString("\n") { "file '${it.absolutePath.replace("\\", "/")}'" }, Charsets.UTF_8)
        val args = mutableListOf(
            "-y",
            "-fflags", "+genpts",
            "-f", "concat",
            "-safe", "0",
            "-i", listFile.absolutePath,
            "-c", "copy"
        )
        if (!currentJoinIsAudio()) args += listOf("-movflags", "+faststart")
        args += outputFile.absolutePath
        return args.toTypedArray()
    }

    private fun executeSmartJoinExperiment(
        inputs: List<File>,
        outputFile: File,
        encoder: FfmpegVideoEncoder
    ): JoinExecutionResult {
        Log.d(TAG, "Executing SmartJoin Experiment branch with ${encoder.ffmpegName}")
        val workDir = File(cacheDir, "smartjoin_mkv_${System.currentTimeMillis()}").apply { mkdirs() }
        val pieces = mutableListOf<SmartJoinPiece>()
        val transitionSeconds = safeTransitionSeconds()
        val profile = detectOutputProfile(inputs.firstOrNull(), clips.firstOrNull(), encoder)

        return try {
            if (inputs.size != clips.size) {
                return JoinExecutionResult(false, false, "Quantidade de vídeos copiados não confere.")
            }

            for (index in inputs.indices) {
                val clip = clips[index]
                val clipSeconds = (clip.durationMs / 1000.0).coerceAtLeast(0.001)
                
                val bodyStart = if (index == 0) 0.0 else transitionSeconds
                val bodyEnd = if (index == inputs.lastIndex) clipSeconds else (clipSeconds - transitionSeconds)
                val bodyDuration = bodyEnd - bodyStart

                // 1. Copiar Corpo (TS)
                if (bodyDuration > 0.12) {
                    val bodyFile = File(workDir, "body_${index.toString().padStart(3, '0')}.ts")
                    val bodyLabel = "Copiando corpo ${index + 1}/${clips.size} (TS)"
                    
                    val args = mutableListOf("-y")
                    if (bodyStart > 0.001) args.addAll(listOf("-ss", formatDecimal(bodyStart)))
                    args.addAll(listOf("-i", inputs[index].absolutePath))
                    args.addAll(listOf("-t", formatDecimal(bodyDuration), "-map", "0:v:0"))
                    if (clip.hasAudio) args.addAll(listOf("-map", "0:a:0?"))
                    
                    args.addAll(listOf(
                        "-c:v", "copy",
                        "-bsf:v", profile.videoBitstreamFilter,
                        "-c:a", "aac",
                        "-b:a", profile.audioBitrate,
                        "-ar", profile.audioSampleRate.toString(),
                        "-ac", profile.audioChannels.toString(),
                        "-avoid_negative_ts", "make_zero",
                        "-mpegts_flags", "+resend_headers",
                        "-muxdelay", "0",
                        "-muxpreload", "0",
                        "-f", "mpegts",
                        bodyFile.absolutePath
                    ))

                    val bodySession = executeFfmpegWithProgress(
                        args.toTypedArray(),
                        (bodyDuration * 1000.0).toLong().coerceAtLeast(1L),
                        bodyLabel,
                        encoderName = if (clip.hasAudio) "aac" else null
                    )
                    if (ReturnCode.isCancel(bodySession.returnCode)) return JoinExecutionResult(false, true, "")
                    if (!ReturnCode.isSuccess(bodySession.returnCode) || !bodyFile.exists() || bodyFile.length() == 0L) {
                        return JoinExecutionResult(false, false, ffmpegFailureMessage(bodyLabel, bodySession))
                    }
                    pieces += SmartJoinPiece(bodyFile, bodyDuration)
                }

                // 2. Gerar Transição xfade (TS)
                if (index < inputs.lastIndex) {
                    val transitionFileTs = File(workDir, "transition_${index.toString().padStart(3, '0')}.ts")
                    val transitionLabel = "Gerando ${if (isFadeInOutTransition()) "fade" else "xfade"} ${index + 1}/${clips.size - 1} (TS)"
                    
                    val args = buildTransitionArgumentsMkv(
                        firstInput = inputs[index],
                        secondInput = inputs[index + 1],
                        firstClip = clips[index],
                        secondClip = clips[index + 1],
                        outputFile = transitionFileTs,
                        transitionSeconds = transitionSeconds,
                        profile = profile,
                        encoder = encoder
                    )

                    val transitionSession = executeFfmpegWithRetry(
                        arguments = args,
                        expectedDurationMs = (transitionSeconds * 1000.0).toLong().coerceAtLeast(1L),
                        taskLabel = transitionLabel,
                        outputFile = transitionFileTs,
                        encoderName = encoder.shortName
                    )
                    if (ReturnCode.isCancel(transitionSession.returnCode)) return JoinExecutionResult(false, true, "")
                    if (!ReturnCode.isSuccess(transitionSession.returnCode) || !transitionFileTs.exists() || transitionFileTs.length() == 0L) {
                        return JoinExecutionResult(false, false, ffmpegFailureMessage(transitionLabel, transitionSession))
                    }
                    
                    pieces += SmartJoinPiece(transitionFileTs, transitionSeconds)
                }
            }

            if (pieces.isEmpty()) return JoinExecutionResult(false, false, "Nenhum trecho gerado.")

            val listFile = File(workDir, "smart_join_list.txt")
            listFile.writeText(pieces.joinToString("\n") { "file '${it.file.absolutePath.replace("\\", "/")}'" }, Charsets.UTF_8)

            val concatArgs = mutableListOf(
                "-y", "-fflags", "+genpts", "-f", "concat", "-safe", "0",
                "-i", listFile.absolutePath,
                "-c", "copy",
                "-bsf:a", "aac_adtstoasc",
                "-avoid_negative_ts", "make_zero",
                "-max_interleave_delta", "0",
                "-use_editlist", "0",
                "-video_track_timescale", "90000",
                "-movflags", "+faststart"
            )
            if (profile.videoCodec == "hevc") {
                concatArgs.addAll(listOf("-tag:v", "hvc1"))
            }
            concatArgs.addAll(rotationMetadataArguments(profile.rotationDegrees))
            concatArgs.add(outputFile.absolutePath)

            val concatSession = executeFfmpegWithProgress(concatArgs.toTypedArray(), totalDurationMs(), "Juntando experimento (TS -> MP4)")
            
            val diagnosticFile = writeSmartJoinDiagnosticReport(pieces, outputFile, profile, concatSession)

            JoinExecutionResult(
                success = ReturnCode.isSuccess(concatSession.returnCode) && outputFile.exists() && outputFile.length() > 0L,
                cancelled = ReturnCode.isCancel(concatSession.returnCode),
                failureMessage = ffmpegFailureMessage("Juntando experimento (TS -> MP4)", concatSession),
                diagnosticFile = diagnosticFile
            )

        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun buildTransitionArgumentsMkv(
        firstInput: File,
        secondInput: File,
        firstClip: JoinClip,
        secondClip: JoinClip,
        outputFile: File,
        transitionSeconds: Double,
        profile: OutputProfile,
        encoder: FfmpegVideoEncoder
    ): Array<String> {
        val windowSeconds = transitionWindowSeconds(firstClip, secondClip, transitionSeconds)
        val firstStart = ((firstClip.durationMs / 1000.0) - windowSeconds).coerceAtLeast(0.0)
        val filter = buildTransitionFilter(firstClip, secondClip, profile, transitionSeconds, windowSeconds)
        val args = mutableListOf(
            "-y",
            "-fflags", "+genpts",
            "-noautorotate",
            "-ss", formatDecimal(firstStart),
            "-t", formatDecimal(windowSeconds),
            "-i", firstInput.absolutePath,
            "-noautorotate",
            "-ss", "0",
            "-t", formatDecimal(windowSeconds),
            "-i", secondInput.absolutePath,
            "-filter_complex", filter,
            "-map", "[vout]",
            "-map", "[aout]"
        )
        
        args.addAll(videoEncodingArguments(profile, constrained = true, encoderOverride = encoder))

        args.addAll(
            listOf(
                "-r", profile.fps,
                "-vsync", "cfr",
                "-g", fadeGopSize(profile.fps),
                "-bf", "0",
                "-c:a", "aac",
                "-b:a", profile.audioBitrate,
                "-ar", profile.audioSampleRate.toString(),
                "-ac", profile.audioChannels.toString(),
                "-avoid_negative_ts", "make_zero",
                "-mpegts_flags", "+resend_headers",
                "-muxdelay", "0",
                "-muxpreload", "0",
                "-f", "mpegts",
                outputFile.absolutePath
            )
        )
        return args.toTypedArray()
    }

    private fun executeMinimalTransitionJoin(inputs: List<File>, outputFile: File): JoinExecutionResult {
        val workDir = File(cacheDir, "join_minimal_${System.currentTimeMillis()}").apply { mkdirs() }
        val pieces = mutableListOf<SmartJoinPiece>()
        val transitionSeconds = safeTransitionSeconds()
        val profile = detectOutputProfile(inputs.firstOrNull(), clips.firstOrNull())

        return try {
            if (inputs.size != clips.size) {
                return JoinExecutionResult(false, false, "Quantidade de vídeos copiados não confere.")
            }

            if (transitionSeconds <= 0.0) {
                val taskLabel = "Juntando sem transição"
                configureFadeProcessingPlan(listOf(taskLabel))
                val session = executeFfmpegWithProgress(
                    buildDirectConcatArguments(inputs, outputFile),
                    totalDurationMs(),
                    taskLabel
                )
                return JoinExecutionResult(
                    success = ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0L,
                    cancelled = ReturnCode.isCancel(session.returnCode),
                    failureMessage = ffmpegFailureMessage(taskLabel, session)
                )
            }

            val keyframeTask = "Lendo keyframes dos vídeos"
            updateStep(keyframeTask, 0, StepState.RUNNING)
            val keyframesByInput = inputs.mapIndexed { index, input ->
                updateStep(keyframeTask, ((index * 100.0) / inputs.size.coerceAtLeast(1)).toInt(), StepState.RUNNING, "vídeo ${index + 1}/${inputs.size}")
                extractVideoKeyframesMs(input)
            }
            updateStep(keyframeTask, 100, StepState.DONE)

            val boundariesTask = "Calculando limites da transição"
            updateStep(boundariesTask, 0, StepState.RUNNING)
            val fadeOutStarts = inputs.indices.map { index ->
                if (index == inputs.lastIndex) {
                    (clips[index].durationMs / 1000.0).coerceAtLeast(0.001)
                } else {
                    previousKeyframeSeconds(
                        keyframesByInput[index],
                        (clips[index].durationMs / 1000.0) - transitionSeconds
                    )
                }
            }
            val fadeInEnds = inputs.indices.map { index ->
                if (index == 0) {
                    0.0
                } else {
                    nextKeyframeSeconds(
                        keyframesByInput[index],
                        transitionSeconds,
                        clips[index].durationMs / 1000.0
                    )
                }
            }
            updateStep(boundariesTask, 100, StepState.DONE)

            val preserveTask = "Verificando trechos preserváveis"
            updateStep(preserveTask, 0, StepState.RUNNING)
            val bodyDurations = inputs.indices.map { index ->
                val clipSeconds = (clips[index].durationMs / 1000.0).coerceAtLeast(0.001)
                (fadeOutStarts[index] - fadeInEnds[index]).coerceIn(0.0, clipSeconds)
            }
            // Segmentos muito curtos ainda podem ter timestamps/DTS instáveis ao alternar
            // entre stream copiada e stream reencodada, mesmo com keyframes internos.
            val containsShortClip = clips.any {
                (it.durationMs / 1000.0) <= FAST_FADE_MIN_CLIP_SECONDS
            }
            updateStep(preserveTask, 100, StepState.DONE)
            if (containsShortClip || bodyDurations.all { it <= 0.12 }) {
                val taskLabel = "Aplicando Fade in/out completo (vídeos curtos)"
                configureFadeProcessingPlan(listOf(taskLabel))
                return executeFadeInOutReencodeJoin(inputs, outputFile, taskLabel)
            }

            val planLabels = mutableListOf<String>()
            clips.forEachIndexed { index, clip ->
                planLabels += "Copiando trecho ${index + 1}/${clips.size}"
                if (index < clips.lastIndex) {
                    planLabels += "Gerando fade-out ${index + 1}/${clips.size - 1}"
                    planLabels += "Gerando fade-in ${index + 1}/${clips.size - 1}"
                }
            }
            planLabels += "Juntando trechos preservados"
            configureFadeProcessingPlan(planLabels)

            for (index in inputs.indices) {
                val clip = clips[index]
                val clipSeconds = (clip.durationMs / 1000.0).coerceAtLeast(0.001)
                val bodyStart = fadeInEnds[index].coerceIn(0.0, clipSeconds)
                val bodyEnd = fadeOutStarts[index].coerceIn(bodyStart, clipSeconds)
                val bodyDuration = bodyEnd - bodyStart

                if (bodyDuration > 0.12) {
                    val bodyFile = File(workDir, "body_${index.toString().padStart(3, '0')}.ts")
                    val bodyLabel = "Copiando trecho ${index + 1}/${clips.size}"
                    val bodySession = executeFfmpegWithRetry(
                        buildBodyCopyArguments(inputs[index], bodyFile, bodyStart, bodyDuration, clip, profile),
                        (bodyDuration * 1000.0).toLong().coerceAtLeast(1L),
                        bodyLabel,
                        bodyFile,
                        maxAttempts = 2,
                        encoderName = if (clip.hasAudio) "aac" else null
                    )
                    if (ReturnCode.isCancel(bodySession.returnCode)) {
                        return JoinExecutionResult(false, true, "")
                    }
                    if (!ReturnCode.isSuccess(bodySession.returnCode) || !bodyFile.exists() || bodyFile.length() == 0L) {
                        return JoinExecutionResult(
                            false,
                            false,
                            ffmpegFailureMessage(bodyLabel, bodySession)
                        )
                    }
                    pieces += SmartJoinPiece(bodyFile, bodyDuration)
                }

                if (index < inputs.lastIndex) {
                    val fadeOutFile = File(workDir, "fade_out_${index.toString().padStart(3, '0')}.ts")
                    val fadeOutLabel = "Gerando fade-out ${index + 1}/${clips.size - 1}"
                    val fadeOutStart = fadeOutStarts[index]
                    val fadeOutDuration = (clipSeconds - fadeOutStart).coerceAtLeast(0.001)
                    val fadeOutOffset = ((clipSeconds - transitionSeconds) - fadeOutStart).coerceAtLeast(0.0)
                    val fadeOutSession = executeFfmpegWithRetry(
                        buildFadeEdgeArguments(
                            inputFile = inputs[index],
                            outputFile = fadeOutFile,
                            startSeconds = fadeOutStart,
                            segmentDurationSeconds = fadeOutDuration,
                            fadeStartSeconds = fadeOutOffset,
                            fadeDurationSeconds = transitionSeconds,
                            clip = clips[index],
                            profile = profile,
                            fadeIn = false
                        ),
                        (fadeOutDuration * 1000.0).toLong().coerceAtLeast(1L),
                        fadeOutLabel,
                        fadeOutFile,
                        encoderName = selectedVideoEncoder?.shortName
                    )
                    if (ReturnCode.isCancel(fadeOutSession.returnCode)) {
                        return JoinExecutionResult(false, true, "")
                    }
                    if (!ReturnCode.isSuccess(fadeOutSession.returnCode) || !fadeOutFile.exists() || fadeOutFile.length() == 0L) {
                        return JoinExecutionResult(
                            false,
                            false,
                            ffmpegFailureMessage(fadeOutLabel, fadeOutSession)
                        )
                    }
                    pieces += SmartJoinPiece(fadeOutFile, fadeOutDuration)

                    val fadeInFile = File(workDir, "fade_in_${index.toString().padStart(3, '0')}.ts")
                    val fadeInLabel = "Gerando fade-in ${index + 1}/${clips.size - 1}"
                    val fadeInDuration = fadeInEnds[index + 1].coerceAtLeast(0.001)
                    val fadeInSession = executeFfmpegWithRetry(
                        buildFadeEdgeArguments(
                            inputFile = inputs[index + 1],
                            outputFile = fadeInFile,
                            startSeconds = 0.0,
                            segmentDurationSeconds = fadeInDuration,
                            fadeStartSeconds = 0.0,
                            fadeDurationSeconds = transitionSeconds,
                            clip = clips[index + 1],
                            profile = profile,
                            fadeIn = true
                        ),
                        (fadeInDuration * 1000.0).toLong().coerceAtLeast(1L),
                        fadeInLabel,
                        fadeInFile,
                        encoderName = selectedVideoEncoder?.shortName
                    )
                    if (ReturnCode.isCancel(fadeInSession.returnCode)) {
                        return JoinExecutionResult(false, true, "")
                    }
                    if (!ReturnCode.isSuccess(fadeInSession.returnCode) || !fadeInFile.exists() || fadeInFile.length() == 0L) {
                        return JoinExecutionResult(
                            false,
                            false,
                            ffmpegFailureMessage(fadeInLabel, fadeInSession)
                        )
                    }
                    pieces += SmartJoinPiece(fadeInFile, fadeInDuration)
                }
            }

            if (pieces.isEmpty()) {
                return JoinExecutionResult(false, false, "Nenhum trecho foi gerado.")
            }

            val concatSession = executeFfmpegWithProgress(
                buildTransportStreamConcatArguments(pieces, outputFile, profile),
                totalDurationMs(),
                "Juntando trechos preservados"
            )
            val diagnosticFile = writeSmartJoinDiagnosticReport(
                pieces = pieces,
                outputFile = outputFile,
                profile = profile,
                concatSession = concatSession
            )
            JoinExecutionResult(
                success = ReturnCode.isSuccess(concatSession.returnCode) && outputFile.exists() && outputFile.length() > 0L,
                cancelled = ReturnCode.isCancel(concatSession.returnCode),
                failureMessage = ffmpegFailureMessage("Juntando trechos preservados", concatSession),
                diagnosticFile = diagnosticFile
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun writeSmartJoinDiagnosticReport(
        pieces: List<SmartJoinPiece>,
        outputFile: File,
        profile: OutputProfile,
        concatSession: FFmpegSession
    ): File {
        val report = File(cacheDir, "smart_join_diagnostico_${System.currentTimeMillis()}.txt")
        val builder = StringBuilder()
        builder.appendLine("Diagnostico Fade in/out")
        builder.appendLine("Gerado em: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(java.util.Date())}")
        builder.appendLine("Transicao: $selectedTransition")
        builder.appendLine("Tempo de transicao: ${formatDecimal(safeTransitionSeconds())}s")
        builder.appendLine()
        builder.appendLine("Perfil alvo detectado")
        builder.appendLine("videoCodec=${profile.videoCodec}")
        builder.appendLine("videoEncoder=${profile.videoEncoder}")
        builder.appendLine("videoBitstreamFilter=${profile.videoBitstreamFilter}")
        builder.appendLine("videoBitrate=${profile.videoBitrate}")
        builder.appendLine("videoBufferSize=${profile.videoBufferSize}")
        builder.appendLine("width=${profile.width}")
        builder.appendLine("height=${profile.height}")
        builder.appendLine("fps=${profile.fps}")
        builder.appendLine("rotationDegrees=${profile.rotationDegrees}")
        builder.appendLine("audioSampleRate=${profile.audioSampleRate}")
        builder.appendLine("audioChannels=${profile.audioChannels}")
        builder.appendLine("audioLayout=${profile.audioLayout}")
        builder.appendLine("audioBitrate=${profile.audioBitrate}")
        builder.appendLine()
        builder.appendLine("Arquivos de entrada")
        clips.forEachIndexed { index, clip ->
            builder.appendLine("[$index] ${clip.name}")
            builder.appendLine("durationMs=${clip.durationMs}")
            builder.appendLine("width=${clip.width}")
            builder.appendLine("height=${clip.height}")
            builder.appendLine("rotationDegrees=${clip.rotationDegrees}")
            builder.appendLine("hasAudio=${clip.hasAudio}")
            builder.appendLine()
        }
        builder.appendLine("Pecas temporarias")
        pieces.forEachIndexed { index, piece ->
            builder.appendLine("===== PECA $index =====")
            builder.appendLine("arquivo=${piece.file.name}")
            builder.appendLine("caminho=${piece.file.absolutePath}")
            val pieceType = when {
                piece.file.name.startsWith("transition_") -> "transicao"
                piece.file.name.startsWith("fade_out_") -> "fade-out"
                piece.file.name.startsWith("fade_in_") -> "fade-in"
                else -> "trecho copiado"
            }
            builder.appendLine("tipo=$pieceType")
            builder.appendLine("duracaoEsperada=${formatDecimal(piece.durationSeconds)}s")
            builder.appendLine("tamanhoBytes=${piece.file.length()}")
            builder.appendLine(analyzeMediaFile(piece.file))
            builder.appendLine()
        }
        builder.appendLine("===== ARQUIVO FINAL =====")
        builder.appendLine("arquivo=${outputFile.name}")
        builder.appendLine("caminho=${outputFile.absolutePath}")
        builder.appendLine("tamanhoBytes=${outputFile.length()}")
        builder.appendLine(analyzeMediaFile(outputFile))
        builder.appendLine()
        builder.appendLine("===== LOGS DO CONCAT FINAL =====")
        builder.appendLine(concatSession.allLogsAsString.orEmpty())
        report.writeText(builder.toString(), Charsets.UTF_8)
        return report
    }

    private fun analyzeMediaFile(file: File): String {
        if (!file.exists()) return "Arquivo nao existe."
        return try {
            val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-i", file.absolutePath))
            session.allLogsAsString.orEmpty().ifBlank { "Sem logs retornados pelo FFmpeg." }
        } catch (e: Throwable) {
            "Falha ao analisar arquivo: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun buildTransportStreamConcatArguments(inputs: List<SmartJoinPiece>, outputFile: File, profile: OutputProfile): Array<String> {
        val listFile = File(outputFile.parentFile ?: cacheDir, "smart_join_list_${System.nanoTime()}.txt")
        listFile.writeText(
            inputs.joinToString("\n") { "file '${it.file.absolutePath.replace("\\", "/")}'" },
            Charsets.UTF_8
        )
        val args = mutableListOf(
            "-y",
            "-fflags", "+genpts",
            "-f", "concat",
            "-safe", "0",
            "-i", listFile.absolutePath,
            "-c", "copy",
            "-bsf:a", "aac_adtstoasc",
            "-avoid_negative_ts", "make_zero",
            "-max_interleave_delta", "0",
            "-use_editlist", "0",
            "-video_track_timescale", "90000",
            "-movflags", "+faststart"
        )
        args.addAll(rotationMetadataArguments(profile.rotationDegrees))
        args.add(outputFile.absolutePath)
        return args.toTypedArray()
    }

    private fun buildBodyCopyArguments(
        inputFile: File,
        outputFile: File,
        startSeconds: Double,
        durationSeconds: Double,
        clip: JoinClip,
        profile: OutputProfile
    ): Array<String> {
        val args = mutableListOf("-y", "-fflags", "+genpts")
        if (startSeconds > 0.001) {
            args.addAll(listOf("-ss", formatDecimal(startSeconds)))
        }
        args.addAll(listOf("-i", inputFile.absolutePath))
        args.addAll(listOf("-t", formatDecimal(durationSeconds), "-map", "0:v:0"))
        if (clip.hasAudio) {
            args.addAll(listOf("-map", "0:a:0?"))
        }
        args.addAll(
            listOf(
                "-c:v", "copy",
                "-bsf:v", profile.videoBitstreamFilter,
                "-c:a", "aac",
                "-b:a", profile.audioBitrate,
                "-ar", profile.audioSampleRate.toString(),
                "-ac", profile.audioChannels.toString(),
                "-avoid_negative_ts", "make_zero",
                "-mpegts_flags", "+resend_headers",
                "-muxdelay", "0",
                "-muxpreload", "0",
                "-f", "mpegts",
                outputFile.absolutePath
            )
        )
        return args.toTypedArray()
    }

    private fun buildFadeEdgeArguments(
        inputFile: File,
        outputFile: File,
        startSeconds: Double,
        segmentDurationSeconds: Double,
        fadeStartSeconds: Double,
        fadeDurationSeconds: Double,
        clip: JoinClip,
        profile: OutputProfile,
        fadeIn: Boolean
    ): Array<String> {
        val fadeType = if (fadeIn) "in" else "out"
        val videoFilter = "[0:v]${videoNormalizeFilter(profile)}," +
            "fade=t=$fadeType:st=${formatDecimal(fadeStartSeconds)}:d=${formatDecimal(fadeDurationSeconds)}," +
            "settb=AVTB,setpts=PTS-STARTPTS[vout]"
        val audioFilter = if (clip.hasAudio) {
            "[0:a]${audioNormalizeFilter(profile)}," +
                "afade=t=$fadeType:st=${formatDecimal(fadeStartSeconds)}:d=${formatDecimal(fadeDurationSeconds)}," +
                "aformat=sample_fmts=fltp:sample_rates=${profile.audioSampleRate}:channel_layouts=${profile.audioLayout}," +
                "asetpts=N/SR/TB[aout]"
        } else {
            "anullsrc=channel_layout=${profile.audioLayout}:sample_rate=${profile.audioSampleRate}," +
                "atrim=0:${formatDecimal(segmentDurationSeconds)},asetpts=N/SR/TB[aout]"
        }
        val args = mutableListOf(
            "-y",
            "-fflags", "+genpts",
            "-noautorotate"
        )
        if (startSeconds > 0.001) {
            args.addAll(listOf("-ss", formatDecimal(startSeconds)))
        }
        args.addAll(
            listOf(
                "-i", inputFile.absolutePath,
                "-t", formatDecimal(segmentDurationSeconds),
                "-filter_complex", "$videoFilter;$audioFilter",
                "-map", "[vout]",
                "-map", "[aout]"
            )
        )
        
        args.addAll(videoEncodingArguments(profile, constrained = true))

        args.addAll(
            listOf(
                "-bsf:v", profile.videoBitstreamFilter,
                "-r", profile.fps,
                "-vsync", "cfr",
                "-g", fadeGopSize(profile.fps),
                "-bf", "0",
                "-force_key_frames", "0",
                "-c:a", "aac",
                "-b:a", profile.audioBitrate,
                "-ar", profile.audioSampleRate.toString(),
                "-ac", profile.audioChannels.toString(),
                "-avoid_negative_ts", "make_zero",
                "-mpegts_flags", "+resend_headers",
                "-muxdelay", "0",
                "-muxpreload", "0",
                "-f", "mpegts",
                outputFile.absolutePath
            )
        )
        return args.toTypedArray()
    }

    private fun buildTransitionFilter(
        firstClip: JoinClip,
        secondClip: JoinClip,
        profile: OutputProfile,
        transitionSeconds: Double,
        windowSeconds: Double
    ): String {
        val video0 = "[0:v]${videoNormalizeFilter(profile)},settb=AVTB,setpts=PTS-STARTPTS[v0]"
        val video1 = "[1:v]${videoNormalizeFilter(profile)},settb=AVTB,setpts=PTS-STARTPTS[v1]"
        val transitionOffset = (windowSeconds - transitionSeconds).coerceAtLeast(0.0)
        val transitionEnd = transitionOffset + transitionSeconds
        val audio0 = if (firstClip.hasAudio) {
            "[0:a]${audioNormalizeFilter(profile)},atrim=start=${formatDecimal(transitionOffset)}:end=${formatDecimal(transitionEnd)},asetpts=PTS-STARTPTS[a0]"
        } else {
            "anullsrc=channel_layout=${profile.audioLayout}:sample_rate=${profile.audioSampleRate},atrim=0:${formatDecimal(transitionSeconds)},asetpts=N/SR/TB[a0]"
        }
        val audio1 = if (secondClip.hasAudio) {
            "[1:a]${audioNormalizeFilter(profile)},atrim=start=0:end=${formatDecimal(transitionSeconds)},asetpts=PTS-STARTPTS[a1]"
        } else {
            "anullsrc=channel_layout=${profile.audioLayout}:sample_rate=${profile.audioSampleRate},atrim=0:${formatDecimal(transitionSeconds)},asetpts=N/SR/TB[a1]"
        }
        val xfade = "[v0][v1]xfade=transition=${xfadeTransitionName()}:duration=${formatDecimal(transitionSeconds)}:offset=${formatDecimal(transitionOffset)},trim=start=${formatDecimal(transitionOffset)}:end=${formatDecimal(transitionEnd)},fps=${profile.fps},setparams=range=tv:color_primaries=bt709:color_trc=bt709:colorspace=bt709,settb=AVTB,setpts=N/(${profile.fps}*TB)[vout]"
        val acrossfade = "[a0]afade=t=out:st=0:d=${formatDecimal(transitionSeconds)}[a0f];" +
            "[a1]afade=t=in:st=0:d=${formatDecimal(transitionSeconds)}[a1f];" +
            "[a0f][a1f]amix=inputs=2:duration=first,volume=2.0,aformat=sample_fmts=fltp:sample_rates=${profile.audioSampleRate}:channel_layouts=${profile.audioLayout},asetpts=N/SR/TB[aout]"
        return listOf(video0, video1, audio0, audio1, xfade, acrossfade).joinToString(";")
    }

    private fun videoNormalizeFilter(profile: OutputProfile): String {
        return "scale=${profile.width}:${profile.height}:force_original_aspect_ratio=decrease," +
            "pad=${profile.width}:${profile.height}:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=${profile.fps},format=yuv420p," +
            "setparams=range=tv:color_primaries=bt709:color_trc=bt709:colorspace=bt709"
    }

    private fun audioNormalizeFilter(profile: OutputProfile): String {
        return "aresample=${profile.audioSampleRate},aformat=sample_fmts=fltp:sample_rates=${profile.audioSampleRate}:channel_layouts=${profile.audioLayout}"
    }

    private fun fadeGopSize(fps: String): String {
        val fpsValue = fps.toDoubleOrNull()?.coerceIn(1.0, 240.0) ?: 30.0
        return kotlin.math.round(fpsValue).toInt().coerceAtLeast(1).toString()
    }

    private fun buildReencodeArguments(inputs: List<File>, outputFile: File, withTransition: Boolean): Array<String> {
        val outputProfile = displayOrientedReencodeProfile(detectOutputProfile(inputs.firstOrNull(), clips.firstOrNull()))
        val transitionSeconds = if (withTransition) safeTransitionSeconds() else 0.0
        val filter = buildFilterComplex(outputProfile, transitionSeconds)
        val args = mutableListOf("-y")
        inputs.forEach { input -> args.addAll(listOf("-i", input.absolutePath)) }
        args.addAll(listOf("-filter_complex", filter, "-map", "[vout]", "-map", "[aout]"))
        args.addAll(videoEncodingArguments(outputProfile, constrained = true))
        args.addAll(listOf("-r", outputProfile.fps, "-c:a", "aac", "-b:a", outputProfile.audioBitrate, "-ar", outputProfile.audioSampleRate.toString(), "-ac", outputProfile.audioChannels.toString(), "-movflags", "+faststart", outputFile.absolutePath))
        return args.toTypedArray()
    }

    private fun buildFadeInOutReencodeArguments(inputs: List<File>, outputFile: File): Array<String> {
        val outputProfile = displayOrientedReencodeProfile(detectOutputProfile(inputs.firstOrNull(), clips.firstOrNull()))
        val transitionSeconds = safeTransitionSeconds()
        val filter = buildFadeInOutFilterComplex(outputProfile, transitionSeconds)
        val args = mutableListOf("-y")
        inputs.forEach { input -> args.addAll(listOf("-i", input.absolutePath)) }
        args.addAll(listOf("-filter_complex", filter, "-map", "[vout]", "-map", "[aout]"))
        args.addAll(videoEncodingArguments(outputProfile, constrained = true))
        args.addAll(listOf("-r", outputProfile.fps, "-c:a", "aac", "-b:a", outputProfile.audioBitrate, "-ar", outputProfile.audioSampleRate.toString(), "-ac", outputProfile.audioChannels.toString(), "-movflags", "+faststart", outputFile.absolutePath))
        return args.toTypedArray()
    }

    private fun buildFadeInOutFilterComplex(profile: OutputProfile, transitionSeconds: Double): String {
        val targetWidth = profile.width
        val targetHeight = profile.height
        val parts = mutableListOf<String>()
        clips.forEachIndexed { index, clip ->
            val clipSeconds = (clip.durationMs / 1000.0).coerceAtLeast(0.001)
            val fadeDuration = transitionSeconds.coerceAtMost((clipSeconds / 2.0).coerceAtLeast(0.1))
            val fadeOutStart = (clipSeconds - fadeDuration).coerceAtLeast(0.0)
            val videoFades = mutableListOf<String>()
            if (index > 0) videoFades += "fade=t=in:st=0:d=${formatDecimal(fadeDuration)}"
            if (index < clips.lastIndex) videoFades += "fade=t=out:st=${formatDecimal(fadeOutStart)}:d=${formatDecimal(fadeDuration)}"
            val videoFilter = buildString {
                append("[$index:v]")
                append(videoFillFrameFilter(targetWidth, targetHeight, profile.fps))
                videoFades.forEach { append(',').append(it) }
                append(",setpts=PTS-STARTPTS[v$index]")
            }
            parts += videoFilter

            val audioFadeFilters = mutableListOf<String>()
            if (index > 0) audioFadeFilters += "afade=t=in:st=0:d=${formatDecimal(fadeDuration)}"
            if (index < clips.lastIndex) audioFadeFilters += "afade=t=out:st=${formatDecimal(fadeOutStart)}:d=${formatDecimal(fadeDuration)}"
            val audioFilter = if (clip.hasAudio) {
                buildString {
                    append("[$index:a]")
                    append("aresample=${profile.audioSampleRate},")
                    append("aformat=sample_fmts=fltp:sample_rates=${profile.audioSampleRate}:channel_layouts=${profile.audioLayout}")
                    audioFadeFilters.forEach { append(',').append(it) }
                    append(",asetpts=PTS-STARTPTS[a$index]")
                }
            } else {
                buildString {
                    append("anullsrc=channel_layout=${profile.audioLayout}:sample_rate=${profile.audioSampleRate},")
                    append("atrim=0:${formatDecimal(clipSeconds)}")
                    audioFadeFilters.forEach { append(',').append(it) }
                    append(",asetpts=N/SR/TB[a$index]")
                }
            }
            parts += audioFilter
        }
        val concatInputs = clips.indices.joinToString("") { "[v$it][a$it]" }
        parts += "${concatInputs}concat=n=${clips.size}:v=1:a=1[vout][aout]"
        return parts.joinToString(";")
    }

    private fun buildFilterComplex(profile: OutputProfile, transitionSeconds: Double): String {
        val targetWidth = profile.width
        val targetHeight = profile.height
        val parts = mutableListOf<String>()
        clips.forEachIndexed { index, clip ->
            parts += "[$index:v]${videoFillFrameFilter(targetWidth, targetHeight, profile.fps)}[v$index]"
            parts += if (clip.hasAudio) {
                "[$index:a]aresample=${profile.audioSampleRate},aformat=sample_fmts=fltp:sample_rates=${profile.audioSampleRate}:channel_layouts=${profile.audioLayout}[a$index]"
            } else {
                "anullsrc=channel_layout=${profile.audioLayout}:sample_rate=${profile.audioSampleRate},atrim=0:${clip.durationMs / 1000.0},asetpts=N/SR/TB[a$index]"
            }
        }

        if (transitionSeconds > 0.0 && clips.size > 1) {
            var lastV = "v0"
            var lastA = "a0"
            var accumulatedSeconds = clips.first().durationMs / 1000.0
            for (index in 1 until clips.size) {
                val videoOut = "vx$index"
                val audioOut = "ax$index"
                val offset = (accumulatedSeconds - transitionSeconds).coerceAtLeast(0.0)
                parts += "[$lastV][v$index]xfade=transition=${xfadeTransitionName()}:duration=${formatDecimal(transitionSeconds)}:offset=${formatDecimal(offset)}[$videoOut]"
                parts += "[$lastA][a$index]acrossfade=d=${formatDecimal(transitionSeconds)}[$audioOut]"
                lastV = videoOut
                lastA = audioOut
                accumulatedSeconds += clips[index].durationMs / 1000.0 - transitionSeconds
            }
            parts += "[$lastV]copy[vout]"
            parts += "[$lastA]acopy[aout]"
        } else {
            val concatInputs = clips.indices.joinToString("") { "[v$it][a$it]" }
            parts += "${concatInputs}concat=n=${clips.size}:v=1:a=1[vout][aout]"
        }
        return parts.joinToString(";")
    }

    private fun videoFillFrameFilter(width: Int, height: Int, fps: String): String {
        return "scale=$width:$height:force_original_aspect_ratio=increase," +
            "crop=$width:$height,setsar=1,fps=$fps,format=yuv420p"
    }

    private fun safeTransitionSeconds(): Double {
        val requested = inputTransitionTime.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.5
        val shortest = clips.minOfOrNull { it.durationMs / 1000.0 } ?: 0.0
        return requested.coerceIn(0.0, (shortest - 0.1).coerceAtLeast(0.0))
    }

    private fun extractVideoKeyframesMs(inputFile: File): List<Long> {
        val extractor = MediaExtractor()
        val keyframes = mutableListOf<Long>()
        try {
            extractor.setDataSource(inputFile.absolutePath)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return listOf(0L)
            extractor.selectTrack(videoTrack)
            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0L) break
                if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    keyframes += sampleTime / 1000L
                }
                extractor.advance()
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Não foi possível extrair keyframes de ${inputFile.name}", error)
        } finally {
            extractor.release()
        }
        return (keyframes + 0L).distinct().sorted()
    }

    private fun previousKeyframeSeconds(keyframesMs: List<Long>, targetSeconds: Double): Double {
        val targetMs = (targetSeconds.coerceAtLeast(0.0) * 1000.0).toLong()
        return (keyframesMs.lastOrNull { it <= targetMs } ?: 0L) / 1000.0
    }

    private fun nextKeyframeSeconds(keyframesMs: List<Long>, targetSeconds: Double, clipSeconds: Double): Double {
        val targetMs = (targetSeconds.coerceAtLeast(0.0) * 1000.0).toLong()
        return ((keyframesMs.firstOrNull { it >= targetMs } ?: (clipSeconds * 1000.0).toLong()) / 1000.0)
            .coerceIn(0.0, clipSeconds)
    }

    private fun transitionWindowSeconds(firstClip: JoinClip, secondClip: JoinClip, transitionSeconds: Double): Double {
        val firstSeconds = (firstClip.durationMs / 1000.0).coerceAtLeast(transitionSeconds)
        val secondSeconds = (secondClip.durationMs / 1000.0).coerceAtLeast(transitionSeconds)
        val maxWindow = minOf(firstSeconds, secondSeconds)
        return (transitionSeconds * 2.0).coerceAtMost(maxWindow).coerceAtLeast(transitionSeconds)
    }

    private fun executeFfmpegWithProgress(
        arguments: Array<String>,
        expectedDurationMs: Long,
        taskLabel: String,
        encoderName: String? = null
    ): FFmpegSession {
        val latch = CountDownLatch(1)
        val sessionRef = AtomicReference<FFmpegSession>()
        val safeDuration = expectedDurationMs.coerceAtLeast(1L)
        val startedAt = SystemClock.elapsedRealtime()
        updateStep(taskLabel, 0, StepState.RUNNING, encoderName = encoderName)
        val session = FFmpegKit.executeWithArgumentsAsync(
            arguments,
            { session ->
                sessionRef.set(session)
                latch.countDown()
            },
            { },
            { statistics ->
                val percent = ((statistics.time / safeDuration.toDouble()) * 100.0)
                    .toInt()
                    .coerceIn(0, 99)
                runOnUiThread {
                    if (progress.visibility == View.VISIBLE) {
                        updateStep(
                            taskLabel,
                            percent,
                            StepState.RUNNING,
                            formatEfficiency(statistics.time, startedAt),
                            encoderName = encoderName
                        )
                    }
                }
            }
        )
        currentSessionId = session.sessionId
        latch.await()
        currentSessionId = null
        val completedSession = sessionRef.get() ?: session
        when {
            ReturnCode.isSuccess(completedSession.returnCode) -> updateStep(
                taskLabel,
                100,
                StepState.DONE,
                encoderName = encoderName,
                elapsedMs = SystemClock.elapsedRealtime() - startedAt
            )
            ReturnCode.isCancel(completedSession.returnCode) -> updateStep(taskLabel, null, StepState.ERROR, "cancelado")
            else -> updateStep(taskLabel, null, StepState.ERROR)
        }
        return completedSession
    }

    private fun executeFfmpegWithRetry(
        arguments: Array<String>,
        expectedDurationMs: Long,
        taskLabel: String,
        outputFile: File,
        maxAttempts: Int = 3,
        encoderName: String? = null
    ): FFmpegSession {
        var lastSession: FFmpegSession? = null
        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            if (attempt > 0) {
                outputFile.delete()
                updateStep(
                    taskLabel,
                    0,
                    StepState.RUNNING,
                    "tentativa ${attempt + 1}/$maxAttempts",
                    encoderName = encoderName
                )
                Thread.sleep(180L)
            }
            val session = executeFfmpegWithProgress(arguments, expectedDurationMs, taskLabel, encoderName)
            lastSession = session
            if (ReturnCode.isCancel(session.returnCode)) return session
            if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0L) {
                return session
            }
        }
        return lastSession ?: executeFfmpegWithProgress(arguments, expectedDurationMs, taskLabel, encoderName)
    }

    private fun formatEfficiency(processedMs: Double, startedAtMs: Long): String {
        val elapsedSeconds = ((SystemClock.elapsedRealtime() - startedAtMs) / 1000.0).coerceAtLeast(0.001)
        val processedSeconds = (processedMs.coerceAtLeast(0.0) / 1000.0)
        val efficiency = processedSeconds / elapsedSeconds
        return String.format(Locale.US, "%.2fx", efficiency)
    }

    private fun ffmpegFailureMessage(step: String, session: FFmpegSession): String {
        val lines = session.allLogsAsString
            .orEmpty()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val important = lines.filter { line ->
            line.contains("error", ignoreCase = true) ||
                line.contains("failed", ignoreCase = true) ||
                line.contains("invalid", ignoreCase = true) ||
                line.contains("not supported", ignoreCase = true) ||
                line.contains("Conversion failed", ignoreCase = true)
        }
        val details = (important.takeLast(6) + lines.takeLast(10))
            .distinct()
            .joinToString(" ")
            .take(420)
        return if (details.isBlank()) step else "$step: $details"
    }

    private fun setProcessing(processing: Boolean) {
        isProcessing = processing
        progress.visibility = if (processing) View.VISIBLE else View.GONE
        if (processing) {
            buttonJoin.setImageResource(R.drawable.ic_ffmpeg_cancel_red)
            buttonJoin.setBackgroundResource(R.drawable.ffmpeg_outline_red_button_bg)
            buttonJoin.contentDescription = "Cancelar"
            buttonJoin.alpha = 1f
            buttonJoin.isClickable = true
            buttonJoin.isFocusable = true
        } else {
            currentSessionId = null
            buttonJoin.setImageResource(R.drawable.ic_ffmpeg_join_videos)
            buttonJoin.setBackgroundResource(R.drawable.ffmpeg_outline_green_button_bg)
            buttonJoin.contentDescription = "Juntar áudios ou vídeos"
            setJoinEnabled(clips.size >= 2)
        }
        updateReencodeControls()
    }

    private fun cancelJoin() {
        failActiveStep("Cancelando...")
        currentSessionId?.let { FFmpegKit.cancel(it) } ?: FFmpegKit.cancel()
    }

    private fun openOutputFolderPicker(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, requestCode)
    }

    private fun takeTreePermission(uri: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
        }
    }

    private fun saveTempOutputsToUri(treeUri: Uri) {
        val destDir = DocumentFile.fromTreeUri(this, treeUri)
        if (destDir == null || !destDir.isDirectory) {
            status.text = "Erro: pasta de destino inválida."
            return
        }

        val tempFile = tempOutputFiles.firstOrNull { it.exists() } ?: run {
            status.text = "Nenhum arquivo para salvar."
            return
        }
        try {
            val outputName = lastOutputName.ifBlank { tempFile.name }
            val outputMime = if (currentJoinIsAudio()) audioMimeType(outputName) else "video/mp4"
            val document = destDir.createFile(outputMime, outputName)
            if (document == null) {
                status.text = "Erro ao criar arquivo na pasta selecionada."
                return
            }
            contentResolver.openOutputStream(document.uri)?.use { output ->
                tempFile.inputStream().use { input -> input.copyTo(output) }
            }
            tempSmartJoinDiagnosticFile?.takeIf { it.exists() }?.let { diagnostic ->
                val diagnosticName = outputName.substringBeforeLast('.', outputName) + "_diagnostico_smartjoin.txt"
                destDir.createFile("text/plain", diagnosticName)?.let { diagnosticDocument ->
                    contentResolver.openOutputStream(diagnosticDocument.uri)?.use { output ->
                        diagnostic.inputStream().use { input -> input.copyTo(output) }
                    }
                }
            }
            finalOutputDirUri = treeUri
            lastOutputUri = document.uri
            lastOutputName = document.name ?: outputName
            
            // Preserva o texto de estatísticas/progresso e adiciona a mensagem de salvamento
            val currentStatusText = status.text
            val savedMsg = "\n\nArquivo salvo na pasta \"${destDir.name ?: "Selecionada"}\""
            if (currentStatusText.isNullOrBlank()) {
                status.text = savedMsg.trim()
            } else {
                status.append(savedMsg)
            }
            
            outputFileName.text = lastOutputName
            outputFileName.visibility = View.VISIBLE
            buttonSaveToFolder.visibility = View.GONE
            buttonOutputFolder.visibility = View.VISIBLE
            buttonOutputShare.visibility = View.VISIBLE
            joinScroll.post { joinScroll.smoothScrollTo(0, outputFileName.bottom) }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save joined video", e)
            status.text = "Erro ao salvar o arquivo."
        }
    }

    private fun openOutputFile() {
        val uri = lastOutputUri ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, if (currentJoinIsAudio()) audioMimeType(lastOutputName) else "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Não encontrei um app para abrir a mídia.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openOutputFolder() {
        val uri = finalOutputDirUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "vnd.android.document/directory")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Throwable) {
            try {
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            } catch (_: Throwable) {
                Toast.makeText(this, "Não consegui abrir a pasta.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareOutputFile() {
        val uri = lastOutputUri ?: return
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = if (currentJoinIsAudio()) audioMimeType(lastOutputName) else "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Compartilhar arquivo"))
        } catch (_: Throwable) {
            Toast.makeText(this, "Não consegui compartilhar o arquivo.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showJoinedPreview(file: File) {
        stopJoinPlayback()
        pendingResultPreviewFile = file
        timelineScroll.visibility = View.GONE
        joinPlaybackContainer.visibility = View.GONE
        resultPreviewContainer.visibility = View.VISIBLE
        resultVideoPreview.visibility = if (currentJoinIsAudio()) View.GONE else View.VISIBLE
        resultCurrentTime.text = formatTime(0L)
        resultTimeline.isEnabled = false
        resultTimeline.setRange(1L, 0L, 1L)
        resultTimeline.setCurrent(0L)
        updateResultPlaybackButton(false)
        updateResultSpeedButtons()
        if (currentJoinIsAudio()) {
            prepareJoinedPreview(file)
        } else if (resultVideoPreview.isAvailable) {
            resultPreviewSurface?.release()
            resultPreviewSurface = Surface(resultVideoPreview.surfaceTexture)
            prepareJoinedPreview(file)
        }
    }

    private fun toggleJoinPlayback() {
        if (clips.isEmpty() || isProcessing) return
        val player = joinPreviewPlayer
        if (player?.isPlaying == true) {
            joinPreviewPositionMs = currentJoinPlaybackPosition()
            player.pause()
            handler.removeCallbacks(joinPreviewTicker)
            updateJoinPlaybackButton(false)
            return
        }

        if (joinPreviewPositionMs >= totalDurationMs()) joinPreviewPositionMs = 0L
        if (player != null && joinPreviewPrepared && joinPreviewClipIndex >= 0) {
            applyJoinPlaybackSpeed(player)
            player.start()
            updateJoinPlaybackButton(true)
            handler.removeCallbacks(joinPreviewTicker)
            handler.post(joinPreviewTicker)
        } else {
            startJoinPlaybackAt(joinPreviewPositionMs)
        }
    }

    private fun startJoinPlaybackAt(positionMs: Long) {
        if (clips.isEmpty()) return
        joinPreviewPositionMs = positionMs.coerceIn(0L, totalDurationMs())
        val index = clips.indices.firstOrNull { joinPreviewPositionMs < joinClipOffset(it) + clips[it].durationMs }
            ?: clips.lastIndex
        val localPosition = (joinPreviewPositionMs - joinClipOffset(index)).coerceAtLeast(0L)
        prepareJoinPlayback(index, localPosition, true)
    }

    private fun prepareJoinPlayback(index: Int, localPositionMs: Long, startWhenPrepared: Boolean) {
        val clip = clips.getOrNull(index) ?: return
        releaseJoinPreviewPlayer()
        joinPreviewClipIndex = index
        joinPreviewPositionMs = (joinClipOffset(index) + localPositionMs).coerceIn(0L, totalDurationMs())
        val player = MediaPlayer()
        joinPreviewPlayer = player
        try {
            player.setDataSource(this, clip.uri)
            player.setOnPreparedListener { prepared ->
                joinPreviewPrepared = true
                seekMediaPlayer(prepared, localPositionMs)
                applyJoinPlaybackSpeed(prepared)
                if (startWhenPrepared) {
                    prepared.start()
                    updateJoinPlaybackButton(true)
                    handler.removeCallbacks(joinPreviewTicker)
                    handler.post(joinPreviewTicker)
                } else {
                    updateJoinPlaybackButton(false)
                }
            }
            player.setOnCompletionListener {
                if (index < clips.lastIndex) {
                    startJoinPlaybackAt(joinClipOffset(index + 1))
                } else {
                    joinPreviewPositionMs = totalDurationMs()
                    joinPlaybackTimeline.setCurrent(joinPreviewPositionMs)
                    joinCurrentTime.text = formatTime(joinPreviewPositionMs)
                    releaseJoinPreviewPlayer()
                    updateJoinPlaybackButton(false)
                }
            }
            player.setOnErrorListener { _, _, _ ->
                status.text = "Não consegui reproduzir ${clip.name}."
                status.setTextColor(Color.parseColor("#FFFF5A5A"))
                stopJoinPlayback()
                true
            }
            player.prepareAsync()
        } catch (error: Throwable) {
            Log.w(TAG, "Não foi possível preparar a prévia de ${clip.name}", error)
            status.text = "Não consegui reproduzir ${clip.name}."
            status.setTextColor(Color.parseColor("#FFFF5A5A"))
            stopJoinPlayback()
        }
    }

    private fun seekJoinPlayback(positionMs: Long) {
        if (clips.isEmpty()) return
        joinPreviewPositionMs = positionMs.coerceIn(0L, totalDurationMs())
        joinPlaybackTimeline.setCurrent(joinPreviewPositionMs)
        joinCurrentTime.text = formatTime(joinPreviewPositionMs)
        val index = clips.indices.firstOrNull { joinPreviewPositionMs < joinClipOffset(it) + clips[it].durationMs }
            ?: clips.lastIndex
        val localPosition = (joinPreviewPositionMs - joinClipOffset(index)).coerceAtLeast(0L)
        val wasPlaying = joinPreviewPlayer?.isPlaying == true
        if (joinPreviewClipIndex == index && joinPreviewPrepared) {
            joinPreviewPlayer?.let { player ->
                seekMediaPlayer(player, localPosition)
                if (wasPlaying && !player.isPlaying) player.start()
            }
        } else {
            prepareJoinPlayback(index, localPosition, wasPlaying)
        }
    }

    private fun changeJoinPlaybackSpeed(direction: Int) {
        val index = joinPreviewSpeedSteps.indexOfFirst { kotlin.math.abs(it - joinPreviewSpeed) < 0.01f }
            .let { if (it >= 0) it else 2 }
        joinPreviewSpeed = joinPreviewSpeedSteps[(index + direction).coerceIn(0, joinPreviewSpeedSteps.lastIndex)]
        joinPreviewPlayer?.let(::applyJoinPlaybackSpeed)
        updateJoinSpeedButtons()
    }

    private fun applyJoinPlaybackSpeed(player: MediaPlayer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching { player.playbackParams = player.playbackParams.setSpeed(joinPreviewSpeed) }
    }

    private fun updateJoinPlaybackButton(playing: Boolean) {
        joinPlayPause.setImageResource(if (playing) R.drawable.ic_ffmpeg_pause else R.drawable.ic_ffmpeg_play)
        joinPlayPause.contentDescription = if (playing) "Pausar" else "Reproduzir"
    }

    private fun updateJoinSpeedButtons() {
        joinSpeedDown.alpha = if (joinPreviewSpeed <= joinPreviewSpeedSteps.first()) 0.35f else 1f
        joinSpeedUp.alpha = if (joinPreviewSpeed >= joinPreviewSpeedSteps.last()) 0.35f else 1f
    }

    private fun currentJoinPlaybackPosition(): Long {
        val player = joinPreviewPlayer
        val index = joinPreviewClipIndex
        return if (player != null && joinPreviewPrepared && index in clips.indices) {
            (joinClipOffset(index) + player.currentPosition.toLong()).coerceIn(0L, totalDurationMs())
        } else {
            joinPreviewPositionMs.coerceIn(0L, totalDurationMs())
        }
    }

    private fun joinClipOffset(index: Int): Long = clips.take(index).sumOf { it.durationMs }

    private fun seekMediaPlayer(player: MediaPlayer, positionMs: Long) {
        val safePosition = positionMs.coerceAtLeast(0L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            player.seekTo(safePosition, MediaPlayer.SEEK_CLOSEST)
        } else {
            player.seekTo(safePosition.toInt())
        }
    }

    private fun stopJoinPlayback() {
        joinPreviewPositionMs = 0L
        releaseJoinPreviewPlayer()
        joinPlaybackTimeline.setCurrent(0L)
        joinCurrentTime.text = formatTime(0L)
        updateJoinPlaybackButton(false)
    }

    private fun releaseJoinPreviewPlayer() {
        handler.removeCallbacks(joinPreviewTicker)
        joinPreviewPlayer?.release()
        joinPreviewPlayer = null
        joinPreviewClipIndex = -1
        joinPreviewPrepared = false
    }

    private fun prepareJoinedPreview(file: File) {
        val surface = resultPreviewSurface
        if (!currentJoinIsAudio() && surface == null) return
        if (!file.exists()) return
        handler.removeCallbacks(resultPreviewTicker)
        resultPreviewPlayer?.release()
        resultPreviewPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            if (surface != null) setSurface(surface)
            setOnPreparedListener { player ->
                resultPreviewDurationMs = player.duration.toLong().coerceAtLeast(1L)
                resultTimeline.isEnabled = true
                resultTimeline.setRange(resultPreviewDurationMs, 0L, resultPreviewDurationMs)
                resultTimeline.setCurrent(0L)
                resultCurrentTime.text = formatTime(0L)
                applyResultPreviewTransform()
            }
            setOnVideoSizeChangedListener { _, _, _ -> applyResultPreviewTransform() }
            setOnCompletionListener {
                resultTimeline.setCurrent(resultPreviewDurationMs)
                resultCurrentTime.text = formatTime(resultPreviewDurationMs)
                updateResultPlaybackButton(false)
            }
            prepareAsync()
        }
    }

    private fun toggleResultPlayback() {
        val player = resultPreviewPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            handler.removeCallbacks(resultPreviewTicker)
            updateResultPlaybackButton(false)
        } else {
            if (player.currentPosition.toLong() >= resultPreviewDurationMs) {
                player.seekTo(0)
                resultTimeline.setCurrent(0L)
                resultCurrentTime.text = formatTime(0L)
            }
            player.start()
            updateResultPlaybackButton(true)
            handler.removeCallbacks(resultPreviewTicker)
            handler.post(resultPreviewTicker)
        }
    }

    private fun changeResultPlaybackSpeed(direction: Int) {
        val index = resultSpeedSteps.indexOfFirst { it == resultPlaybackSpeed }.let { if (it >= 0) it else 2 }
        resultPlaybackSpeed = resultSpeedSteps[(index + direction).coerceIn(0, resultSpeedSteps.lastIndex)]
        resultPreviewPlayer?.let { player ->
            player.playbackParams = player.playbackParams.setSpeed(resultPlaybackSpeed)
        }
        updateResultSpeedButtons()
    }

    private fun seekResultPreview(positionMs: Long) {
        val player = resultPreviewPlayer ?: return
        val safePosition = positionMs.coerceIn(0L, resultPreviewDurationMs).toInt()
        player.seekTo(safePosition)
        resultCurrentTime.text = formatTime(safePosition.toLong())
    }

    private fun updateResultPlaybackButton(playing: Boolean) {
        resultPlayPause.setImageResource(if (playing) R.drawable.ic_ffmpeg_pause else R.drawable.ic_ffmpeg_play)
        resultPlayPause.contentDescription = if (playing) "Pausar" else "Reproduzir"
    }

    private fun updateResultSpeedButtons() {
        resultSpeedDown.alpha = if (resultPlaybackSpeed <= resultSpeedSteps.first()) 0.35f else 1f
        resultSpeedUp.alpha = if (resultPlaybackSpeed >= resultSpeedSteps.last()) 0.35f else 1f
    }

    private fun applyResultPreviewTransform() {
        val player = resultPreviewPlayer ?: return
        val viewWidth = resultVideoPreview.width
        val viewHeight = resultVideoPreview.height
        if (viewWidth <= 0 || viewHeight <= 0 || player.videoWidth <= 0 || player.videoHeight <= 0) return
        val scale = minOf(
            viewWidth.toFloat() / player.videoWidth.toFloat(),
            viewHeight.toFloat() / player.videoHeight.toFloat()
        )
        val width = player.videoWidth * scale
        val height = player.videoHeight * scale
        resultVideoPreview.setTransform(Matrix().apply {
            setScale(scale, scale)
            postTranslate((viewWidth - width) / 2f, (viewHeight - height) / 2f)
        })
    }

    private fun clearJoinedPreview() {
        handler.removeCallbacks(resultPreviewTicker)
        resultPreviewPlayer?.release()
        resultPreviewPlayer = null
        pendingResultPreviewFile = null
        resultPreviewDurationMs = 0L
        resultPlaybackSpeed = 1f
        resultPreviewContainer.visibility = View.GONE
        resultVideoPreview.visibility = View.GONE
        timelineScroll.visibility = View.VISIBLE
        joinPlaybackContainer.visibility = if (clips.isNotEmpty()) View.VISIBLE else View.GONE
    }

    override fun onPause() {
        joinPreviewPositionMs = currentJoinPlaybackPosition()
        joinPreviewPlayer?.pause()
        handler.removeCallbacks(joinPreviewTicker)
        updateJoinPlaybackButton(false)
        resultPreviewPlayer?.pause()
        handler.removeCallbacks(resultPreviewTicker)
        updateResultPlaybackButton(false)
        super.onPause()
    }

    override fun onDestroy() {
        releaseJoinPreviewPlayer()
        clearJoinedPreview()
        resultPreviewSurface?.release()
        resultPreviewSurface = null
        super.onDestroy()
    }

    private fun clearOutputResult() {
        stopJoinPlayback()
        clearJoinedPreview()
        tempOutputFiles.clear()
        tempSmartJoinDiagnosticFile = null
        lastOutputUri = null
        lastOutputName = ""
        processingSteps.clear()
        outputFileName.text = ""
        outputFileName.visibility = View.GONE
        outputActions.visibility = View.GONE
    }

    private fun copyUriToCache(uri: Uri, displayName: String): File {
        val extension = displayName.substringAfterLast('.', "mp4")
        val file = File(cacheDir, "join_input_${System.nanoTime()}.$extension")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        }
        return file
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private fun totalDurationMs(): Long = clips.sumOf { it.durationMs }.coerceAtLeast(1L)

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun FfmpegVideoEncoder.toSmartJoinOption(): SmartJoinEncoderOption {
        return SmartJoinEncoderOption(ffmpegName = ffmpegName, codecFamily = codecFamily)
    }

    private fun smartJoinProcessingLabels(): List<String> {
        val labels = mutableListOf("Validando encoders do Smart Join")
        val transitionSeconds = safeTransitionSeconds()
        val transitionName = if (isFadeInOutTransition()) "fade" else "xfade"
        clips.forEachIndexed { index, clip ->
            val clipSeconds = (clip.durationMs / 1000.0).coerceAtLeast(0.001)
            val bodyStart = if (index == 0) 0.0 else transitionSeconds
            val bodyEnd = if (index == clips.lastIndex) clipSeconds else (clipSeconds - transitionSeconds)
            if (bodyEnd - bodyStart > 0.12) {
                labels += "Copiando corpo ${index + 1}/${clips.size} (TS)"
            }
            if (index < clips.lastIndex) {
                labels += "Gerando $transitionName ${index + 1}/${clips.size - 1} (TS)"
            }
        }
        labels += "Juntando experimento (TS -> MP4)"
        return labels
    }

    private fun regularVideoProcessingLabels(): List<String> {
        if (!checkReencode.isChecked) return listOf("Juntando sem reencodar")
        return if (isFadeInOutTransition()) {
            listOf(
                "Lendo keyframes dos vídeos",
                "Calculando limites da transição",
                "Verificando trechos preserváveis"
            )
        } else {
            listOf("Aplicando transições")
        }
    }

    private fun initProcessingSteps() {
        processingSteps.clear()
        processingSteps += ProcessingStep("Preparar arquivos de entrada")
        if (currentJoinIsAudio()) {
            val label = if (checkReencode.isChecked || checkSmartJoin.isChecked) {
                "Aplicando transição de áudio"
            } else {
                "Juntando áudios sem reencodar"
            }
            processingSteps += ProcessingStep(label)
            processingSteps += ProcessingStep("Preparar arquivo para salvar")
            renderProcessingSteps()
            return
        }
        val useOrientationSafeReencode = shouldUseOrientationSafeReencode()
        if (useOrientationSafeReencode) {
            processingSteps += ProcessingStep("Normalizando vídeos")
        } else if (usesFastPieceJoin()) {
            smartJoinProcessingLabels().forEach { processingSteps += ProcessingStep(it) }
        } else {
            regularVideoProcessingLabels().forEach { processingSteps += ProcessingStep(it) }
        }
        processingSteps += ProcessingStep("Preparar arquivo para salvar")
        renderProcessingSteps()
    }

    private fun configureVideoProcessingPlan(
        taskLabels: List<String>,
        preserveExistingTaskState: Boolean = true
    ) {
        val applyPlan = {
            val preparation = processingSteps.firstOrNull { it.label == "Preparar arquivos de entrada" }
                ?: ProcessingStep("Preparar arquivos de entrada")
            preparation.percent = 100
            preparation.state = StepState.DONE
            preparation.detail = null
            val save = processingSteps.firstOrNull { it.label == "Preparar arquivo para salvar" }
                ?: ProcessingStep("Preparar arquivo para salvar")
            val existingByLabel = if (preserveExistingTaskState) {
                processingSteps.associateBy { it.label }
            } else {
                listOfNotNull(
                    processingSteps.firstOrNull { it.label == SMART_JOIN_VALIDATION_LABEL }?.let {
                        SMART_JOIN_VALIDATION_LABEL to it
                    }
                ).toMap()
            }
            processingSteps.clear()
            processingSteps += preparation
            taskLabels.forEach { processingSteps += existingByLabel[it] ?: ProcessingStep(it) }
            processingSteps += save
            renderProcessingSteps()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyPlan()
        } else {
            val latch = CountDownLatch(1)
            runOnUiThread {
                applyPlan()
                latch.countDown()
            }
            latch.await()
        }
    }

    private fun configureFadeProcessingPlan(taskLabels: List<String>) {
        val applyPlan = {
            val preparation = processingSteps.firstOrNull { it.label == "Preparar arquivos de entrada" }
                ?: ProcessingStep("Preparar arquivos de entrada", 100, StepState.DONE)
            val save = processingSteps.firstOrNull { it.label == "Preparar arquivo para salvar" }
                ?: ProcessingStep("Preparar arquivo para salvar")
            val completedOrActiveSteps = processingSteps.filter {
                it.label != "Preparar arquivos de entrada" && it.label != "Preparar arquivo para salvar"
            }
            processingSteps.clear()
            processingSteps += preparation
            processingSteps += completedOrActiveSteps
            taskLabels.forEach { processingSteps += ProcessingStep(it) }
            processingSteps += save
            renderProcessingSteps()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyPlan()
        } else {
            val latch = CountDownLatch(1)
            runOnUiThread {
                applyPlan()
                latch.countDown()
            }
            latch.await()
        }
    }

    private fun isFadeInOutTransition(): Boolean = selectedTransition == TRANSITION_FADE_IN_OUT

    private fun audioCrossfadeCurve(): String {
        return AUDIO_TRANSITIONS[selectedTransition] ?: "tri"
    }

    private fun xfadeTransitionName(): String {
        return if (isFadeInOutTransition()) "fade" else selectedTransition
    }

    private fun shouldUseOrientationSafeReencode(): Boolean {
        if (!usesFastPieceJoin() || clips.size < 2) return false
        return clips
            .map { rotationComparisonKey(it.rotationDegrees) }
            .distinct()
            .size > 1
    }

    private fun usesFastPieceJoin(): Boolean {
        return checkSmartJoin.isChecked
    }

    private fun renameProcessingStep(oldLabel: String, newLabel: String) {
        val action = {
            processingSteps.firstOrNull { it.label == oldLabel }?.let { step ->
                step.label = newLabel
                renderProcessingSteps()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            handler.post { action() }
        }
    }

    private fun restartProcessingStep(label: String) {
        val action = {
            processingSteps.firstOrNull { it.label == label }?.let { step ->
                step.percent = 0
                step.state = StepState.RUNNING
                step.detail = null
                step.startedAtMs = SystemClock.elapsedRealtime()
                step.elapsedMs = null
                renderProcessingSteps()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            handler.post { action() }
        }
    }

    private fun rotationComparisonKey(value: Int): Int {
        return ((value % 360) + 360) % 360
    }

    private fun updateStep(
        label: String,
        percent: Int?,
        state: StepState,
        detail: String? = null,
        encoderName: String? = null,
        elapsedMs: Long? = null
    ) {
        val action = {
            val step = processingSteps.firstOrNull { it.label == label }
                ?: processingSteps.firstOrNull { label.startsWith("${it.label} (") }
            if (step != null) {
                if (!(step.state == StepState.DONE && state == StepState.RUNNING)) {
                    if (step.label != label) step.label = label
                    percent?.let { step.percent = it.coerceIn(0, 100) }
                    encoderName?.let { step.encoderName = it }
                    if (state == StepState.RUNNING && step.startedAtMs == null) {
                        step.startedAtMs = SystemClock.elapsedRealtime()
                    }
                    if (state == StepState.DONE) {
                        step.elapsedMs = elapsedMs
                            ?: step.startedAtMs?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
                    }
                    step.state = state
                    step.detail = detail
                    renderProcessingSteps()
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            handler.post { action() }
        }
    }

    private fun failActiveStep(message: String) {
        val action = {
            val active = processingSteps.firstOrNull { it.state == StepState.RUNNING }
                ?: processingSteps.firstOrNull { it.state == StepState.PENDING }
            if (active != null) {
                active.state = StepState.ERROR
                active.detail = message
                renderProcessingSteps()
            } else {
                status.text = message
                status.setTextColor(Color.parseColor("#FFFF5A5A"))
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            handler.post { action() }
        }
    }

    private fun successStep(stats: String) {
        val action = {
            processingSteps.forEach { 
                if (it.startedAtMs != null && it.elapsedMs == null) {
                    it.elapsedMs = (SystemClock.elapsedRealtime() - it.startedAtMs!!).coerceAtLeast(0L)
                }
                it.state = StepState.DONE 
                it.percent = 100
            }
            renderProcessingSteps()
            
            val builder = SpannableStringBuilder(status.text)
            builder.append("\n\n")
            val startStats = builder.length
            builder.append(stats)
            builder.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#FF5EDAF2")), startStats, builder.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            status.text = builder
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            handler.post { action() }
        }
    }

    private fun renderProcessingSteps() {
        if (processingSteps.isEmpty()) return
        val builder = SpannableStringBuilder()
        processingSteps.forEachIndexed { index, step ->
            val start = builder.length
            val suffix = when (step.state) {
                StepState.RUNNING -> FfmpegProgressText.suffix(
                    encoderName = step.encoderName,
                    detail = step.detail
                )
                StepState.DONE -> FfmpegProgressText.suffix(
                    encoderName = step.encoderName,
                    elapsedMs = step.elapsedMs
                )
                else -> ""
            }
            if (step.state == StepState.PENDING) {
                builder.append("    ")
                builder.append(step.label)
            } else if (step.state == StepState.ERROR) {
                builder.append(step.label).append(": FALHOU")
            } else {
                builder.append(step.label).append(' ').append(step.percent.toString()).append('%').append(suffix)
            }
            if (index < processingSteps.lastIndex) builder.append('\n')
            val color = when (step.state) {
                StepState.PENDING -> Color.parseColor("#88FFFFFF")
                StepState.RUNNING -> Color.WHITE
                StepState.DONE -> Color.parseColor("#FF62F28F")
                StepState.ERROR -> Color.parseColor("#FFFF5A5A")
            }
            builder.setSpan(ForegroundColorSpan(color), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        status.text = builder
    }

    private fun buildJoinedOutputName(forceAudioStandardization: Boolean = false): String {
        val baseName = clips.joinToString("+") { clip ->
            sanitizeFileNameBase(clip.name.substringBeforeLast('.', clip.name))
        }.ifBlank {
            if (currentJoinIsAudio()) "audios_juntos" else "videos_juntos"
        }
        val extension = if (currentJoinIsAudio()) {
            if (forceAudioStandardization) "wav"
            else if (checkReencode.isChecked || checkSmartJoin.isChecked) "m4a"
            else clips.firstOrNull()?.name?.substringAfterLast('.', "m4a")?.lowercase(Locale.ROOT) ?: "m4a"
        } else {
            "mp4"
        }
        return "$baseName.$extension"
    }

    private fun currentJoinIsAudio(): Boolean = clips.isNotEmpty() && clips.all { it.isAudio }

    private fun audioInputsAreCopyCompatible(inputs: List<File>): Boolean {
        if (inputs.size < 2) return true
        val profiles = inputs.map(::inspectAudioInput)
        val first = profiles.firstOrNull() ?: return false
        if (profiles.any { it == null }) return false
        return profiles.drop(1).all { profile ->
            profile != null &&
                profile.mime == first.mime &&
                profile.sampleRate == first.sampleRate &&
                profile.channels == first.channels &&
                profile.pcmEncoding == first.pcmEncoding
        }
    }

    private fun inspectAudioInput(file: File): AudioInputProfile? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(audioTrack)
            AudioInputProfile(
                mime = format.getString(MediaFormat.KEY_MIME).orEmpty(),
                sampleRate = formatIntOrNull(format, MediaFormat.KEY_SAMPLE_RATE),
                channels = formatIntOrNull(format, MediaFormat.KEY_CHANNEL_COUNT),
                pcmEncoding = formatIntOrNull(format, MediaFormat.KEY_PCM_ENCODING)
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Não foi possível analisar a compatibilidade do áudio ${file.name}", error)
            null
        } finally {
            extractor.release()
        }
    }

    private fun formatIntOrNull(format: MediaFormat, key: String): Int? {
        return if (format.containsKey(key)) {
            runCatching { format.getInteger(key) }.getOrNull()
        } else {
            null
        }
    }

    private fun audioJoinNormalizeFilter(profile: OutputProfile): String {
        val sampleRate = profile.audioSampleRate
        val layout = profile.audioLayout
        return "aresample=$sampleRate,aformat=sample_fmts=fltp:sample_rates=$sampleRate:channel_layouts=$layout"
    }

    private fun audioMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        else -> "audio/mp4"
    }

    private fun sanitizeFileNameBase(name: String): String {
        return name
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .ifBlank { "video" }
    }

    private fun parseBitrateFromText(text: String): String? {
        return parseBitrateKbpsFromText(text)?.let { "${it.coerceAtLeast(1)}k" }
    }

    private fun parseBitrateKbpsFromText(text: String): Int? {
        val match = Regex("""(\d+(?:\.\d+)?)\s*([kmg]?)\s*(?:b/s|bit/s|bits/s)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?: return null
        val value = match.groupValues[1].toDoubleOrNull()?.takeIf { it > 0.0 } ?: return null
        val unit = match.groupValues[2].lowercase(Locale.ROOT)
        val kbps = when (unit) {
            "g" -> value * 1_000_000.0
            "m" -> value * 1000.0
            "k" -> value
            else -> value / 1000.0
        }
        return kbps.toInt().coerceAtLeast(1)
    }

    private fun detectOutputProfile(
        inputFile: File?,
        firstClip: JoinClip?,
        encoderOverride: FfmpegVideoEncoder? = null
    ): OutputProfile {
        val fallbackWidth = makeEven(firstClip?.width ?: 1280).coerceAtLeast(2)
        val fallbackHeight = makeEven(firstClip?.height ?: 720).coerceAtLeast(2)
        if (inputFile == null) {
            return OutputProfile(
                width = fallbackWidth,
                height = fallbackHeight,
                fps = "30",
                rotationDegrees = normalizeRotationForMetadata(firstClip?.rotationDegrees ?: 0),
                videoCodec = DEFAULT_VIDEO_CODEC,
                videoEncoder = videoEncoderFor(DEFAULT_VIDEO_CODEC, encoderOverride),
                videoBitstreamFilter = videoBitstreamFilterFor(DEFAULT_VIDEO_CODEC),
                videoBitrate = FALLBACK_VIDEO_BITRATE,
                videoBufferSize = bufferSizeFor(FALLBACK_VIDEO_BITRATE),
                audioSampleRate = 48000,
                audioChannels = 2,
                audioLayout = "stereo",
                audioBitrate = "192k"
            )
        }

        return try {
            val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-i", inputFile.absolutePath))
            val logs = session.allLogsAsString.orEmpty()
            val videoLine = logs.lines().firstOrNull { it.contains("Video:", ignoreCase = true) }.orEmpty()
            val audioLine = logs.lines().firstOrNull { it.contains("Audio:", ignoreCase = true) }.orEmpty()
            val resolution = parseResolution(videoLine)
            val videoCodec = detectVideoCodec(videoLine)
            val rotationDegrees = parseDisplayRotation(logs)
                ?: normalizeRotationForMetadata(firstClip?.rotationDegrees ?: 0)
            val videoBitrate = bestVideoBitrate(inputFile, logs, videoLine)
                ?: FALLBACK_VIDEO_BITRATE
            val audioSampleRate = parseAudioSampleRate(audioLine) ?: 48000
            val audioChannels = parseAudioChannels(audioLine)
            val audioLayout = if (audioChannels == 1) "mono" else "stereo"

            OutputProfile(
                width = resolution?.first ?: fallbackWidth,
                height = resolution?.second ?: fallbackHeight,
                fps = parseFrameRate(videoLine) ?: "30",
                rotationDegrees = rotationDegrees,
                videoCodec = videoCodec,
                videoEncoder = videoEncoderFor(videoCodec, encoderOverride),
                videoBitstreamFilter = videoBitstreamFilterFor(videoCodec),
                videoBitrate = videoBitrate,
                videoBufferSize = bufferSizeFor(videoBitrate),
                audioSampleRate = audioSampleRate,
                audioChannels = audioChannels,
                audioLayout = audioLayout,
                audioBitrate = parseBitrateFromText(audioLine) ?: "192k"
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Could not detect output media profile", e)
            OutputProfile(
                width = fallbackWidth,
                height = fallbackHeight,
                fps = "30",
                rotationDegrees = normalizeRotationForMetadata(firstClip?.rotationDegrees ?: 0),
                videoCodec = DEFAULT_VIDEO_CODEC,
                videoEncoder = videoEncoderFor(DEFAULT_VIDEO_CODEC, encoderOverride),
                videoBitstreamFilter = videoBitstreamFilterFor(DEFAULT_VIDEO_CODEC),
                videoBitrate = FALLBACK_VIDEO_BITRATE,
                videoBufferSize = bufferSizeFor(FALLBACK_VIDEO_BITRATE),
                audioSampleRate = 48000,
                audioChannels = 2,
                audioLayout = "stereo",
                audioBitrate = "192k"
            )
        }
    }

    private fun bestVideoBitrate(inputFile: File, logs: String, videoLine: String): String? {
        val candidates = listOfNotNull(
            parseBitrateKbpsFromText(videoLine),
            detectContainerBitrateKbps(inputFile),
            parseBitrateKbpsFromText(logs)
        )
        return candidates.maxOrNull()?.let { "${it.coerceAtLeast(1)}k" }
    }

    private fun detectContainerBitrateKbps(inputFile: File): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(inputFile.absolutePath)
            val bitsPerSecond = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: return null
            (bitsPerSecond / 1000L).coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } catch (_: Throwable) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun parseResolution(videoLine: String): Pair<Int, Int>? {
        val match = Regex("""\b(\d{2,5})x(\d{2,5})\b""").find(videoLine) ?: return null
        val width = match.groupValues[1].toIntOrNull()?.let { makeEven(it).coerceAtLeast(2) } ?: return null
        val height = match.groupValues[2].toIntOrNull()?.let { makeEven(it).coerceAtLeast(2) } ?: return null
        return width to height
    }

    private fun detectVideoCodec(videoLine: String): String {
        return when {
            videoLine.contains("hevc", ignoreCase = true) ||
                videoLine.contains("h265", ignoreCase = true) ||
                videoLine.contains("h.265", ignoreCase = true) -> "hevc"
            else -> DEFAULT_VIDEO_CODEC
        }
    }

    private fun parseDisplayRotation(logs: String): Int? {
        val displayMatrix = Regex("""rotation of\s+(-?\d+(?:\.\d+)?)\s+degrees""", RegexOption.IGNORE_CASE)
            .find(logs)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?.let { kotlin.math.round(it).toInt() }
        if (displayMatrix != null) {
            return normalizeRotationForMetadata(displayMatrix)
        }
        return Regex("""rotate\s*:\s*(-?\d+)""", RegexOption.IGNORE_CASE)
            .find(logs)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { normalizeRotationForMetadata(it) }
    }

    private fun normalizeRotationForMetadata(value: Int): Int {
        val normalized = ((value % 360) + 360) % 360
        return when (normalized) {
            90 -> if (value < 0) -270 else 90
            180 -> 180
            270 -> if (value < 0) -90 else 270
            else -> 0
        }
    }

    private fun rotationMetadataArguments(rotationDegrees: Int): List<String> {
        if (rotationDegrees == 0) return emptyList()
        return listOf("-metadata:s:v:0", "rotate=$rotationDegrees")
    }

    private fun displayOrientedReencodeProfile(profile: OutputProfile): OutputProfile {
        val normalized = ((profile.rotationDegrees % 360) + 360) % 360
        if (normalized != 90 && normalized != 270) {
            return profile.copy(rotationDegrees = 0)
        }
        return profile.copy(
            width = profile.height,
            height = profile.width,
            rotationDegrees = 0
        )
    }

    private fun videoEncoderFor(codec: String, encoderOverride: FfmpegVideoEncoder? = null): String {
        return encoderOverride?.ffmpegName ?: selectedVideoEncoder?.ffmpegName ?: when (codec) {
            "hevc" -> "hevc_mediacodec"
            else -> "h264_mediacodec"
        }
    }

    private fun videoEncodingArguments(
        profile: OutputProfile,
        constrained: Boolean,
        encoderOverride: FfmpegVideoEncoder? = null
    ): List<String> {
        val encoder = encoderOverride ?: requireVideoEncoder()
        val settings = encoder.encodingFor(selectedVideoQuality, profile.videoBitrate)
        val targetBitrate = settings.targetBitrate ?: return settings.arguments
        return buildList {
            addAll(settings.arguments)
            addAll(listOf("-b:v", targetBitrate))
            if (constrained) {
                addAll(listOf("-minrate", targetBitrate, "-maxrate", targetBitrate, "-bufsize", bufferSizeFor(targetBitrate)))
            }
        }
    }

    private fun requireVideoEncoder(): FfmpegVideoEncoder {
        return selectedVideoEncoder ?: error("Encoder de vídeo indisponível")
    }

    private fun videoBitstreamFilterFor(codec: String): String {
        return when (codec) {
            "hevc" -> "hevc_mp4toannexb"
            else -> "h264_mp4toannexb"
        }
    }

    private fun parseFrameRate(videoLine: String): String? {
        val value = Regex("""(\d+(?:\.\d+)?)\s*fps""", RegexOption.IGNORE_CASE)
            .find(videoLine)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?: Regex("""(\d+(?:\.\d+)?)\s*tbr""", RegexOption.IGNORE_CASE)
                .find(videoLine)
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()
            ?: return null
        if (value <= 0.0 || value > 240.0) return null
        return formatFrameRate(value)
    }

    private fun formatFrameRate(value: Double): String {
        val rounded = kotlin.math.round(value)
        return if (kotlin.math.abs(value - rounded) < 0.01) {
            rounded.toInt().toString()
        } else {
            String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
        }
    }

    private fun parseAudioSampleRate(audioLine: String): Int? {
        return Regex("""(\d{4,6})\s*Hz""", RegexOption.IGNORE_CASE)
            .find(audioLine)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it in 8000..192000 }
    }

    private fun parseAudioChannels(audioLine: String): Int {
        return when {
            audioLine.contains("mono", ignoreCase = true) -> 1
            audioLine.contains("stereo", ignoreCase = true) -> 2
            else -> 2
        }
    }

    private fun bufferSizeFor(bitrate: String): String {
        val match = Regex("""(\d+)""").find(bitrate) ?: return "30M"
        val value = match.groupValues[1].toIntOrNull() ?: return "30M"
        return when {
            bitrate.endsWith("M", ignoreCase = true) -> "${(value * 2).coerceAtLeast(2)}M"
            else -> "${(value * 2).coerceAtLeast(2)}k"
        }
    }

    private fun makeEven(value: Int): Int = if (value % 2 == 0) value else value - 1

    private fun formatDecimal(value: Double): String = String.format(Locale.US, "%.3f", value)

    private data class JoinClip(
        val id: Long,
        val uri: Uri,
        val name: String,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val hasAudio: Boolean,
        val isAudio: Boolean,
        val thumbnail: Bitmap?
    )

    private data class AudioInputProfile(
        val mime: String,
        val sampleRate: Int?,
        val channels: Int?,
        val pcmEncoding: Int?
    )

    private data class OutputProfile(
        val width: Int,
        val height: Int,
        val fps: String,
        val rotationDegrees: Int,
        val videoCodec: String,
        val videoEncoder: String,
        val videoBitstreamFilter: String,
        val videoBitrate: String,
        val videoBufferSize: String,
        val audioSampleRate: Int,
        val audioChannels: Int,
        val audioLayout: String,
        val audioBitrate: String
    )

    private data class SmartJoinPiece(
        val file: File,
        val durationSeconds: Double
    )

    private data class SmartJoinEncoderProbe(
        val success: Boolean,
        val detail: String
    )

    private data class SmartJoinPreflightResult(
        val approvedEncoders: List<FfmpegVideoEncoder>,
        val failedEncoders: List<FfmpegVideoEncoder>,
        val failureMessages: List<String>
    )

    private enum class SmartJoinDecisionType {
        USE_ENCODER,
        FULL_REENCODE,
        CANCEL
    }

    private data class SmartJoinDecision(
        val type: SmartJoinDecisionType,
        val encoder: FfmpegVideoEncoder? = null
    )

    private data class ProcessingStep(
        var label: String,
        var percent: Int = 0,
        var state: StepState = StepState.PENDING,
        var detail: String? = null,
        var encoderName: String? = null,
        var startedAtMs: Long? = null,
        var elapsedMs: Long? = null
    )

    private enum class StepState {
        PENDING,
        RUNNING,
        DONE,
        ERROR
    }

    private data class JoinExecutionResult(
        val success: Boolean,
        val cancelled: Boolean,
        val failureMessage: String,
        val diagnosticFile: File? = null
    )

    companion object {
        private const val REQUEST_PICK_VIDEOS = 7601
        private const val REQUEST_CHOOSE_PRE_OUTPUT_DIR = 7602
        private const val REQUEST_CHOOSE_OUTPUT_DIR = 7603
        private const val FALLBACK_VIDEO_BITRATE = "15M"
        private const val FAST_FADE_MIN_CLIP_SECONDS = 6.0
        private const val DEFAULT_VIDEO_CODEC = "h264"
        private const val TRANSITION_FADE_IN_OUT = "Fade in/out"
        private const val TRANSITION_DEFAULT_VIDEO = "fade"
        private const val SMART_JOIN_VALIDATION_LABEL = "Validando encoders do Smart Join"
        private const val SMART_JOIN_PREFLIGHT_MAX_SECONDS = 1.0
        private const val TAG = "FfmpegJoinVideos"
        private val TRANSITIONS = listOf(
            TRANSITION_FADE_IN_OUT,
            "fade",
            "dissolve",
            "wipeleft",
            "wiperight",
            "slideleft",
            "slideright",
            "smoothleft",
            "smoothright",
            "circleopen",
            "circleclose"
        )
        private val AUDIO_TRANSITIONS = linkedMapOf(
            TRANSITION_FADE_IN_OUT to null,
            "Linear slope (tri)" to "tri",
            "Quarter sine wave (qsin)" to "qsin",
            "Exponential sine wave (esin)" to "esin",
            "Half sine wave (hsin)" to "hsin",
            "Logarithmic (log)" to "log",
            "Inverted parabola (ipar)" to "ipar",
            "Quadratic (qua)" to "qua",
            "Cubic (cub)" to "cub",
            "Square root (squ)" to "squ",
            "Cubic root (cbr)" to "cbr",
            "Parabola (par)" to "par",
            "Exponential (exp)" to "exp",
            "Inverted quarter sine wave (iqsin)" to "iqsin",
            "Inverted half sine wave (ihsin)" to "ihsin",
            "Double-exponential seat (dese)" to "dese",
            "Double-exponential sigmoid (desi)" to "desi",
            "Logistic sigmoid (losi)" to "losi",
            "Sine cardinal function (sinc)" to "sinc",
            "Inverted sine cardinal function (isinc)" to "isinc",
            "Quartic (quat)" to "quat",
            "Quartic root (quatr)" to "quatr",
            "Squared quarter sine wave (qsin2)" to "qsin2",
            "Squared half sine wave (hsin2)" to "hsin2",
            "No fade (nofade)" to "nofade"
        )
    }
}
