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
import androidx.core.widget.doAfterTextChanged
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
    private lateinit var smartJoinRow: View
    private lateinit var buttonVideoEncoder: TextView
    private lateinit var buttonVideoQuality: TextView
    private lateinit var videoEncodingControls: View
    private lateinit var buttonJoin: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var outputFileName: TextView
    private lateinit var outputStats: TextView
    private lateinit var outputActions: View
    private lateinit var buttonSaveToFolder: ImageButton
    private lateinit var buttonOutputFolder: ImageButton
    private lateinit var buttonOutputShare: ImageButton
    private lateinit var buttonSelectOutputFolder: ImageButton
    private lateinit var arrowInputOutput: View
    private lateinit var buttonSelectVideos: View

    private val handler = Handler(Looper.getMainLooper())
    private val clips = mutableListOf<JoinClip>()
    private val selectedAudioTracks = mutableMapOf<String, Int>()
    private val tempOutputFiles = mutableListOf<File>()
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
    private var processingVideoQuality = FfmpegVideoQuality.default
    private var processingAudioTrackCount = 1
    private var updatingJoinModeChecks = false
    private var previewProfiles: Map<String, OutputProfile> = emptyMap()
    private var previewKeyframes: Map<String, List<Double>> = emptyMap()
    private var previewAnalysisGeneration = 0
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
        smartJoinRow = findViewById(R.id.smart_join_row)
        buttonVideoEncoder = findViewById(R.id.button_video_encoder)
        buttonVideoQuality = findViewById(R.id.button_video_quality)
        videoEncodingControls = findViewById(R.id.video_encoding_controls)
        buttonJoin = findViewById(R.id.button_join)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        outputFileName = findViewById(R.id.output_file_name)
        outputStats = findViewById(R.id.output_stats)
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
        buttonSelectVideos = findViewById<View>(R.id.button_select_videos)
        buttonSelectVideos.setOnClickListener { openMediaPicker() }
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

        checkReencode.setOnCheckedChangeListener { _, checked ->
            if (checked && !updatingJoinModeChecks) {
                updatingJoinModeChecks = true
                checkSmartJoin.isChecked = false
                updatingJoinModeChecks = false
            }
            normalizeVideoTransitionForCurrentMode()
            updateReencodeControls()
        }
        checkSmartJoin.setOnCheckedChangeListener { _, checked ->
            if (checked && !updatingJoinModeChecks) {
                updatingJoinModeChecks = true
                checkReencode.isChecked = false
                updatingJoinModeChecks = false
            }
            normalizeVideoTransitionForCurrentMode()
            updateReencodeControls()
        }
        inputTransitionTime.doAfterTextChanged { refreshCommandPreview() }
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
        refreshCommandPreview()
        handleIncomingShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    /** Juntar aceita varios audios ou varios videos, sem misturar os dois. */
    private fun handleIncomingShareIntent(intent: Intent?) {
        if (!SharedMediaIntents.isShareAction(intent)) return
        val received = SharedMediaIntents.mediaFrom(this, intent)
        val media = received.filter { it.isAudio || it.isVideo }
        if (media.isEmpty()) {
            Toast.makeText(this, "Compartilhe arquivos de áudio ou vídeo.", Toast.LENGTH_LONG).show()
            status.text = "Nenhum arquivo de áudio ou vídeo recebido."
            return
        }
        val rejected = received.size - media.size
        addPickedUris(media.map { it.uri }, intent?.flags ?: 0)
        if (rejected > 0) {
            status.text = "$rejected arquivo(s) ignorado(s): envie apenas áudio ou vídeo."
        }
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
        val uris = mutableListOf<Uri>()
        data?.clipData?.let { clipData ->
            for (index in 0 until clipData.itemCount) {
                uris += clipData.getItemAt(index).uri
            }
        }
        data?.data?.let { uris += it }
        addPickedUris(uris, data?.flags ?: 0)
    }

    /** Caminho comum entre o seletor de arquivos e o compartilhamento. */
    private fun addPickedUris(uris: List<Uri>, flags: Int) {
        if (uris.isEmpty()) return

        val loaded = uris.distinct().mapNotNull { uri ->
            SharedMediaIntents.takeReadPermission(contentResolver, uri, flags)
            loadClip(uri)
        }
        if (loaded.isEmpty()) {
            Toast.makeText(this, "Nenhum arquivo de áudio ou vídeo foi reconhecido.", Toast.LENGTH_LONG).show()
            return
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
            TRANSITIONS
        }
        if (selectedTransition !in allowedTransitions) {
            selectedTransition = if (currentJoinIsAudio()) {
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
        val isAudio = currentJoinIsAudio()
        videoEncodingControls.visibility = if (isAudio) View.GONE else View.VISIBLE
        smartJoinRow.visibility = if (isAudio) View.GONE else View.VISIBLE
        if (isAudio && checkSmartJoin.isChecked) checkSmartJoin.isChecked = false
        setJoinEnabled(clips.size >= 2 && !isProcessing)
        scheduleJoinPreviewAnalysis()
        refreshCommandPreview()
    }

    private fun updateReencodeControls(refreshPreview: Boolean = true) {
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
        // A restauracao automatica de UI apos o processamento nao deve
        // substituir o historico verde dos comandos executados pelo preview.
        if (refreshPreview) refreshCommandPreview()
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
        if (!isProcessing) processingVideoQuality = selectedVideoQuality
        buttonVideoEncoder.text = if (encoder == null) "Encoder indisponível" else encoder.shortName
        buttonVideoQuality.text = selectedVideoQuality.label
        updateReencodeControls()
    }

    private fun scheduleJoinPreviewAnalysis() {
        val generation = ++previewAnalysisGeneration
        val snapshot = clips.toList()
        if (snapshot.isEmpty()) {
            previewProfiles = emptyMap()
            previewKeyframes = emptyMap()
            return
        }
        Thread {
            val profiles = snapshot.associate { clip ->
                clip.uri.toString() to detectOutputProfile(clip.uri, clip)
            }
            val keyframes = snapshot.filterNot { it.isAudio }.associate { clip ->
                clip.uri.toString() to detectVideoKeyframes(clip.uri)
            }
            runOnUiThread {
                if (generation == previewAnalysisGeneration) {
                    previewProfiles = profiles
                    previewKeyframes = keyframes
                    refreshCommandPreview()
                }
            }
        }.start()
    }

    private fun refreshCommandPreview() {
        if (isProcessing || !::status.isInitialized) return
        val inputs = if (clips.isEmpty()) {
            listOf(File("input.ext"), File("input2.ext"))
        } else {
            clips.map { File(it.name) }
        }
        if (clips.isEmpty()) {
            FfmpegCommandPresenter.preview(
                status,
                FfmpegMediaPolicies.directConcatCommandArguments(File("input.txt").absolutePath, File("output.ext").absolutePath).asIterable()
            )
            return
        }
        val profiles = clips.map { clip ->
            previewProfiles[clip.uri.toString()] ?: detectOutputProfile(null, clip)
        }
        val aggregate = aggregateOutputProfiles(profiles)
        val commands = when {
            currentJoinIsAudio() && checkReencode.isChecked -> {
                val extension = clips.first().name.substringAfterLast('.', "m4a").lowercase(Locale.ROOT)
                    .takeIf { it in setOf("wav", "flac", "mp3", "ogg", "opus", "m4a", "aac") } ?: "m4a"
                listOf(
                    FfmpegCommandPresenter.PreviewCommand(
                        buildAudioReencodeArguments(inputs, File("output.$extension"), aggregate, withTransition = true).asIterable()
                    )
                )
            }
            currentJoinIsAudio() -> {
                val extension = clips.first().name.substringAfterLast('.', "ext")
                listOf(
                    FfmpegCommandPresenter.PreviewCommand(
                        FfmpegMediaPolicies.directConcatCommandArguments(
                            File("input.txt").absolutePath,
                            File("output.$extension").absolutePath
                        ).asIterable()
                    )
                )
            }
            !checkReencode.isChecked && !checkSmartJoin.isChecked -> buildList {
                add(
                    FfmpegCommandPresenter.PreviewCommand(
                        FfmpegMediaPolicies.directConcatCommandArguments(
                            File("input.txt").absolutePath,
                            File("output.mkv").absolutePath
                        ).asIterable()
                    )
                )
                previewVideoRemux("mkv", profiles.firstOrNull()?.videoCodec.equals("hevc", true))?.let {
                    add(FfmpegCommandPresenter.PreviewCommand(it))
                }
            }
            checkSmartJoin.isChecked -> {
                val outcome = buildSmartJoinPreview(inputs, profiles, aggregate)
                if (outcome.reason != null && outcome.commands.isEmpty()) {
                    // Plano indisponivel: nunca deixar o comando anterior
                    // (de outro modo) na tela como se fosse o planejado.
                    FfmpegCommandPresenter.placeholder(
                        status,
                        "${outcome.reason} Desative o SmartJoin para ver o comando desta junção."
                    )
                }
                // reason == null com commands vazio = analise de keyframes
                // ainda em voo; o refresh ao final da analise mostra o plano.
                outcome.commands
            }
            else -> {
                val encoder = selectedVideoEncoder
                if (encoder == null) emptyList() else {
                    val intermediateExtension = if (encoder.ffmpegName.endsWith("_mediacodec", true)) "mp4" else "mkv"
                    val output = File("output.$intermediateExtension")
                    val main = if (isFadeInOutTransition()) {
                        buildFadeInOutReencodeArguments(inputs, output, aggregate)
                    } else {
                        buildReencodeArguments(inputs, output, withTransition = true, profileOverride = aggregate)
                    }
                    buildList {
                        add(FfmpegCommandPresenter.PreviewCommand(main.asIterable()))
                        previewVideoRemux(intermediateExtension, encoder.codecFamily == "hevc")?.let {
                            add(FfmpegCommandPresenter.PreviewCommand(it))
                        }
                    }
                }
            }
        }
        if (commands.isNotEmpty()) FfmpegCommandPresenter.preview(status, commands)
    }

    private data class SmartJoinPreviewOutcome(
        val commands: List<FfmpegCommandPresenter.PreviewCommand>,
        val reason: String? = null
    )

    private fun buildSmartJoinPreview(
        inputs: List<File>,
        sourceProfiles: List<OutputProfile>,
        aggregate: OutputProfile
    ): SmartJoinPreviewOutcome {
        // Analise de keyframes ainda em voo: sem comando por enquanto; o
        // refresh ao final da analise (scheduleJoinPreviewAnalysis) refaz o
        // preview. reason == null sinaliza exatamente esse caso pendente.
        if (clips.any { previewKeyframes[it.uri.toString()] == null }) return SmartJoinPreviewOutcome(emptyList())
        val plannerSources = clips.mapIndexed { index, clip ->
            SmartJoinPlanner.Source(
                durationSeconds = clip.durationMs / 1000.0,
                profile = sourceProfiles[index].toSmartJoinProfile(),
                keyframesSeconds = previewKeyframes[clip.uri.toString()].orEmpty()
            )
        }
        val transitionSeconds = if (selectedTransition == TRANSITION_NONE) 0.0 else safeTransitionSeconds()
        val plan = SmartJoinPlanner.plan(plannerSources, transitionSeconds, isFadeInOutTransition())
        if (!plan.canSmartJoin) {
            return SmartJoinPreviewOutcome(
                emptyList(),
                plan.ineligibilityReason ?: "O SmartJoin não é aplicável a estes arquivos."
            )
        }
        if (plan.clips.none { it.copyVideo }) {
            return SmartJoinPreviewOutcome(
                emptyList(),
                "Nenhum corpo de vídeo pôde ser preservado por stream copy: o SmartJoin recodificaria tudo e não traria ganho."
            )
        }
        val encoderName = SmartJoinPlanner.compatibleEncoderNames(
            plan.targetProfile.codecFamily,
            selectedVideoEncoder?.ffmpegName,
            availableVideoEncoders.map { it.ffmpegName to it.codecFamily }
        ).firstOrNull()
            ?: return SmartJoinPreviewOutcome(
                emptyList(),
                "Nenhum encoder compatível com o perfil de destino do SmartJoin está disponível."
            )
        val encoder = availableVideoEncoders.firstOrNull { it.ffmpegName == encoderName }
            ?: return SmartJoinPreviewOutcome(emptyList(), "Encoder do SmartJoin indisponível.")
        val targetProfile = sourceProfiles[plan.targetIndex].copy(
            videoEncoder = encoder.ffmpegName,
            audioSampleRate = aggregate.audioSampleRate,
            audioChannels = aggregate.audioChannels,
            audioLayout = aggregate.audioLayout,
            audioBitrate = aggregate.audioBitrate
        )
        val outputAudioTracks = if (clips.any { it.hasAudio }) processingAudioTrackCount.coerceAtLeast(1) else 0
        val commands = mutableListOf<FfmpegCommandPresenter.PreviewCommand>()
        val pieces = mutableListOf<SmartJoinPiece>()
        plan.clips.forEachIndexed { index, clipPlan ->
            if (clipPlan.bodyDurationSeconds > SMART_JOIN_MIN_SEGMENT_SECONDS) {
                val ts = File("body_${index.toString().padStart(3, '0')}.ts")
                val encoded = if (clipPlan.copyVideo) ts else File("body_${index.toString().padStart(3, '0')}.mp4")
                commands += FfmpegCommandPresenter.PreviewCommand(
                    buildSmartJoinBodyArguments(
                        inputs[index], index, sourceProfiles[index], targetProfile, encoder,
                        clipPlan.bodyStartSeconds, clipPlan.bodyDurationSeconds, clipPlan.copyVideo,
                        outputAudioTracks, encoded, clipPlan.copyVideo
                    ).asIterable()
                )
                if (!clipPlan.copyVideo) {
                    commands += FfmpegCommandPresenter.PreviewCommand(
                        buildSmartJoinTsArguments(encoded, ts, targetProfile.videoCodec, outputAudioTracks).asIterable()
                    )
                }
                pieces += SmartJoinPiece(ts, clipPlan.bodyDurationSeconds)
            }
            plan.junctions.getOrNull(index)?.let { junction ->
                val mp4 = File("bridge_${index.toString().padStart(3, '0')}.mp4")
                val ts = File("bridge_${index.toString().padStart(3, '0')}.ts")
                val duration = smartJoinBridgeDuration(junction, plan.fadeInOut)
                commands += FfmpegCommandPresenter.PreviewCommand(
                    buildSmartJoinBridgeArguments(
                        inputs[index], inputs[index + 1], index, index + 1,
                        sourceProfiles[index], sourceProfiles[index + 1], targetProfile, encoder,
                        junction, plan.fadeInOut, outputAudioTracks, mp4
                    ).asIterable()
                )
                commands += FfmpegCommandPresenter.PreviewCommand(
                    buildSmartJoinTsArguments(mp4, ts, targetProfile.videoCodec, outputAudioTracks).asIterable()
                )
                pieces += SmartJoinPiece(ts, duration)
            }
        }
        commands += FfmpegCommandPresenter.PreviewCommand(
            smartJoinConcatPreviewArguments(targetProfile, outputAudioTracks).asIterable()
        )
        previewVideoRemux("mp4", targetProfile.videoCodec.equals("hevc", true))?.let {
            commands += FfmpegCommandPresenter.PreviewCommand(it)
        }
        return SmartJoinPreviewOutcome(commands)
    }

    private fun smartJoinConcatPreviewArguments(profile: OutputProfile, outputAudioTracks: Int): Array<String> = buildList {
        addAll(listOf(
            "-y", "-display_rotation:v:0", profile.rotationDegrees.toString(), "-fflags", "+genpts",
            "-f", "concat", "-safe", "0", "-i", File("input.txt").absolutePath,
            "-map", "0:v:0"
        ))
        if (outputAudioTracks > 0) addAll(listOf("-map", "0:a?"))
        addAll(listOf("-c", "copy"))
        if (outputAudioTracks > 0) addAll(listOf("-bsf:a", "aac_adtstoasc"))
        if (SmartJoinPlanner.normalizeCodec(profile.videoCodec) == "hevc") addAll(listOf("-tag:v", "hvc1"))
        addAll(listOf(
            "-avoid_negative_ts", "make_zero", "-max_interleave_delta", "0",
            "-video_track_timescale", "90000", "-movflags", "+faststart", File("output.mp4").absolutePath
        ))
    }.toTypedArray()

    private fun previewVideoRemux(inputExtension: String, hevc: Boolean): List<String>? {
        val outputExtension = clips.firstOrNull()?.name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        if (outputExtension !in setOf("mp4", "mov", "m4v", "3gp", "3g2", "avi", "mkv") ||
            outputExtension == inputExtension
        ) return null
        return buildList {
            addAll(listOf("-y", "-hide_banner", "-loglevel", "error", "-i", File("input.$inputExtension").absolutePath, "-c", "copy"))
            if (hevc && outputExtension in setOf("mp4", "mov", "m4v", "3gp", "3g2")) addAll(listOf("-tag:v", "hvc1"))
            if (outputExtension in setOf("mp4", "mov", "m4v", "3gp", "3g2")) addAll(listOf("-movflags", "+faststart"))
            add(File("output.$outputExtension").absolutePath)
        }
    }

    private fun adoptVideoEncoder(encoder: FfmpegVideoEncoder) {
        selectedVideoEncoder = encoder
        if (Looper.myLooper() == Looper.getMainLooper()) updateVideoEncoderButton()
        else runOnUiThread { updateVideoEncoderButton() }
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
                TRANSITIONS
            }
            transitions.forEach { transition ->
                menu.add(transition)
            }
            setOnMenuItemClickListener { item ->
                selectedTransition = item.title.toString()
                buttonTransition.text = "Transição: $selectedTransition"
                refreshCommandPreview()
                true
            }
            show()
        }
    }

    private fun showReencodeHelp() {
        val message = if (currentJoinIsAudio()) {
            "Sem reencodar, a junção usa -c copy e é rápida e sem perda, mas exige áudios compatíveis.\n\nCom reencode, o app normaliza taxa de amostragem, canais e bitrate, permitindo aplicar fade ou crossfade."
        } else {
            "Recodificar processa todos os vídeos usando o encoder e a qualidade escolhidos. É necessário para transições, orientações diferentes e arquivos incompatíveis, mas pode demorar mais e consumir mais bateria."
        }
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showSmartJoinHelp() {
        AlertDialog.Builder(this)
            .setTitle("SmartJoin")
            .setMessage(
                "Localiza os keyframes ao redor de cada emenda, copia os trechos longos sem perda e recodifica apenas as transições e as pequenas margens necessárias para cortes exatos.\n\n" +
                    "Clipes com codec, resolução, FPS, formato de pixel, proporção ou rotação incompatíveis são normalizados individualmente; os demais continuam em stream copy. O áudio é normalizado por segmento para manter sincronização exata.\n\n" +
                    "Se o aparelho ou o arquivo não permitir uma emenda segura, o SmartJoin interrompe com diagnóstico e não recodifica o arquivo inteiro silenciosamente."
            )
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

    private fun startJoin(
        subtitleRemovalConfirmed: Boolean = false,
        wavStandardizationConfirmed: Boolean = false,
        multitrackReductionConfirmed: Boolean = false
    ) {
        if (clips.size < 2) return
        val audioOnly = currentJoinIsAudio()
        val reencodeChecked = checkReencode.isChecked
        val smartJoinChecked = !audioOnly && checkSmartJoin.isChecked
        val processingRequested = reencodeChecked || smartJoinChecked
        val audioTrackCounts = clips.map { audioTrackCount(it.uri) }
        val hasMultitrackAudio = audioTrackCounts.any { it > 1 }
        val canPreserveAllAudioTracks = hasMultitrackAudio &&
            audioTrackCounts.all { it == audioTrackCounts.first() && it > 1 }
        if ((processingRequested || audioOnly) && hasMultitrackAudio && !canPreserveAllAudioTracks && !multitrackReductionConfirmed) {
            val detail = audioTrackCounts.mapIndexed { index, count -> "Arquivo ${index + 1}: $count faixa(s)" }.joinToString("\n")
            AlertDialog.Builder(this)
                .setTitle("As quantidades de faixas de áudio são diferentes")
                .setMessage("$detail\n\nNão é possível preservar todas por posição. Se continuar, você escolherá uma faixa de cada arquivo com múltiplas faixas; as demais serão descartadas.")
                .setPositiveButton("Escolher faixas") { _, _ ->
                    startJoin(
                        subtitleRemovalConfirmed = subtitleRemovalConfirmed,
                        wavStandardizationConfirmed = wavStandardizationConfirmed,
                        multitrackReductionConfirmed = true
                    )
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        if ((processingRequested || audioOnly) && hasMultitrackAudio && !canPreserveAllAudioTracks) {
            clips.firstOrNull {
                audioTrackCount(it.uri) > 1 && selectedAudioTracks[it.uri.toString()] == null
            }?.let { clip ->
                requestAudioTrack(clip) {
                    startJoin(
                        subtitleRemovalConfirmed = subtitleRemovalConfirmed,
                        wavStandardizationConfirmed = wavStandardizationConfirmed,
                        multitrackReductionConfirmed = true
                    )
                }
                return
            }
        }
        if (canPreserveAllAudioTracks || (!processingRequested && !audioOnly)) selectedAudioTracks.clear()
        processingAudioTrackCount = if (processingRequested && canPreserveAllAudioTracks) audioTrackCounts.first() else 1
        val hasSelectedMultitrackAudio = clips.any { selectedAudioTracks.containsKey(it.uri.toString()) }
        if (!audioOnly && processingRequested && !subtitleRemovalConfirmed && clips.any { subtitleTrackCount(it.uri) > 0 }) {
            AlertDialog.Builder(this)
                .setTitle("As legendas não podem participar das transições")
                .setMessage("A saída recodificada removerá as faixas de legenda. Os arquivos originais não serão alterados. Deseja continuar?")
                .setPositiveButton("Remover e continuar") { _, _ ->
                    startJoin(
                        subtitleRemovalConfirmed = true,
                        wavStandardizationConfirmed = wavStandardizationConfirmed,
                        multitrackReductionConfirmed = multitrackReductionConfirmed
                    )
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        val isFadeInOut = isFadeInOutTransition()
        val transitionSeconds = safeTransitionSeconds()
        val originalEncoder = selectedVideoEncoder
        processingVideoQuality = selectedVideoQuality
        if (!audioOnly && processingRequested && originalEncoder == null) {
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
                validateSupportedStreamTopology(copiedInputs, audioOnly)?.let { reason ->
                    throw IllegalArgumentException(reason)
                }
                val directConcatIncompatibility = directConcatCompatibilityError(copiedInputs)
                val audioPlan = FfmpegMediaPolicies.audioJoinPlan(
                    requestedReencode = processingRequested,
                    directCopyCompatible = directConcatIncompatibility == null,
                    selectedTrackReduction = hasSelectedMultitrackAudio
                )
                processingAudioTrackCount = FfmpegMediaPolicies.normalizedAudioTrackCount(
                    audioTrackCounts,
                    audioPlan,
                    selectedTrackReduction = hasSelectedMultitrackAudio
                )
                val audioNeedsNormalization = audioOnly && audioPlan.requiresReencode
                val audioWillStandardizeLosslessly = audioOnly && audioPlan.standardizeLosslessly
                if (audioWillStandardizeLosslessly && !wavStandardizationConfirmed) {
                    val extension = FfmpegMediaPolicies.losslessAudioStandardizationExtension(processingAudioTrackCount)
                    val preservesAllTracks = processingAudioTrackCount > 1
                    val title = if (preservesAllTracks) "A saída será convertida para MKA" else "A saída será convertida para WAV"
                    val trackNotice = when {
                        preservesAllTracks -> " Todas as $processingAudioTrackCount faixas serão preservadas separadamente em FLAC."
                        hasSelectedMultitrackAudio -> " Somente as faixas escolhidas entrarão na saída; as demais serão descartadas conforme confirmado."
                        else -> ""
                    }
                    runOnUiThread {
                        setProcessing(false)
                        AlertDialog.Builder(this)
                            .setTitle(title)
                            .setMessage("Os áudios não podem ser unidos com cópia direta porque seus parâmetros internos são diferentes. A saída usará $extension com codec sem perdas; taxa de amostragem e canais serão normalizados, e o arquivo poderá ficar maior.$trackNotice Deseja continuar?")
                            .setPositiveButton("Converter para ${extension.uppercase(Locale.ROOT)}") { _, _ ->
                                startJoin(
                                    subtitleRemovalConfirmed = subtitleRemovalConfirmed,
                                    wavStandardizationConfirmed = true,
                                    multitrackReductionConfirmed = multitrackReductionConfirmed
                                )
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                    return@Thread
                }
                val outputName = buildJoinedOutputName(forceAudioStandardization = audioWillStandardizeLosslessly)
                // O MediaCodec entrega AVCC/HVCC. Finalizar primeiro em MP4
                // evita a falha do muxer Matroska observada no Android; o
                // remux para o container original ocorre apos o encode.
                val intermediateName = if (audioOnly) {
                    // O encoder selecionado para vídeo pode permanecer em memória
                    // enquanto o usuário alterna para a junção de áudio. Não
                    // deixe esse estado alterar o contêiner/codec da saída de áudio.
                    outputName
                } else if (smartJoinChecked) {
                    // Todos os segmentos do SmartJoin convergem para MPEG-TS e
                    // são finalizados em MP4 antes do remux para o contêiner original.
                    outputNameWithExtension(outputName, "mp4")
                } else {
                    intermediateVideoOutputName(outputName, originalEncoder, reencodeChecked)
                }
                val tempOutput = File(cacheDir, "join_${System.currentTimeMillis()}_$intermediateName")
                val sourceProfile = detectAggregateOutputProfile(copiedInputs)
                val directConcatOrientationMismatch = !audioOnly && clips.size >= 2 && clips.map { rotationComparisonKey(it.rotationDegrees) }.distinct().size > 1
                if (!audioOnly) {
                    configureVideoProcessingPlan(
                        if (smartJoinChecked) smartJoinInitialProcessingLabels() else regularVideoProcessingLabels()
                    )
                }

                val result = if (audioOnly) {
                    executeAudioJoin(
                        copiedInputs,
                        tempOutput,
                        sourceProfile,
                        forceNormalization = audioNeedsNormalization,
                        requestedReencode = reencodeChecked
                    )
                } else if (smartJoinChecked) {
                    executeSmartJoin(copiedInputs, tempOutput, requireNotNull(originalEncoder))
                } else if (reencodeChecked) {
                    if (isFadeInOut) {
                        executeFadeInOutReencodeJoin(copiedInputs, tempOutput)
                    } else {
                        executeFullReencodeJoin(
                            copiedInputs,
                            tempOutput,
                            "Aplicando transições"
                        )
                    }
                } else {
                    if (!audioOnly && (directConcatIncompatibility != null || directConcatOrientationMismatch || hasSelectedMultitrackAudio)) {
                        JoinExecutionResult(
                            success = false,
                            cancelled = false,
                            failureMessage = directConcatIncompatibility
                                ?: "Vídeos com orientações diferentes ou uma faixa de áudio escolhida exigem processamento. Ative 'SmartJoin' ou 'Recodificar'."
                        )
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
                }

                var finalOutput = tempOutput
                var finalName = outputName
                if (result.success && !audioOnly) {
                    handler.post {
                        val convertStep = ProcessingStep("Converter para o formato original")
                        convertStep.startedAtMs = SystemClock.elapsedRealtime()
                        convertStep.state = StepState.RUNNING
                        processingSteps += convertStep
                        renderProcessingSteps()
                    }
                    val originalExtension = FfmpegOutputRemuxer.originalVideoExtension(clips.firstOrNull()?.name.orEmpty())
                    var remuxRan = false
                    val remux = FfmpegOutputRemuxer.remuxToOriginalContainer(
                        tempOutput,
                        originalExtension
                    ) { arguments ->
                        remuxRan = true
                        FfmpegCommandPresenter.show(status, arguments.asIterable())
                        Log.i(TAG, FfmpegMediaPolicies.formatCommand(arguments.asIterable()))
                    }
                    if (remux.converted) {
                        FfmpegCommandPresenter.completeLastShown(status, true)
                        finalOutput = remux.file
                    } else if (remuxRan) {
                        FfmpegCommandPresenter.completeLastShown(status, false)
                    }
                    val finalExtension = remux.file.extension.ifBlank { tempOutput.extension }
                    finalName = outputNameWithExtension(outputName, finalExtension)
                    updateStep("Converter para o formato original", 100, StepState.DONE)
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
                    tempOutputFiles.add(finalOutput)
                    lastOutputName = finalName
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
                    showJoinedPreview(finalOutput)
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

    private fun executeSmartJoin(
        inputs: List<File>,
        outputFile: File,
        requestedEncoder: FfmpegVideoEncoder
    ): JoinExecutionResult {
        updateStep(SMART_JOIN_ANALYZE_LABEL, 0, StepState.RUNNING)
        val sourceProfiles = inputs.mapIndexed { index, input ->
            applySelectedAudioProfile(input, clips.getOrNull(index), detectOutputProfile(input, clips.getOrNull(index)))
        }
        val plannerSources = inputs.mapIndexed { index, input ->
            updateStep(
                SMART_JOIN_ANALYZE_LABEL,
                ((index.toDouble() / inputs.size.coerceAtLeast(1)) * 90.0).toInt(),
                StepState.RUNNING,
                "keyframes ${index + 1}/${inputs.size}"
            )
            SmartJoinPlanner.Source(
                durationSeconds = clips[index].durationMs / 1000.0,
                profile = sourceProfiles[index].toSmartJoinProfile(),
                keyframesSeconds = detectVideoKeyframes(input)
            )
        }
        val transitionSeconds = if (selectedTransition == TRANSITION_NONE) 0.0 else safeTransitionSeconds()
        val plan = SmartJoinPlanner.plan(plannerSources, transitionSeconds, isFadeInOutTransition())
        plannerSources.forEachIndexed { index, source ->
            Log.i(
                TAG,
                "SmartJoin probe[$index]: codec=${source.profile.codecFamily}, ${source.profile.width}x${source.profile.height}, " +
                    "fps=${source.profile.fps}, pix=${source.profile.pixelFormat}, sar=${source.profile.sampleAspectRatio}, " +
                    "rot=${source.profile.rotationDegrees}, keyframes=${source.keyframesSeconds.size}, " +
                    "first=${source.keyframesSeconds.firstOrNull()}"
            )
        }
        Log.i(TAG, "SmartJoin plan: target=${plan.targetIndex}, copy=${plan.clips.map { it.copyVideo }}, ineligible=${plan.ineligibilityReason}")
        if (!plan.canSmartJoin) {
            return smartJoinFailure(
                outputFile,
                "${plan.ineligibilityReason.orEmpty()} O SmartJoin não recodifica o arquivo inteiro automaticamente."
            )
        }
        if (plan.clips.none { it.copyVideo }) {
            return smartJoinFailure(
                outputFile,
                "Nenhum corpo de vídeo pôde ser preservado por stream copy."
            )
        }

        val encoderNames = SmartJoinPlanner.compatibleEncoderNames(
            codecFamily = plan.targetProfile.codecFamily,
            selectedEncoderName = requestedEncoder.ffmpegName,
            encoders = availableVideoEncoders.map { it.ffmpegName to it.codecFamily }
        )
        val encoder = encoderNames.firstOrNull()?.let { name ->
            availableVideoEncoders.firstOrNull { it.ffmpegName == name }
        } ?: return smartJoinFailure(
            outputFile,
            "Não há encoder ${plan.targetProfile.codecFamily} para gerar emendas compatíveis com os corpos copiados."
        )
        if (encoder.ffmpegName != selectedVideoEncoder?.ffmpegName) adoptVideoEncoder(encoder)

        val aggregate = detectAggregateOutputProfile(inputs)
        val targetVideo = sourceProfiles[plan.targetIndex]
        val targetProfile = targetVideo.copy(
            videoEncoder = encoder.ffmpegName,
            audioSampleRate = aggregate.audioSampleRate,
            audioChannels = aggregate.audioChannels,
            audioLayout = aggregate.audioLayout,
            audioBitrate = aggregate.audioBitrate
        )
        val outputAudioTracks = if (clips.any { it.hasAudio }) processingAudioTrackCount else 0
        val taskLabels = buildList {
            add(SMART_JOIN_ANALYZE_LABEL)
            plan.clips.filter { it.bodyDurationSeconds > SMART_JOIN_MIN_SEGMENT_SECONDS }.forEach { clipPlan ->
                add(smartJoinBodyLabel(clipPlan, clips.size))
                add(smartJoinPrepareLabel("corpo", clipPlan.index + 1, clips.size))
            }
            plan.junctions.forEach { junction ->
                add(smartJoinBridgeLabel(junction.index, plan.junctions.size))
                add(smartJoinPrepareLabel("emenda", junction.index + 1, plan.junctions.size))
            }
            add(SMART_JOIN_FINALIZE_LABEL)
        }
        configureVideoProcessingPlan(taskLabels)
        val copiedCount = plan.clips.count { it.copyVideo }
        updateStep(
            SMART_JOIN_ANALYZE_LABEL,
            100,
            StepState.DONE,
            "$copiedCount/${plan.clips.size} corpos em stream copy; encoder ${encoder.shortName}"
        )

        val workDir = createSmartJoinWorkDir()
        val pieces = mutableListOf<SmartJoinPiece>()
        var failure: SmartJoinStepException? = null
        try {
            plan.clips.forEachIndexed { index, clipPlan ->
                if (failure != null) return@forEachIndexed
                if (clipPlan.bodyDurationSeconds > SMART_JOIN_MIN_SEGMENT_SECONDS) {
                    try {
                        // Corpos preserváveis vão diretamente para MPEG-TS.
                        // Para um corpo incompatível, o MediaCodec é mais
                        // estável quando finaliza primeiro em MP4 (AVC/HVCC)
                        // e só depois é convertido para TS; ainda assim
                        // somente esse trecho é recodificado.
                        val ts = File(workDir, "body_${index.toString().padStart(3, '0')}.ts")
                        val encoded = if (clipPlan.copyVideo) ts else {
                            File(workDir, "body_${index.toString().padStart(3, '0')}.mp4")
                        }
                        val label = smartJoinBodyLabel(clipPlan, clips.size)
                        val session = executeFfmpegWithProgress(
                            buildSmartJoinBodyArguments(
                                input = inputs[index],
                                clipIndex = index,
                                sourceProfile = sourceProfiles[index],
                                targetProfile = targetProfile,
                                encoder = encoder,
                                startSeconds = clipPlan.bodyStartSeconds,
                                durationSeconds = clipPlan.bodyDurationSeconds,
                                copyVideo = clipPlan.copyVideo,
                                outputAudioTracks = outputAudioTracks,
                                outputFile = encoded,
                                outputAsMpegTs = clipPlan.copyVideo
                            ),
                            (clipPlan.bodyDurationSeconds * 1000.0).toLong(),
                            label,
                            if (clipPlan.copyVideo) "copy+aac" else encoder.shortName
                        )
                        requireSmartJoinStep(session, encoded, label)
                        val prepareLabel = smartJoinPrepareLabel("corpo", index + 1, clips.size)
                        if (clipPlan.copyVideo) {
                            updateStep(prepareLabel, 100, StepState.DONE, "MPEG-TS direto; sem recodificar o corpo")
                        } else {
                            val remuxSession = executeFfmpegWithProgress(
                                buildSmartJoinTsArguments(encoded, ts, targetProfile.videoCodec, outputAudioTracks),
                                (clipPlan.bodyDurationSeconds * 1000.0).toLong(),
                                prepareLabel
                            )
                            requireSmartJoinStep(remuxSession, ts, prepareLabel)
                        }
                        pieces += SmartJoinPiece(ts, clipPlan.bodyDurationSeconds)
                    } catch (error: SmartJoinStepException) {
                        failure = error
                    }
                }

                val junction = plan.junctions.getOrNull(index)
                if (junction != null && failure == null) {
                    try {
                        val mp4 = File(workDir, "bridge_${index.toString().padStart(3, '0')}.mp4")
                        val ts = File(workDir, "bridge_${index.toString().padStart(3, '0')}.ts")
                        val bridgeDuration = smartJoinBridgeDuration(junction, plan.fadeInOut)
                        val label = smartJoinBridgeLabel(index, plan.junctions.size)
                        val session = executeFfmpegWithProgress(
                            buildSmartJoinBridgeArguments(
                                firstInput = inputs[index],
                                secondInput = inputs[index + 1],
                                firstClipIndex = index,
                                secondClipIndex = index + 1,
                                firstProfile = sourceProfiles[index],
                                secondProfile = sourceProfiles[index + 1],
                                targetProfile = targetProfile,
                                encoder = encoder,
                                junction = junction,
                                fadeInOut = plan.fadeInOut,
                                outputAudioTracks = outputAudioTracks,
                                outputFile = mp4
                            ),
                            (bridgeDuration * 1000.0).toLong(),
                            label,
                            encoder.shortName
                        )
                        requireSmartJoinStep(session, mp4, label)
                        val prepareLabel = smartJoinPrepareLabel("emenda", index + 1, plan.junctions.size)
                        val remuxSession = executeFfmpegWithProgress(
                            buildSmartJoinTsArguments(mp4, ts, targetProfile.videoCodec, outputAudioTracks),
                            (bridgeDuration * 1000.0).toLong(),
                            prepareLabel
                        )
                        requireSmartJoinStep(remuxSession, ts, prepareLabel)
                        pieces += SmartJoinPiece(ts, bridgeDuration)
                    } catch (error: SmartJoinStepException) {
                        failure = error
                    }
                }
            }

            if (failure == null && pieces.isEmpty()) {
                failure = SmartJoinStepException(false, "O SmartJoin não gerou segmentos.")
            }
            if (failure == null) {
                val expectedSeconds = plan.expectedDurationSeconds(plannerSources.map { it.durationSeconds })
                val session = executeFfmpegWithProgress(
                    buildSmartJoinConcatArguments(
                        pieces = pieces,
                        outputFile = outputFile,
                        profile = targetProfile,
                        outputAudioTracks = outputAudioTracks
                    ),
                    (expectedSeconds * 1000.0).toLong(),
                    SMART_JOIN_FINALIZE_LABEL
                )
                try {
                    requireSmartJoinStep(session, outputFile, SMART_JOIN_FINALIZE_LABEL)
                    validateSmartJoinDuration(outputFile, expectedSeconds, plan.junctions.size)
                } catch (error: SmartJoinStepException) {
                    failure = error
                }
            }
        } finally {
            cleanupSmartJoinWorkDir(workDir)
        }

        val error = failure
        if (error != null) {
            if (error.cancelled) return JoinExecutionResult(false, true, "")
            return smartJoinFailure(outputFile, error.message.orEmpty())
        }
        return JoinExecutionResult(true, false, "")
    }

    private fun smartJoinFailure(
        outputFile: File,
        reason: String
    ): JoinExecutionResult {
        val detail = reason.ifBlank { "emenda híbrida indisponível" }.take(180)
        Log.e(TAG, "SmartJoin failed without full-reencode fallback: $detail")
        outputFile.delete()
        updateStep(
            SMART_JOIN_ANALYZE_LABEL,
            100,
            StepState.DONE,
            "SmartJoin interrompido: $detail"
        )
        updateStep(SMART_JOIN_FINALIZE_LABEL, 0, StepState.ERROR, detail)
        return JoinExecutionResult(
            success = false,
            cancelled = false,
            failureMessage = "SmartJoin não concluiu sem recodificação total: $detail"
        )
    }

    private fun requireSmartJoinStep(session: FFmpegSession, outputFile: File, label: String) {
        if (ReturnCode.isCancel(session.returnCode)) throw SmartJoinStepException(true, "Operação cancelada.")
        if (!ReturnCode.isSuccess(session.returnCode) || !outputFile.exists() || outputFile.length() <= 0L) {
            throw SmartJoinStepException(false, ffmpegFailureMessage(label, session))
        }
    }

    private fun detectVideoKeyframes(input: File): List<Double> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(input.absolutePath)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return emptyList()
            extractor.selectTrack(videoTrack)
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val result = mutableListOf<Double>()
            while (extractor.sampleTime >= 0L) {
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    val seconds = extractor.sampleTime / 1_000_000.0
                    if (result.lastOrNull()?.let { kotlin.math.abs(it - seconds) > 0.0005 } != false) result += seconds
                }
                if (!extractor.advance()) break
            }
            result
        } catch (error: Throwable) {
            Log.w(TAG, "Não foi possível localizar keyframes em ${input.name}", error)
            emptyList()
        } finally {
            extractor.release()
        }
    }

    private fun OutputProfile.toSmartJoinProfile(): SmartJoinPlanner.VideoProfile =
        SmartJoinPlanner.VideoProfile(
            codecFamily = videoCodec,
            width = width,
            height = height,
            fps = fps.toDoubleOrNull() ?: 30.0,
            rotationDegrees = rotationDegrees,
            pixelFormat = pixFmt,
            sampleAspectRatio = sar,
            codecProfile = videoProfile
        )

    private fun smartJoinBodyLabel(plan: SmartJoinPlanner.ClipPlan, total: Int): String =
        if (plan.copyVideo) "Copiando corpo ${plan.index + 1}/$total" else "Recodificando clipe ${plan.index + 1}/$total"

    private fun smartJoinBridgeLabel(index: Int, total: Int): String =
        "Recodificando emenda ${index + 1}/$total"

    private fun smartJoinPrepareLabel(kind: String, index: Int, total: Int): String =
        "Preparando $kind $index/$total"

    private fun smartJoinBridgeDuration(
        junction: SmartJoinPlanner.JunctionPlan,
        fadeInOut: Boolean
    ): Double {
        val outgoing = junction.outgoingDurationSeconds - junction.outgoingBridgeStartSeconds
        val incoming = junction.incomingBridgeEndSeconds
        return if (fadeInOut) outgoing + incoming else outgoing + incoming - junction.incomingTransitionEndSeconds
    }

    private fun createSmartJoinWorkDir(): File {
        val root = cacheDir.canonicalFile
        val directory = File(root, "smart_join_${System.currentTimeMillis()}_${System.nanoTime()}").canonicalFile
        check(directory.parentFile == root && directory.name.startsWith("smart_join_")) {
            "Diretório temporário inválido para SmartJoin."
        }
        check(directory.mkdirs()) { "Não foi possível criar o diretório temporário do SmartJoin." }
        return directory
    }

    private fun cleanupSmartJoinWorkDir(directory: File) {
        val root = runCatching { cacheDir.canonicalFile }.getOrNull() ?: return
        val target = runCatching { directory.canonicalFile }.getOrNull() ?: return
        if (target.parentFile != root || !target.name.startsWith("smart_join_")) return
        target.listFiles()?.forEach { file -> if (file.isFile) file.delete() }
        target.delete()
    }

    private fun validateSmartJoinDuration(outputFile: File, expectedSeconds: Double, junctionCount: Int) {
        val retriever = MediaMetadataRetriever()
        val containerSeconds = try {
            retriever.setDataSource(outputFile.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000.0)
        } catch (_: Throwable) {
            null
        } finally {
            retriever.release()
        }
        // AAC/TS adds a small encoder delay and one or two video time-base
        // ticks at each boundary. Keep the acceptance window below 1% for
        // normal clips; a missing GOP (the failure we are guarding against)
        // is hundreds of milliseconds and must be rejected.
        val tolerance = maxOf(0.35, junctionCount * 0.12)
        if (containerSeconds != null && kotlin.math.abs(containerSeconds - expectedSeconds) > tolerance) {
            throw SmartJoinStepException(
                false,
                "Duração inesperada: ${formatDecimal(containerSeconds)}s; esperado ${formatDecimal(expectedSeconds)}s."
            )
        }

        val trackDurations = mutableListOf<Pair<String, Double>>()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(outputFile.absolutePath)
            repeat(extractor.trackCount) { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if ((mime.startsWith("video/") || mime.startsWith("audio/")) &&
                    format.containsKey(MediaFormat.KEY_DURATION)
                ) {
                    trackDurations += mime.substringBefore('/') to
                        (format.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0)
                }
            }
        } catch (_: Throwable) {
            // A validação do contêiner acima ainda protege o caminho em aparelhos
            // cujo extractor não expõe duração por faixa.
        } finally {
            extractor.release()
        }
        val videoSeconds = trackDurations.filter { it.first == "video" }.maxOfOrNull { it.second }
        val audioSeconds = trackDurations.filter { it.first == "audio" }.maxOfOrNull { it.second }
        if (videoSeconds != null && kotlin.math.abs(videoSeconds - expectedSeconds) > tolerance) {
            throw SmartJoinStepException(
                false,
                "Vídeo truncado: ${formatDecimal(videoSeconds)}s; esperado ${formatDecimal(expectedSeconds)}s."
            )
        }
        if (videoSeconds != null && audioSeconds != null && kotlin.math.abs(videoSeconds - audioSeconds) > tolerance) {
            throw SmartJoinStepException(
                false,
                "Faixas fora de sincronia: vídeo ${formatDecimal(videoSeconds)}s; áudio ${formatDecimal(audioSeconds)}s."
            )
        }
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
        forceNormalization: Boolean,
        requestedReencode: Boolean = checkReencode.isChecked
    ): JoinExecutionResult {
        val normalize = requestedReencode || forceNormalization
        val label = when {
            requestedReencode -> "Aplicando transição de áudio"
            forceNormalization -> "Convertendo áudios incompatíveis sem perdas no perfil agregado"
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
                standardizeLosslessly = forceNormalization && !requestedReencode
            )
        } else {
            buildDirectConcatArguments(inputs, outputFile)
        }
        val encoderName = when {
            !normalize -> null
            forceNormalization && !requestedReencode ->
                FfmpegMediaPolicies.losslessAudioStandardizationEncoder(outputFile.extension)
            else -> audioEncoderForOutput(outputFile, profile)
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
        standardizeLosslessly: Boolean = false
    ): Array<String> {
        val transitionSeconds = if (withTransition && selectedTransition != TRANSITION_NONE) {
            safeTransitionSeconds()
                .coerceAtMost((clips.minOfOrNull { it.durationMs } ?: 1L) / 2000.0)
                .coerceAtLeast(0.0)
        } else 0.0
        val normalizeFilter = audioJoinNormalizeFilter(profile)
        val outputLabels = (0 until processingAudioTrackCount).map(::audioOutputLabel)
        val filter = FfmpegMediaPolicies.audioJoinFilterComplex(
            inputs = clips.mapIndexed { index, clip ->
                FfmpegAudioJoinFilterInput(
                    durationSeconds = (clip.durationMs / 1000.0).coerceAtLeast(0.01),
                    audioInputSpecifiers = (0 until processingAudioTrackCount).map { track -> audioInputLabel(index, track) }
                )
            },
            normalizeFilter = normalizeFilter,
            outputLabels = outputLabels,
            transitionSeconds = transitionSeconds,
            fadeInOut = withTransition && isFadeInOutTransition(),
            crossfadeCurve = audioCrossfadeCurve().takeIf {
                withTransition && transitionSeconds > 0.0 && selectedTransition != TRANSITION_NONE
            }
        )
        val encoder = if (standardizeLosslessly) {
            FfmpegMediaPolicies.losslessAudioStandardizationEncoder(outputFile.extension)
        } else audioEncoderForOutput(outputFile, profile)
        return FfmpegMediaPolicies.joinAudioCommandArguments(
            inputPaths = inputs.map(File::getAbsolutePath),
            outputPath = outputFile.absolutePath,
            filterComplex = filter,
            encoder = encoder,
            sampleRate = profile.audioSampleRate,
            channels = profile.audioChannels,
            bitrate = profile.audioBitrate.takeIf { encoder !in setOf("flac", "pcm_s16le", "alac") },
            outputLabels = outputLabels
        )
    }

    private fun audioEncoderForOutput(outputFile: File, profile: OutputProfile): String {
        val codec = profile.audioCodec?.lowercase(Locale.ROOT)
        return when (outputFile.extension.lowercase(Locale.ROOT)) {
            "wav" -> "pcm_s16le"
            "flac" -> "flac"
            "mp3" -> "libmp3lame"
            "opus" -> "libopus"
            "ogg" -> if (codec == "opus") "libopus" else "libvorbis"
            "m4a", "mp4", "aac" -> "aac"
            else -> when (codec) {
                "flac" -> "flac"
                "mp3" -> "libmp3lame"
                "opus" -> "libopus"
                "vorbis" -> "libvorbis"
                "ac3" -> "ac3"
                else -> "aac"
            }
        }
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
        return FfmpegMediaPolicies.directConcatCommandArguments(listFile.absolutePath, outputFile.absolutePath)
    }

    private fun buildReencodeArguments(
        inputs: List<File>,
        outputFile: File,
        withTransition: Boolean,
        profileOverride: OutputProfile? = null
    ): Array<String> {
        val outputProfile = displayOrientedReencodeProfile(profileOverride ?: detectAggregateOutputProfile(inputs))
        val transitionSeconds = if (withTransition && selectedTransition != TRANSITION_NONE) safeTransitionSeconds() else 0.0
        val filter = buildFilterComplex(outputProfile, transitionSeconds)
        val videoEncoder = requireVideoEncoder()
        val args = mutableListOf("-y")
        inputs.forEach { input -> args.addAll(listOf("-i", input.absolutePath)) }
        args.addAll(listOf("-filter_complex", filter, "-map", "[vout]"))
        (0 until processingAudioTrackCount).forEach { args.addAll(listOf("-map", "[${audioOutputLabel(it)}]")) }
        args.addAll(videoEncodingArguments(outputProfile, constrained = true))
        if (videoEncoder.ffmpegName.endsWith("_mediacodec", ignoreCase = true)) {
            args.addAll(listOf("-g", mediaCodecGopSize(outputProfile.fps.toDoubleOrNull()).toString()))
        }
        args.addAll(listOf("-r", outputProfile.fps))
        args.addAll(audioEncodingArguments(outputProfile))
        args.addAll(listOf("-ar", outputProfile.audioSampleRate.toString(), "-ac", outputProfile.audioChannels.toString(), "-avoid_negative_ts", "make_zero", outputFile.absolutePath))
        return args.toTypedArray()
    }

    private fun buildFadeInOutReencodeArguments(
        inputs: List<File>,
        outputFile: File,
        profileOverride: OutputProfile? = null
    ): Array<String> {
        val outputProfile = displayOrientedReencodeProfile(profileOverride ?: detectAggregateOutputProfile(inputs))
        val transitionSeconds = safeTransitionSeconds()
        val filter = buildFadeInOutFilterComplex(outputProfile, transitionSeconds)
        val videoEncoder = requireVideoEncoder()
        val args = mutableListOf("-y")
        inputs.forEach { input -> args.addAll(listOf("-i", input.absolutePath)) }
        args.addAll(listOf("-filter_complex", filter, "-map", "[vout]"))
        (0 until processingAudioTrackCount).forEach { args.addAll(listOf("-map", "[${audioOutputLabel(it)}]")) }
        args.addAll(videoEncodingArguments(outputProfile, constrained = true))
        if (videoEncoder.ffmpegName.endsWith("_mediacodec", ignoreCase = true)) {
            args.addAll(listOf("-g", mediaCodecGopSize(outputProfile.fps.toDoubleOrNull()).toString()))
        }
        args.addAll(listOf("-r", outputProfile.fps))
        args.addAll(audioEncodingArguments(outputProfile))
        args.addAll(listOf("-ar", outputProfile.audioSampleRate.toString(), "-ac", outputProfile.audioChannels.toString(), "-avoid_negative_ts", "make_zero", outputFile.absolutePath))
        return args.toTypedArray()
    }

    private fun buildSmartJoinBodyArguments(
        input: File,
        clipIndex: Int,
        sourceProfile: OutputProfile,
        targetProfile: OutputProfile,
        encoder: FfmpegVideoEncoder,
        startSeconds: Double,
        durationSeconds: Double,
        copyVideo: Boolean,
        outputAudioTracks: Int,
        outputFile: File,
        outputAsMpegTs: Boolean = true
    ): Array<String> {
        // Os timestamps dos MP4 de origem já são válidos. Regenerar PTS aqui
        // (especialmente combinado com -ss + stream-copy) faz o FFmpeg 6 do
        // Android encerrar o último GOP antes do corte.
        val args = mutableListOf("-y")
        if (!copyVideo) {
            // Um arquivo incompatível pode carregar edit-list/PTS não
            // contínuos. Antes do filtro e do MediaCodec, gere PTS sintéticos;
            // o caminho de stream-copy deliberadamente não faz isso para não
            // perder o GOP final.
            args.addAll(listOf("-fflags", "+genpts"))
        }
        if (startSeconds > 0.0005) args.addAll(listOf("-ss", formatDecimal(startSeconds)))
        args.addAll(
            listOf(
                "-noautorotate",
                "-display_rotation:v:0", "0",
                "-i", input.absolutePath
            )
        )
        // Para TS, -t é aplicado ao relógio da peça depois do seek no
        // keyframe. O limite é controlado por -t; não usamos -shortest porque
        // no FFmpeg 6 Android ele pode descartar o último GOP do vídeo copy.
        args.addAll(listOf("-t", formatDecimal(durationSeconds)))

        val filters = mutableListOf<String>()
        if (!copyVideo) {
            filters += "[0:v:0]${smartJoinVideoNormalizationFilter(sourceProfile, targetProfile)}[vout]"
        }
        (0 until outputAudioTracks).forEach { track ->
            filters += smartJoinAudioWindowFilter(
                inputIndex = 0,
                clipIndex = clipIndex,
                outputTrack = track,
                durationSeconds = durationSeconds,
                targetProfile = targetProfile,
                outputLabel = "aout$track"
            )
        }
        if (filters.isNotEmpty()) args.addAll(listOf("-filter_complex", filters.joinToString(";")))
        args.addAll(listOf("-map", if (copyVideo) "0:v:0" else "[vout]"))
        (0 until outputAudioTracks).forEach { track -> args.addAll(listOf("-map", "[aout$track]")) }

        if (copyVideo) {
            args.addAll(listOf("-c:v", "copy"))
        } else {
            args.addAll(videoEncodingArguments(targetProfile, constrained = false, encoderOverride = encoder))
            args.addAll(smartJoinVideoEncoderTail(targetProfile, encoder))
        }
        if (outputAudioTracks > 0) {
            args.addAll(
                listOf(
                    "-c:a", "aac",
                    "-b:a", targetProfile.audioBitrate,
                    "-ar", targetProfile.audioSampleRate.toString(),
                    "-ac", targetProfile.audioChannels.toString(),
                    // Não usar -shortest nesta peça híbrida. No FFmpeg 6
                    // distribuído no Android, combinar -shortest com um
                    // vídeo em stream-copy e áudio filtrado encerra o vídeo
                    // no último DTS decodificado (antes do limite solicitado),
                    // deixando a peça com um GOP truncado.
                )
            )
        }
        args.addAll(listOf("-map_metadata", "-1", "-avoid_negative_ts", "make_zero"))
        if (outputAsMpegTs) {
            args.addAll(
                listOf(
                    "-bsf:v", if (SmartJoinPlanner.normalizeCodec(targetProfile.videoCodec) == "hevc") {
                        "hevc_mp4toannexb"
                    } else {
                        "h264_mp4toannexb"
                    },
                    "-mpegts_flags", "+resend_headers+initial_discontinuity",
                    "-muxdelay", "0",
                    "-muxpreload", "0",
                    "-f", "mpegts"
                )
            )
        } else {
            args.addAll(listOf("-video_track_timescale", "90000", "-movflags", "+faststart"))
        }
        args.add(outputFile.absolutePath)
        return args.toTypedArray()
    }

    private fun buildSmartJoinBridgeArguments(
        firstInput: File,
        secondInput: File,
        firstClipIndex: Int,
        secondClipIndex: Int,
        firstProfile: OutputProfile,
        secondProfile: OutputProfile,
        targetProfile: OutputProfile,
        encoder: FfmpegVideoEncoder,
        junction: SmartJoinPlanner.JunctionPlan,
        fadeInOut: Boolean,
        outputAudioTracks: Int,
        outputFile: File
    ): Array<String> {
        val transition = junction.incomingTransitionEndSeconds
        val outgoingWindow = junction.outgoingDurationSeconds - junction.outgoingBridgeStartSeconds
        val outgoingPrefix = junction.outgoingTransitionStartSeconds - junction.outgoingBridgeStartSeconds
        val incomingWindow = junction.incomingBridgeEndSeconds
        val incomingSuffix = incomingWindow - transition
        val expectedDuration = smartJoinBridgeDuration(junction, fadeInOut)
        val args = mutableListOf("-y", "-fflags", "+genpts")
        if (junction.outgoingBridgeStartSeconds > 0.0005) {
            args.addAll(listOf("-ss", formatDecimal(junction.outgoingBridgeStartSeconds)))
        }
        args.addAll(listOf("-noautorotate", "-display_rotation:v:0", "0", "-i", firstInput.absolutePath))
        args.addAll(listOf("-noautorotate", "-display_rotation:v:0", "0", "-i", secondInput.absolutePath))

        val filters = mutableListOf<String>()
        filters += "[0:v:0]trim=duration=${formatDecimal(outgoingWindow)},${smartJoinVideoNormalizationFilter(firstProfile, targetProfile)}[ovbase]"
        filters += "[1:v:0]trim=duration=${formatDecimal(incomingWindow)},${smartJoinVideoNormalizationFilter(secondProfile, targetProfile)}[ivbase]"
        if (fadeInOut) {
            filters += "[ovbase]fade=t=out:st=${formatDecimal((outgoingWindow - transition).coerceAtLeast(0.0))}:d=${formatDecimal(transition)}[ovfade]"
            filters += "[ivbase]fade=t=in:st=0:d=${formatDecimal(transition)}[ivfade]"
            filters += "[ovfade][ivfade]concat=n=2:v=1:a=0[vout]"
        } else {
            val videoSequence = mutableListOf<String>()
            if (outgoingPrefix > SMART_JOIN_MIN_SEGMENT_SECONDS) {
                filters += "[ovbase]split=2[ovprefixsrc][ovtailsrc]"
                filters += "[ovprefixsrc]trim=duration=${formatDecimal(outgoingPrefix)},setpts=PTS-STARTPTS[ovprefix]"
                filters += "[ovtailsrc]trim=start=${formatDecimal(outgoingPrefix)}:duration=${formatDecimal(transition)},setpts=PTS-STARTPTS[ovtail]"
                videoSequence += "ovprefix"
            } else {
                filters += "[ovbase]trim=duration=${formatDecimal(transition)},setpts=PTS-STARTPTS[ovtail]"
            }
            if (incomingSuffix > SMART_JOIN_MIN_SEGMENT_SECONDS) {
                filters += "[ivbase]split=2[ivheadsrc][ivsuffixsrc]"
                filters += "[ivheadsrc]trim=duration=${formatDecimal(transition)},setpts=PTS-STARTPTS[ivhead]"
                filters += "[ivsuffixsrc]trim=start=${formatDecimal(transition)}:duration=${formatDecimal(incomingSuffix)},setpts=PTS-STARTPTS[ivsuffix]"
            } else {
                filters += "[ivbase]trim=duration=${formatDecimal(transition)},setpts=PTS-STARTPTS[ivhead]"
            }
            filters += "[ovtail][ivhead]xfade=transition=${xfadeTransitionName()}:duration=${formatDecimal(transition)}:offset=0[vxfade]"
            videoSequence += "vxfade"
            if (incomingSuffix > SMART_JOIN_MIN_SEGMENT_SECONDS) videoSequence += "ivsuffix"
            filters += smartJoinVideoConcatFilter(videoSequence, "vout")
        }

        (0 until outputAudioTracks).forEach { track ->
            val outgoingBase = "oabase$track"
            val incomingBase = "iabase$track"
            filters += smartJoinAudioWindowFilter(
                inputIndex = 0,
                clipIndex = firstClipIndex,
                outputTrack = track,
                durationSeconds = outgoingWindow,
                targetProfile = targetProfile,
                outputLabel = outgoingBase
            )
            filters += smartJoinAudioWindowFilter(
                inputIndex = 1,
                clipIndex = secondClipIndex,
                outputTrack = track,
                durationSeconds = incomingWindow,
                targetProfile = targetProfile,
                outputLabel = incomingBase
            )
            if (fadeInOut) {
                filters += "[$outgoingBase]afade=t=out:st=${formatDecimal((outgoingWindow - transition).coerceAtLeast(0.0))}:d=${formatDecimal(transition)}[oafade$track]"
                filters += "[$incomingBase]afade=t=in:st=0:d=${formatDecimal(transition)}[iafade$track]"
                filters += "[oafade$track][iafade$track]concat=n=2:v=0:a=1[aout$track]"
            } else {
                val audioSequence = mutableListOf<String>()
                if (outgoingPrefix > SMART_JOIN_MIN_SEGMENT_SECONDS) {
                    filters += "[$outgoingBase]asplit=2[oaprefixsrc$track][oatailsrc$track]"
                    filters += "[oaprefixsrc$track]atrim=duration=${formatDecimal(outgoingPrefix)},asetpts=N/SR/TB[oaprefix$track]"
                    filters += "[oatailsrc$track]atrim=start=${formatDecimal(outgoingPrefix)}:duration=${formatDecimal(transition)},asetpts=N/SR/TB[oatail$track]"
                    audioSequence += "oaprefix$track"
                } else {
                    filters += "[$outgoingBase]atrim=duration=${formatDecimal(transition)},asetpts=N/SR/TB[oatail$track]"
                }
                if (incomingSuffix > SMART_JOIN_MIN_SEGMENT_SECONDS) {
                    filters += "[$incomingBase]asplit=2[iaheadsrc$track][iasuffixsrc$track]"
                    filters += "[iaheadsrc$track]atrim=duration=${formatDecimal(transition)},asetpts=N/SR/TB[iahead$track]"
                    filters += "[iasuffixsrc$track]atrim=start=${formatDecimal(transition)}:duration=${formatDecimal(incomingSuffix)},asetpts=N/SR/TB[iasuffix$track]"
                } else {
                    filters += "[$incomingBase]atrim=duration=${formatDecimal(transition)},asetpts=N/SR/TB[iahead$track]"
                }
                filters += "[oatail$track][iahead$track]acrossfade=d=${formatDecimal(transition)}:c1=tri:c2=tri[axfade$track]"
                audioSequence += "axfade$track"
                if (incomingSuffix > SMART_JOIN_MIN_SEGMENT_SECONDS) audioSequence += "iasuffix$track"
                filters += smartJoinAudioConcatFilter(audioSequence, "aout$track")
            }
        }

        args.addAll(listOf("-filter_complex", filters.joinToString(";"), "-map", "[vout]"))
        (0 until outputAudioTracks).forEach { track -> args.addAll(listOf("-map", "[aout$track]")) }
        args.addAll(videoEncodingArguments(targetProfile, constrained = false, encoderOverride = encoder))
        args.addAll(smartJoinVideoEncoderTail(targetProfile, encoder))
        if (outputAudioTracks > 0) {
            args.addAll(
                listOf(
                    "-c:a", "aac",
                    "-b:a", targetProfile.audioBitrate,
                    "-ar", targetProfile.audioSampleRate.toString(),
                    "-ac", targetProfile.audioChannels.toString()
                )
            )
        }
        args.addAll(
            listOf(
                "-t", formatDecimal(expectedDuration),
                "-map_metadata", "-1",
                "-avoid_negative_ts", "make_zero",
                "-video_track_timescale", "90000",
                "-movflags", "+faststart",
                outputFile.absolutePath
            )
        )
        return args.toTypedArray()
    }

    private fun smartJoinVideoNormalizationFilter(source: OutputProfile, target: OutputProfile): String {
        val filters = mutableListOf("setpts=PTS-STARTPTS")
        if (rotationComparisonKey(source.rotationDegrees) != rotationComparisonKey(target.rotationDegrees)) {
            filters += FfmpegMediaPolicies.physicalRotationFilters(source.rotationDegrees)
            filters += FfmpegMediaPolicies.physicalRotationFilters(-target.rotationDegrees)
        }
        filters += "scale=${target.width}:${target.height}:force_original_aspect_ratio=decrease"
        filters += "pad=${target.width}:${target.height}:(ow-iw)/2:(oh-ih)/2"
        val sar = target.sar?.takeIf { it.matches(Regex("\\d+:\\d+")) }?.replace(':', '/') ?: "1"
        filters += "setsar=$sar"
        filters += "fps=${target.fps}"
        filters += "format=yuv420p"
        filters += "settb=AVTB"
        filters += "setpts=PTS-STARTPTS"
        return filters.joinToString(",")
    }

    private fun smartJoinAudioWindowFilter(
        inputIndex: Int,
        clipIndex: Int,
        outputTrack: Int,
        durationSeconds: Double,
        targetProfile: OutputProfile,
        outputLabel: String
    ): String {
        val duration = formatDecimal(durationSeconds)
        val clip = clips[clipIndex]
        return if (clip.hasAudio) {
            val sourceTrack = if (processingAudioTrackCount > 1) outputTrack
            else selectedAudioTracks[clip.uri.toString()] ?: 0
            "[$inputIndex:a:$sourceTrack]aresample=${targetProfile.audioSampleRate}:async=1:first_pts=0," +
                "aformat=sample_fmts=fltp:sample_rates=${targetProfile.audioSampleRate}:channel_layouts=${targetProfile.audioLayout}," +
                "atrim=duration=$duration,asetpts=N/SR/TB[$outputLabel]"
        } else {
            "anullsrc=channel_layout=${targetProfile.audioLayout}:sample_rate=${targetProfile.audioSampleRate}," +
                "atrim=duration=$duration,asetpts=N/SR/TB[$outputLabel]"
        }
    }

    private fun smartJoinVideoConcatFilter(labels: List<String>, outputLabel: String): String {
        require(labels.isNotEmpty())
        return if (labels.size == 1) "[${labels.single()}]null[$outputLabel]"
        else labels.joinToString("") { "[$it]" } + "concat=n=${labels.size}:v=1:a=0[$outputLabel]"
    }

    private fun smartJoinAudioConcatFilter(labels: List<String>, outputLabel: String): String {
        require(labels.isNotEmpty())
        return if (labels.size == 1) "[${labels.single()}]anull[$outputLabel]"
        else labels.joinToString("") { "[$it]" } + "concat=n=${labels.size}:v=0:a=1[$outputLabel]"
    }

    private fun smartJoinVideoEncoderTail(profile: OutputProfile, encoder: FfmpegVideoEncoder): List<String> = buildList {
        addAll(listOf("-pix_fmt", "yuv420p", "-r", profile.fps, "-fps_mode", "cfr"))
        addAll(listOf("-g", mediaCodecGopSize(profile.fps.toDoubleOrNull()).toString()))
        val codecProfile = profile.videoProfile?.lowercase(Locale.ROOT).orEmpty()
        val ffmpegProfile = when {
            SmartJoinPlanner.normalizeCodec(profile.videoCodec) == "hevc" && "main" in codecProfile -> "main"
            "baseline" in codecProfile -> "baseline"
            "main" in codecProfile -> "main"
            "high" in codecProfile -> "high"
            else -> null
        }
        val mediaCodec = encoder.ffmpegName.endsWith("_mediacodec", ignoreCase = true)
        // O wrapper MediaCodec desta build rejeita -profile:v mesmo quando o
        // perfil coincide com a origem. Os cabeçalhos são repetidos no TS e o
        // contêiner tolera a troca; libx264 aceita e recebe o perfil explícito.
        if (!mediaCodec) ffmpegProfile?.let { addAll(listOf("-profile:v", it)) }
        if (mediaCodec) addAll(listOf("-bf", "0"))
    }

    private fun buildSmartJoinTsArguments(
        inputFile: File,
        outputFile: File,
        videoCodec: String,
        outputAudioTracks: Int
    ): Array<String> {
        val bitstreamFilter = if (SmartJoinPlanner.normalizeCodec(videoCodec) == "hevc") {
            "hevc_mp4toannexb"
        } else {
            "h264_mp4toannexb"
        }
        return buildList {
            addAll(listOf("-y", "-i", inputFile.absolutePath, "-map", "0:v:0"))
            if (outputAudioTracks > 0) addAll(listOf("-map", "0:a?"))
            addAll(
                listOf(
                    "-c", "copy",
                    "-bsf:v", bitstreamFilter,
                    "-avoid_negative_ts", "make_zero",
                    // Cada peça começa uma nova linha temporal. O sinalizador de
                    // descontinuidade permite ao demuxer MPEG-TS recompor PTS/DTS
                    // sem perder o GOP final da peça anterior.
                    "-mpegts_flags", "+resend_headers+initial_discontinuity",
                    "-muxdelay", "0",
                    "-muxpreload", "0",
                    "-f", "mpegts",
                    outputFile.absolutePath
                )
            )
        }.toTypedArray()
    }

    private fun buildSmartJoinConcatArguments(
        pieces: List<SmartJoinPiece>,
        outputFile: File,
        profile: OutputProfile,
        outputAudioTracks: Int
    ): Array<String> = buildList {
        require(pieces.isNotEmpty())
        // O protocolo concat: apenas cola bytes dos TS e reinicia os DTS a
        // cada peça. Isso produz regressões audíveis e avisos de DTS fora de
        // ordem no ponto de junção. O demuxer concat calcula o deslocamento
        // temporal de cada arquivo e mantém uma linha do tempo contínua sem
        // recodificar os corpos.
        val manifest = File(
            pieces.first().file.parentFile,
            "smart_join_concat_${System.nanoTime()}.txt"
        )
        manifest.writeText(
            pieces.joinToString("\n") { piece ->
                "file '${piece.file.absolutePath.replace("\\", "/")}'"
            },
            Charsets.UTF_8
        )
        addAll(
            listOf(
                "-y",
                "-display_rotation:v:0", profile.rotationDegrees.toString(),
                "-fflags", "+genpts",
                "-f", "concat",
                "-safe", "0",
                "-i", manifest.absolutePath,
                "-map", "0:v:0"
            )
        )
        if (outputAudioTracks > 0) addAll(listOf("-map", "0:a?"))
        addAll(listOf("-c", "copy"))
        if (outputAudioTracks > 0) addAll(listOf("-bsf:a", "aac_adtstoasc"))
        if (SmartJoinPlanner.normalizeCodec(profile.videoCodec) == "hevc") addAll(listOf("-tag:v", "hvc1"))
        addAll(
            listOf(
                "-avoid_negative_ts", "make_zero",
                "-max_interleave_delta", "0",
                "-video_track_timescale", "90000",
                "-movflags", "+faststart",
                outputFile.absolutePath
            )
        )
    }.toTypedArray()

    private fun audioEncodingArguments(profile: OutputProfile): List<String> {
        val encoder = when (profile.audioCodec?.lowercase(Locale.ROOT)) {
            "opus" -> "libopus"
            "vorbis" -> "libvorbis"
            "flac" -> "flac"
            "mp3" -> "libmp3lame"
            "ac3" -> "ac3"
            else -> "aac"
        }
        return if (encoder == "flac") listOf("-c:a", encoder)
        else listOf("-c:a", encoder, "-b:a", profile.audioBitrate)
    }

    private fun buildFadeInOutFilterComplex(profile: OutputProfile, transitionSeconds: Double): String {
        return FfmpegMediaPolicies.videoJoinFilterComplex(
            inputs = videoJoinFilterInputs(),
            videoFilter = videoFillFrameFilter(profile.width, profile.height, profile.fps),
            sampleRate = profile.audioSampleRate,
            audioLayout = profile.audioLayout,
            outputAudioLabels = (0 until processingAudioTrackCount).map(::audioOutputLabel),
            transitionSeconds = transitionSeconds,
            fadeInOut = true,
            xfadeTransition = "fade"
        )
    }

    private fun buildFilterComplex(profile: OutputProfile, transitionSeconds: Double): String {
        return FfmpegMediaPolicies.videoJoinFilterComplex(
            inputs = videoJoinFilterInputs(),
            videoFilter = videoFillFrameFilter(profile.width, profile.height, profile.fps),
            sampleRate = profile.audioSampleRate,
            audioLayout = profile.audioLayout,
            outputAudioLabels = (0 until processingAudioTrackCount).map(::audioOutputLabel),
            transitionSeconds = transitionSeconds,
            fadeInOut = false,
            xfadeTransition = xfadeTransitionName(),
            audioCrossfadeCurve = "tri"
        )
    }

    private fun videoJoinFilterInputs(): List<FfmpegVideoJoinFilterInput> =
        clips.mapIndexed { index, clip ->
            FfmpegVideoJoinFilterInput(
                durationSeconds = (clip.durationMs / 1000.0).coerceAtLeast(0.001),
                hasAudio = clip.hasAudio,
                audioInputSpecifiers = if (clip.hasAudio) {
                    (0 until processingAudioTrackCount).map { track -> audioInputLabel(index, track) }
                } else emptyList()
            )
        }

    private fun videoFillFrameFilter(width: Int, height: Int, fps: String): String {
        return "scale=$width:$height:force_original_aspect_ratio=decrease," +
            "pad=$width:$height:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=$fps,format=yuv420p"
    }

    private fun safeTransitionSeconds(): Double {
        val requested = inputTransitionTime.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.5
        val maxSafe = maxSafeTransitionSeconds()
        val safe = requested.coerceIn(0.0, maxSafe)
        if (requested > maxSafe && clips.isNotEmpty() && maxSafe > 0.0) {
            val formattedSafe = formatDecimal(safe)
            Log.i(TAG, "Transição solicitada (${requested}s) ajustada para ${formattedSafe}s devido à duração do clipe mais curto.")
            runOnUiThread {
                inputTransitionTime.setText(formattedSafe)
                Toast.makeText(
                    this,
                    "Tempo de transição ajustado para ${formattedSafe}s devido ao clipe mais curto.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        return safe
    }

    private fun maxSafeTransitionSeconds(): Double {
        val shortest = clips.minOfOrNull { it.durationMs / 1000.0 } ?: 0.0
        return (shortest - 0.1).coerceAtLeast(0.0)
    }

    private fun executeFfmpegWithProgress(
        arguments: Array<String>,
        expectedDurationMs: Long,
        taskLabel: String,
        encoderName: String? = null
    ): FFmpegSession {
        FfmpegCommandPresenter.show(status, arguments.asIterable())
        Log.i(TAG, "FFmpeg: ${FfmpegMediaPolicies.formatCommand(arguments.asIterable())}")
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
        arguments.firstOrNull { argument ->
            File(argument).name.startsWith("join_list_")
        }?.let { listPath -> runCatching { File(listPath).delete() } }
        when {
            ReturnCode.isSuccess(completedSession.returnCode) -> {
                updateStep(
                    taskLabel,
                    100,
                    StepState.DONE,
                    encoderName = encoderName,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt
                )
                FfmpegCommandPresenter.completeLastShown(status, true)
            }
            ReturnCode.isCancel(completedSession.returnCode) -> {
                updateStep(taskLabel, null, StepState.ERROR, "cancelado")
                FfmpegCommandPresenter.completeLastShown(status, false)
            }
            else -> {
                updateStep(taskLabel, null, StepState.ERROR)
                FfmpegCommandPresenter.completeLastShown(status, false)
            }
        }
        return completedSession
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
        checkReencode.isEnabled = !processing
        checkSmartJoin.isEnabled = !processing && !currentJoinIsAudio()
        timeline.isEnabled = !processing
        buttonSelectOutputFolder.isEnabled = !processing
        buttonSelectVideos.isEnabled = !processing
        joinPlayPause.isEnabled = !processing
        joinSpeedDown.isEnabled = !processing
        joinSpeedUp.isEnabled = !processing
        updateReencodeControls(refreshPreview = false)
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
            val outputMime = if (currentJoinIsAudio()) audioMimeType(outputName) else FfmpegMediaPolicies.videoMimeForName(outputName)
            val targetName = FfmpegMediaPolicies.uniqueOutputName(outputName) { candidate ->
                destDir.findFile(candidate) != null
            }
            val document = destDir.createFile(outputMime, targetName)
            if (document == null) {
                status.text = "Erro ao criar arquivo na pasta selecionada."
                return
            }
            contentResolver.openOutputStream(document.uri)?.use { output ->
                tempFile.inputStream().use { input -> input.copyTo(output) }
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
            setDataAndType(uri, if (currentJoinIsAudio()) audioMimeType(lastOutputName) else FfmpegMediaPolicies.videoMimeForName(lastOutputName))
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
                type = if (currentJoinIsAudio()) audioMimeType(lastOutputName) else FfmpegMediaPolicies.videoMimeForName(lastOutputName)
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
        lastOutputUri = null
        lastOutputName = ""
        processingSteps.clear()
        outputFileName.text = ""
        outputFileName.visibility = View.GONE
        outputActions.visibility = View.GONE
        outputStats.visibility = View.GONE
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
        val safeMilliseconds = milliseconds.coerceAtLeast(0L)
        val totalSeconds = safeMilliseconds / 1000
        val hours = totalSeconds / 3600
        val m = (totalSeconds / 60) % 60
        val s = totalSeconds % 60
        val millis = safeMilliseconds % 1000
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, m, s, millis)
    }

    private fun regularVideoProcessingLabels(): List<String> {
        if (!checkReencode.isChecked) return listOf("Juntando sem reencodar")
        return if (isFadeInOutTransition()) {
            listOf("Aplicando Fade in/out")
        } else {
            listOf("Aplicando transições")
        }
    }

    private fun smartJoinInitialProcessingLabels(): List<String> = listOf(
        SMART_JOIN_ANALYZE_LABEL,
        SMART_JOIN_FINALIZE_LABEL
    )

    private fun initProcessingSteps() {
        processingSteps.clear()
        processingSteps += ProcessingStep("Preparar arquivos de entrada")
        if (currentJoinIsAudio()) {
            val label = if (checkReencode.isChecked) {
                "Aplicando transição de áudio"
            } else {
                "Juntando áudios sem reencodar"
            }
            processingSteps += ProcessingStep(label)
            processingSteps += ProcessingStep("Preparar arquivo para salvar")
            renderProcessingSteps()
            return
        }
        val videoLabels = if (checkSmartJoin.isChecked) smartJoinInitialProcessingLabels() else regularVideoProcessingLabels()
        videoLabels.forEach { processingSteps += ProcessingStep(it) }
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
            val existingByLabel = if (preserveExistingTaskState) processingSteps.associateBy { it.label } else emptyMap()
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

    private fun isFadeInOutTransition(): Boolean = selectedTransition == TRANSITION_FADE_IN_OUT

    private fun audioCrossfadeCurve(): String {
        return AUDIO_TRANSITIONS[selectedTransition] ?: "tri"
    }

    private fun xfadeTransitionName(): String {
        return if (isFadeInOutTransition()) "fade" else VIDEO_TRANSITION_VALUES[selectedTransition] ?: "fade"
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
            
            // Estatisticas nao sao coladas no status (que tem so os passos);
            // elas aparecem no output_stats, apos os botoes de saida. Assim o
            // usuario ve Salvar/Compartilhar logo apos o passo-a-passo.
            if (stats.isNotBlank()) {
                outputStats.text = "Estatísticas:\n$stats"
                outputStats.visibility = View.VISIBLE
            }
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
            if (forceAudioStandardization && processingAudioTrackCount > 1) "mka"
            else if (forceAudioStandardization) "wav"
            else if (processingAudioTrackCount > 1) "mka"
            else if (checkReencode.isChecked) {
                clips.firstOrNull()?.name?.substringAfterLast('.', "m4a")?.lowercase(Locale.ROOT)
                    ?.takeIf { it in setOf("wav", "flac", "mp3", "ogg", "opus", "m4a", "aac") }
                    ?: "m4a"
            }
            else clips.firstOrNull()?.name?.substringAfterLast('.', "m4a")?.lowercase(Locale.ROOT) ?: "m4a"
        } else {
            "mkv"
        }
        return "$baseName.$extension"
    }

    private fun intermediateVideoOutputName(
        outputName: String,
        encoder: FfmpegVideoEncoder?,
        reencode: Boolean
    ): String {
        val outputExtension = outputName.substringAfterLast('.', "")
        val intermediateExtension = FfmpegOutputRemuxer.intermediateVideoExtension(
            outputExtension,
            encoder?.ffmpegName,
            reencode
        )
        return outputNameWithExtension(outputName, intermediateExtension)
    }

    private fun outputNameWithExtension(name: String, extension: String): String {
        if (extension.isBlank()) return name
        val base = name.substringBeforeLast('.', name)
        return "$base.$extension"
    }

    private fun currentJoinIsAudio(): Boolean = clips.isNotEmpty() && clips.all { it.isAudio }

    private fun directConcatCompatibilityError(inputs: List<File>): String? {
        if (inputs.size < 2) return null
        val signatures = inputs.map(::streamCopySignatures)
        return if (FfmpegMediaPolicies.directConcatSignaturesCompatible(signatures)) null
        else "Os arquivos possuem codecs, perfis ou parâmetros internos diferentes. Ative 'SmartJoin' ou 'Recodificar'."
    }

    private fun streamCopySignatures(file: File): List<FfmpegStreamCopySignature>? {
        val descriptors = ffmpegStreamCopyDescriptors(file) ?: return null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            if (descriptors.size != extractor.trackCount) return null
            val containerFamily = ffmpegContainerFamily(file) ?: return null
            (0 until extractor.trackCount).map { index ->
                val format = extractor.getTrackFormat(index)
                fun intValue(key: String): Int? = runCatching { format.getInteger(key) }.getOrNull()
                fun doubleValue(key: String): Double? =
                    intValue(key)?.toDouble() ?: runCatching { format.getFloat(key).toDouble() }.getOrNull()
                fun stringValue(key: String): String? = runCatching { format.getString(key) }.getOrNull()
                fun csdHash(key: String): Int? = runCatching {
                    val buffer = format.getByteBuffer(key)?.duplicate() ?: return@runCatching null
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    bytes.contentHashCode()
                }.getOrNull()
                FfmpegStreamCopySignature(
                    containerFamily = containerFamily,
                    ffmpegDescriptor = descriptors[index],
                    mime = format.getString(MediaFormat.KEY_MIME).orEmpty(),
                    profile = intValue(MediaFormat.KEY_PROFILE),
                    level = intValue(MediaFormat.KEY_LEVEL),
                    sampleRate = intValue(MediaFormat.KEY_SAMPLE_RATE),
                    channels = intValue(MediaFormat.KEY_CHANNEL_COUNT),
                    channelMask = intValue(MediaFormat.KEY_CHANNEL_MASK),
                    pcmEncoding = intValue(MediaFormat.KEY_PCM_ENCODING),
                    width = intValue(MediaFormat.KEY_WIDTH),
                    height = intValue(MediaFormat.KEY_HEIGHT),
                    frameRate = doubleValue(MediaFormat.KEY_FRAME_RATE),
                    colorStandard = intValue(MediaFormat.KEY_COLOR_STANDARD),
                    colorTransfer = intValue(MediaFormat.KEY_COLOR_TRANSFER),
                    colorRange = intValue(MediaFormat.KEY_COLOR_RANGE),
                    codecTag = stringValue("codec-tag"),
                    sampleFormat = stringValue("sample-format"),
                    channelLayout = stringValue("channel-layout"),
                    timeBase = stringValue("time-base"),
                    csd0 = csdHash("csd-0"),
                    csd1 = csdHash("csd-1"),
                    csd2 = csdHash("csd-2")
                )
            }
        } catch (_: Throwable) {
            null
        } finally {
            extractor.release()
        }
    }

    private fun ffmpegStreamCopyDescriptors(file: File): List<String>? {
        return try {
            val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-i", file.absolutePath))
            val streamPattern = Regex(
                """Stream #\d+:\d+(?:\[[^]]+])?(?:\([^)]*\))?:\s*(Video|Audio|Subtitle|Data|Attachment):\s*(.+)""",
                RegexOption.IGNORE_CASE
            )
            session.allLogsAsString.orEmpty().lines().mapNotNull { line ->
                val match = streamPattern.find(line) ?: return@mapNotNull null
                val type = match.groupValues[1].lowercase(Locale.ROOT)
                val details = match.groupValues[2]
                    .lowercase(Locale.ROOT)
                    .replace(Regex(""",\s*\d+(?:\.\d+)?\s*kb/s\b""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                "$type:$details"
            }.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun ffmpegContainerFamily(file: File): String? {
        return try {
            val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-i", file.absolutePath))
            val formatNames = Regex(
                """Input #0,\s*(.+?),\s+from\s+""",
                RegexOption.IGNORE_CASE
            ).find(session.allLogsAsString.orEmpty())?.groupValues?.getOrNull(1)?.trim() ?: return null
            FfmpegMediaPolicies.containerFamilyFromProbe(formatNames).takeIf { it != "unknown" }
        } catch (_: Throwable) {
            null
        }
    }

    private fun validateSupportedStreamTopology(inputs: List<File>, audioOnly: Boolean): String? {
        inputs.forEachIndexed { index, file ->
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val mimes = (0 until extractor.trackCount).mapNotNull { track ->
                    extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)
                }
                val audioCount = mimes.count { it.startsWith("audio/") }
                val videoCount = mimes.count { it.startsWith("video/") }
                if (audioOnly && audioCount < 1) {
                    return "O arquivo ${index + 1} precisa ter pelo menos uma faixa de áudio."
                }
                if (!audioOnly && videoCount != 1) {
                    return "O arquivo ${index + 1} precisa ter exatamente uma faixa de vídeo."
                }
            } catch (error: Throwable) {
                return "Não foi possível validar as faixas do arquivo ${index + 1}: ${error.message.orEmpty()}"
            } finally {
                extractor.release()
            }
        }
        return null
    }

    private fun audioInputLabel(inputIndex: Int, trackIndex: Int = 0): String {
        val clip = clips.getOrNull(inputIndex)
        val track = if (processingAudioTrackCount > 1) trackIndex
        else clip?.let { selectedAudioTracks[it.uri.toString()] } ?: 0
        return FfmpegMediaPolicies.audioFilterInputSpecifier(inputIndex, track)
    }

    private fun preparedAudioLabel(inputIndex: Int, trackIndex: Int): String =
        if (processingAudioTrackCount == 1) "a$inputIndex" else "a${trackIndex}_$inputIndex"

    private fun audioOutputLabel(trackIndex: Int): String =
        if (processingAudioTrackCount == 1) "aout" else "aout$trackIndex"

    private fun audioTrackCount(uri: Uri): Int = audioTrackLabels(uri).size

    private fun subtitleTrackCount(uri: Uri): Int {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(this, uri, null)
            (0 until extractor.trackCount).count { index ->
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                mime.startsWith("text/") || mime.contains("subtitle", true) || mime.contains("subrip", true) ||
                    mime.contains("ass", true) || mime.contains("ssa", true) || mime.contains("vtt", true) || mime.contains("ttml", true)
            }
        } catch (_: Throwable) {
            0
        } finally {
            extractor.release()
        }
    }

    private fun requestAudioTrack(clip: JoinClip, onSelected: () -> Unit) {
        val labels = audioTrackLabels(clip.uri)
        AlertDialog.Builder(this)
            .setTitle("Escolha 1 das ${labels.size} faixas de ${clip.name}; as demais não entrarão na saída recodificada")
            .setSingleChoiceItems(labels.toTypedArray(), 0) { dialog, which ->
                selectedAudioTracks[clip.uri.toString()] = which
                dialog.dismiss()
                onSelected()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun audioTrackLabels(uri: Uri): List<String> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(this, uri, null)
            var audioOrdinal = 0
            (0 until extractor.trackCount).mapNotNull { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("audio/")) return@mapNotNull null
                audioOrdinal += 1
                val language = format.getString(MediaFormat.KEY_LANGUAGE)?.takeIf(String::isNotBlank)
                "Faixa $audioOrdinal — ${mime.substringAfter('/').uppercase(Locale.ROOT)}" +
                    (language?.let { " · $it" } ?: "")
            }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            extractor.release()
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
        "mka", "mkv" -> "audio/x-matroska"
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
                videoBitrate = FALLBACK_VIDEO_BITRATE,
                videoBufferSize = bufferSizeFor(FALLBACK_VIDEO_BITRATE),
                audioSampleRate = 48000,
                audioChannels = 2,
                audioLayout = "stereo",
                audioBitrate = "192k",
                pixFmt = null,
                sar = null
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
            val audioLayout = FfmpegMediaPolicies.channelLayout(audioChannels)

            val pixFmt = parsePixFmt(videoLine)
            val sar = parseSar(videoLine)
            val videoProfile = parseVideoProfile(videoLine)
            val audioCodec = detectAudioCodec(audioLine)

            OutputProfile(
                width = resolution?.first ?: fallbackWidth,
                height = resolution?.second ?: fallbackHeight,
                fps = parseFrameRate(videoLine) ?: "30",
                rotationDegrees = rotationDegrees,
                videoCodec = videoCodec,
                videoEncoder = videoEncoderFor(videoCodec, encoderOverride),
                videoBitrate = videoBitrate,
                videoBufferSize = bufferSizeFor(videoBitrate),
                audioSampleRate = audioSampleRate,
                audioChannels = audioChannels,
                audioLayout = audioLayout,
                audioBitrate = parseBitrateFromText(audioLine) ?: "192k",
                pixFmt = pixFmt,
                sar = sar,
                videoProfile = videoProfile,
                audioCodec = audioCodec
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
                videoBitrate = FALLBACK_VIDEO_BITRATE,
                videoBufferSize = bufferSizeFor(FALLBACK_VIDEO_BITRATE),
                audioSampleRate = 48000,
                audioChannels = 2,
                audioLayout = "stereo",
                audioBitrate = "192k",
                pixFmt = null,
                sar = null
            )
        }
    }

    private fun detectAggregateOutputProfile(inputs: List<File>): OutputProfile {
        val profiles = inputs.mapIndexed { index, input ->
            val clip = clips.getOrNull(index)
            applySelectedAudioProfile(input, clip, detectOutputProfile(input, clip))
        }
        return aggregateOutputProfiles(profiles)
    }

    private fun aggregateOutputProfiles(profiles: List<OutputProfile>): OutputProfile {
        val first = profiles.firstOrNull() ?: return detectOutputProfile(null, clips.firstOrNull())
        val fps = profiles.maxOf { it.fps.toDoubleOrNull() ?: 30.0 }
        fun kbps(value: String, fallback: Int): Int {
            val amount = Regex("""(\d+(?:\.\d+)?)""").find(value)?.value?.toDoubleOrNull() ?: return fallback
            return when {
                value.endsWith("M", ignoreCase = true) -> (amount * 1000.0).toInt()
                value.endsWith("k", ignoreCase = true) -> amount.toInt()
                else -> (amount / 1000.0).toInt().coerceAtLeast(1)
            }
        }
        val videoBitrateKbps = profiles.maxOf { kbps(it.videoBitrate, 15_000) }
        val audioBitrateKbps = profiles.maxOf { kbps(it.audioBitrate, 192) }
        val channels = profiles.maxOf { it.audioChannels }.coerceAtLeast(1)
        val videoBitrate = "${videoBitrateKbps}k"
        return first.copy(
            width = makeEven(profiles.maxOf { it.width }).coerceAtLeast(2),
            height = makeEven(profiles.maxOf { it.height }).coerceAtLeast(2),
            fps = formatFrameRate(fps),
            videoBitrate = videoBitrate,
            videoBufferSize = bufferSizeFor(videoBitrate),
            audioSampleRate = profiles.maxOf { it.audioSampleRate },
            audioChannels = channels,
            audioLayout = FfmpegMediaPolicies.channelLayout(channels),
            audioBitrate = "${audioBitrateKbps}k"
        )
    }

    private fun applySelectedAudioProfile(input: File, clip: JoinClip?, profile: OutputProfile): OutputProfile {
        val selectedTrack = clip?.let { selectedAudioTracks[it.uri.toString()] } ?: 0
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(input.absolutePath)
            val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                .getOrNull(selectedTrack) ?: return profile
            val extractorSampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(profile.audioSampleRate)
            // MediaExtractor expõe 22,05 kHz para alguns HE-AAC/SBR cujo sample
            // rate de apresentação é 44,1 kHz. O probe do FFmpeg conhece a taxa
            // efetiva; preservá-la evita reduzir a qualidade por engano.
            val sampleRate = if (selectedTrack == 0) {
                maxOf(profile.audioSampleRate, extractorSampleRate)
            } else {
                extractorSampleRate
            }
            val channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(profile.audioChannels)
            val bitrate = runCatching { format.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrNull()
                ?.let { "${(it / 1000).coerceAtLeast(1)}k" } ?: profile.audioBitrate
            val codec = format.getString(MediaFormat.KEY_MIME)?.substringAfter('/') ?: profile.audioCodec
            profile.copy(
                audioSampleRate = sampleRate,
                audioChannels = channels,
                audioLayout = FfmpegMediaPolicies.channelLayout(channels),
                audioBitrate = bitrate,
                audioCodec = codec
            )
        } catch (_: Throwable) {
            profile
        } finally {
            extractor.release()
        }
    }

    private fun bestVideoBitrate(inputFile: File, logs: String, videoLine: String): String? {
        return parseBitrateKbpsFromText(videoLine)?.let { "${it.coerceAtLeast(1)}k" }
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
            videoLine.contains("vp9", ignoreCase = true) -> "vp9"
            videoLine.contains("av01", ignoreCase = true) ||
                videoLine.contains("av1", ignoreCase = true) -> "av1"
            videoLine.contains("mpeg4", ignoreCase = true) ||
                videoLine.contains("mp4v", ignoreCase = true) -> "mpeg4"
            videoLine.contains("h264", ignoreCase = true) ||
                videoLine.contains("avc", ignoreCase = true) ||
                videoLine.contains("h.264", ignoreCase = true) -> "h264"
            else -> {
                val match = Regex("""Video:\s*([a-zA-Z0-9_-]+)""").find(videoLine)
                match?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: DEFAULT_VIDEO_CODEC
            }
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
        val settings = encoder.encodingFor(processingVideoQuality, profile.videoBitrate)
        val targetBitrate = settings.targetBitrate ?: return settings.arguments
        return buildList {
            addAll(settings.arguments)
            addAll(listOf("-b:v", targetBitrate))
            // minrate/maxrate/bufsize formam um CBR estrito. O wrapper
            // MediaCodec traduz esses campos para MediaFormat e vários
            // aparelhos recusam a configuração mesmo quando -b:v isolado é
            // aceito. Para hardware, deixe o encoder controlar o rate control;
            // libx264 continua recebendo o CBR solicitado pela UI.
            if (constrained && !encoder.ffmpegName.endsWith("_mediacodec", ignoreCase = true)) {
                addAll(listOf("-minrate", targetBitrate, "-maxrate", targetBitrate, "-bufsize", bufferSizeFor(targetBitrate)))
            }
        }
    }

    private fun detectOutputProfile(uri: Uri, clip: JoinClip): OutputProfile {
        val fallback = detectOutputProfile(null, clip)
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(this, uri, null)
            val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            val video = formats.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val selectedTrack = selectedAudioTracks[uri.toString()] ?: 0
            val audio = formats.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }.getOrNull(selectedTrack)
            val videoMime = video?.getString(MediaFormat.KEY_MIME).orEmpty()
            val videoCodec = when (videoMime) {
                "video/hevc" -> "hevc"
                "video/avc" -> "h264"
                else -> videoMime.substringAfter('/', fallback.videoCodec)
            }
            val fps = video?.let { format ->
                runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrNull()
                    ?: runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrNull()
            }?.takeIf { it in 1.0..240.0 }?.let(::formatFrameRate) ?: fallback.fps
            val videoBitrate = video?.let { runCatching { it.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrNull() }
                ?.takeIf { it > 0 }?.let { "${(it / 1000).coerceAtLeast(1)}k" } ?: fallback.videoBitrate
            val sampleRate = audio?.let { runCatching { it.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrNull() }
                ?: fallback.audioSampleRate
            val channels = audio?.let { runCatching { it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrNull() }
                ?: fallback.audioChannels
            val audioBitrate = audio?.let { runCatching { it.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrNull() }
                ?.takeIf { it > 0 }?.let { "${(it / 1000).coerceAtLeast(1)}k" } ?: fallback.audioBitrate
            fallback.copy(
                width = video?.let { runCatching { it.getInteger(MediaFormat.KEY_WIDTH) }.getOrNull() }?.let(::makeEven) ?: fallback.width,
                height = video?.let { runCatching { it.getInteger(MediaFormat.KEY_HEIGHT) }.getOrNull() }?.let(::makeEven) ?: fallback.height,
                fps = fps,
                rotationDegrees = normalizeRotationForMetadata(clip.rotationDegrees),
                videoCodec = videoCodec,
                videoEncoder = videoEncoderFor(videoCodec),
                videoBitrate = videoBitrate,
                videoBufferSize = bufferSizeFor(videoBitrate),
                audioSampleRate = sampleRate,
                audioChannels = channels,
                audioLayout = FfmpegMediaPolicies.channelLayout(channels),
                audioBitrate = audioBitrate,
                audioCodec = audio?.getString(MediaFormat.KEY_MIME)?.substringAfter('/'),
                // O MediaExtractor quase nunca expõe o pixel format do
                // arquivo (KEY_PIXEL_FORMAT descreve a superfície do codec).
                // O probe real do FFmpeg (usado na execucao com o arquivo
                // copiado) sempre loga o pix_fmt; no preview, sem o arquivo,
                // h264/hevc de aparelhos/celulares sao yuv420p 8-bit na
                // pratica total — assumir esse valor evita o SmartJoin
                // recusar todo plano so porque o formato e "desconhecido"
                // aqui, divergindo do runtime que aceitaria o mesmo arquivo.
                pixFmt = fallback.pixFmt ?: when (videoCodec) {
                    "h264", "hevc" -> "yuv420p"
                    else -> null
                }
            )
        } catch (_: Throwable) {
            fallback
        } finally {
            extractor.release()
        }
    }

    private fun detectVideoKeyframes(uri: Uri): List<Double> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(this, uri, null)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return emptyList()
            extractor.selectTrack(videoTrack)
            val result = mutableListOf<Double>()
            while (extractor.sampleTime >= 0L) {
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    val seconds = extractor.sampleTime / 1_000_000.0
                    if (result.lastOrNull()?.let { kotlin.math.abs(it - seconds) > 0.0005 } != false) result += seconds
                }
                if (!extractor.advance()) break
            }
            result
        } catch (_: Throwable) {
            emptyList()
        } finally {
            extractor.release()
        }
    }

    private fun requireVideoEncoder(): FfmpegVideoEncoder {
        return selectedVideoEncoder ?: error("Encoder de vídeo indisponível")
    }

    private fun parsePixFmt(videoLine: String): String? {
        return Regex("""\b(yuv[0-9a-z_]+|rgb[0-9a-z_]+|bgr[0-9a-z_]+)\b""").find(videoLine)?.groupValues?.get(1)
    }

    private fun parseSar(videoLine: String): String? {
        return Regex("""SAR\s+(\d+:\d+)""", RegexOption.IGNORE_CASE).find(videoLine)?.groupValues?.get(1)
    }

    private fun parseVideoProfile(videoLine: String): String? {
        return FfmpegMediaPolicies.parseKnownVideoProfile(videoLine)
    }

    private fun detectAudioCodec(audioLine: String): String? {
        return Regex("""Audio:\s*([a-zA-Z0-9_-]+)""").find(audioLine)?.groupValues?.get(1)?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
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
        return FfmpegMediaPolicies.parseAudioChannelCount(audioLine) ?: 2
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

    private data class OutputProfile(
        val width: Int,
        val height: Int,
        val fps: String,
        val rotationDegrees: Int,
        val videoCodec: String,
        val videoEncoder: String,
        val videoBitrate: String,
        val videoBufferSize: String,
        val audioSampleRate: Int,
        val audioChannels: Int,
        val audioLayout: String,
        val audioBitrate: String,
        val pixFmt: String? = null,
        val sar: String? = null,
        val videoProfile: String? = null,
        val audioCodec: String? = null
    )

    private data class SmartJoinPiece(
        val file: File,
        val durationSeconds: Double
    )

    private class SmartJoinStepException(
        val cancelled: Boolean,
        message: String
    ) : Exception(message)

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
        val failureMessage: String
    )

    companion object {
        private const val REQUEST_PICK_VIDEOS = 7601
        private const val REQUEST_CHOOSE_PRE_OUTPUT_DIR = 7602
        private const val REQUEST_CHOOSE_OUTPUT_DIR = 7603
        private const val FALLBACK_VIDEO_BITRATE = "15M"
        private const val DEFAULT_VIDEO_CODEC = "h264"
        private const val TRANSITION_FADE_IN_OUT = "Fade in/out"
        private const val TRANSITION_NONE = "Sem transição"
        private const val TRANSITION_DEFAULT_VIDEO = "Dissolver"
        private const val SMART_JOIN_ANALYZE_LABEL = "Analisando perfis e keyframes"
        private const val SMART_JOIN_FINALIZE_LABEL = "Unindo segmentos SmartJoin"
        private const val SMART_JOIN_MIN_SEGMENT_SECONDS = 0.020
        private const val TAG = "FfmpegJoinVideos"
        private val TRANSITIONS = listOf(
            TRANSITION_NONE,
            TRANSITION_FADE_IN_OUT,
            "Dissolver", "Dissolução suave", "Varredura para a esquerda",
            "Varredura para a direita", "Deslizar para a esquerda",
            "Deslizar para a direita", "Suave para a esquerda",
            "Suave para a direita", "Círculo abrindo", "Círculo fechando"
        )
        private val VIDEO_TRANSITION_VALUES = linkedMapOf(
            "Dissolver" to "fade",
            "Dissolução suave" to "dissolve",
            "Varredura para a esquerda" to "wipeleft",
            "Varredura para a direita" to "wiperight",
            "Deslizar para a esquerda" to "slideleft",
            "Deslizar para a direita" to "slideright",
            "Suave para a esquerda" to "smoothleft",
            "Suave para a direita" to "smoothright",
            "Círculo abrindo" to "circleopen",
            "Círculo fechando" to "circleclose"
        )
        private val AUDIO_TRANSITIONS = linkedMapOf(
            TRANSITION_FADE_IN_OUT to null,
            "Curva linear" to "tri",
            "Curva exponencial" to "exp",
            "Curva logarítmica" to "log",
            TRANSITION_NONE to TRANSITION_NONE
        )
    }
}
