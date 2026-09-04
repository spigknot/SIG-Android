package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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

class FfmpegInsertAudioActivity : AppCompatActivity() {

    private lateinit var scroll: ScrollView
    private lateinit var mainName: TextView
    private lateinit var insertName: TextView
    private lateinit var selectInsert: ImageButton
    private lateinit var timeline: FfmpegInsertAudioTimelineView
    private lateinit var playPause: ImageButton
    private lateinit var speedDown: ImageButton
    private lateinit var speedUp: ImageButton
    private lateinit var inputTime: EditText
    private lateinit var options: View
    private lateinit var transitionButton: TextView
    private lateinit var transitionTime: EditText
    private lateinit var executeButton: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var outputName: TextView
    private lateinit var outputStats: TextView
    private lateinit var outputActions: View
    private lateinit var saveButton: ImageButton
    private lateinit var openFolderButton: ImageButton
    private lateinit var shareButton: ImageButton
    private lateinit var selectOutputFolder: ImageButton
    private lateinit var arrowInputOutput: View

    private val handler = Handler(Looper.getMainLooper())
    private var mainAudio: AudioSource? = null
    private var insertedAudio: AudioSource? = null
    private val selectedAudioTracks = mutableMapOf<String, Int>()
    private var insertionMs = 0L
    private var compositePositionMs = 0L
    private var mainPlayer: MediaPlayer? = null
    private var insertedPlayer: MediaPlayer? = null
    private var mainPrepared = false
    private var insertedPrepared = false
    private var activeSegment = Segment.NONE
    private var playbackSpeed = 1f
    private val speedSteps = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f)
    private var selectedTransition = TRANSITION_NONE
    private var isProcessing = false
    private var currentSessionId: Long? = null
    private var lastOutputFile: File? = null
    private var lastOutputUri: Uri? = null
    private var lastOutputName = ""
    private var preSelectedOutputDirUri: Uri? = null
    private var finalOutputDirUri: Uri? = null

    private val playbackTicker = object : Runnable {
        override fun run() {
            updateCompositePlaybackPosition()
            if (isPlaying()) handler.postDelayed(this, 50L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_ffmpeg_insert_audio)

        scroll = findViewById(R.id.insert_scroll)
        mainName = findViewById(R.id.selected_main_audio)
        insertName = findViewById(R.id.selected_insert_audio)
        selectInsert = findViewById(R.id.button_select_insert_audio)
        timeline = findViewById(R.id.insert_timeline)
        playPause = findViewById(R.id.button_play_pause)
        speedDown = findViewById(R.id.button_speed_down)
        speedUp = findViewById(R.id.button_speed_up)
        inputTime = findViewById(R.id.input_insert_time)
        options = findViewById(R.id.insert_options)
        transitionButton = findViewById(R.id.button_transition)
        transitionTime = findViewById(R.id.input_transition_time)
        executeButton = findViewById(R.id.button_insert)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        outputName = findViewById(R.id.output_file_name)
        outputStats = findViewById(R.id.output_stats)
        outputActions = findViewById(R.id.output_actions)
        saveButton = findViewById(R.id.button_save_to_folder)
        openFolderButton = findViewById(R.id.button_output_folder)
        shareButton = findViewById(R.id.button_output_share)
        selectOutputFolder = findViewById(R.id.button_select_output_folder)
        arrowInputOutput = findViewById(R.id.arrow_input_output)

        val exitHandler = installCancelAndExitGuard(
            isTaskRunning = { isProcessing },
            cancelTask = { cancelProcessing() }
        )
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { exitHandler() }
        findViewById<View>(R.id.button_select_main_audio).setOnClickListener { openAudioPicker(REQUEST_MAIN_AUDIO) }
        selectInsert.setOnClickListener { openAudioPicker(REQUEST_INSERT_AUDIO) }
        selectOutputFolder.setOnClickListener { openOutputFolderPicker(REQUEST_PRE_OUTPUT_DIR) }
        playPause.setOnClickListener { togglePlayback() }
        speedDown.setOnClickListener { changePlaybackSpeed(-1) }
        speedUp.setOnClickListener { changePlaybackSpeed(1) }
        transitionButton.setOnClickListener { showTransitionMenu() }
        executeButton.setOnClickListener { if (isProcessing) cancelProcessing() else startInsert() }
        saveButton.setOnClickListener {
            preSelectedOutputDirUri?.let(::saveOutputToUri) ?: openOutputFolderPicker(REQUEST_OUTPUT_DIR)
        }
        openFolderButton.setOnClickListener { openOutputFolder() }
        shareButton.setOnClickListener { shareOutput() }
        outputName.setOnClickListener { openOutput() }

        timeline.onSeek = { position -> seekComposite(position) }
        inputTime.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyTypedInsertionTime()
                inputTime.clearFocus()
                true
            } else false
        }
        inputTime.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) applyTypedInsertionTime() }
        inputTime.doAfterTextChanged { refreshCommandPreview() }
        transitionTime.doAfterTextChanged { refreshCommandPreview() }
        updateOptionState()
        updateSpeedButtons()
        refreshCommandPreview()
        handleIncomingShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    /**
     * Inserir audio aceita somente audio, apenas um, e ele entra como audio
     * base (o mesmo papel do primeiro arquivo escolhido pelo botao +).
     */
    private fun handleIncomingShareIntent(intent: Intent?) {
        if (!SharedMediaIntents.isShareAction(intent)) return
        val audio = SharedMediaIntents.mediaFrom(this, intent).firstOrNull { it.isAudio }
        if (audio == null) {
            Toast.makeText(this, "A ferramenta Inserir áudio aceita apenas um áudio.", Toast.LENGTH_LONG).show()
            status.text = "Compartilhe um arquivo de áudio para usar como base."
            return
        }
        loadAudio(audio.uri, primary = true, flags = intent?.flags ?: 0)
        status.text = "Áudio base recebido pelo compartilhamento."
    }

    @Deprecated("Legacy XML activity callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        when (requestCode) {
            REQUEST_MAIN_AUDIO -> data?.data?.let { loadAudio(it, true, data.flags) }
            REQUEST_INSERT_AUDIO -> data?.data?.let { loadAudio(it, false, data.flags) }
            REQUEST_PRE_OUTPUT_DIR -> data?.data?.let {
                takeTreePermission(it)
                preSelectedOutputDirUri = it
                selectOutputFolder.setBackgroundResource(R.drawable.ffmpeg_outline_green_button_bg)
                Toast.makeText(this, "Pasta de saída selecionada.", Toast.LENGTH_SHORT).show()
            }
            REQUEST_OUTPUT_DIR -> data?.data?.let {
                takeTreePermission(it)
                saveOutputToUri(it)
            }
        }
    }

    private fun openAudioPicker(requestCode: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, requestCode)
    }

    private fun loadAudio(uri: Uri, primary: Boolean, flags: Int) {
        try {
            if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: SecurityException) {
        }
        val source = readAudioSource(uri) ?: return
        clearOutputResult()
        pausePlayback()
        if (primary) {
            mainAudio = source
            insertedAudio = null
            insertionMs = 0L
            compositePositionMs = 0L
            mainName.text = source.name
            insertName.visibility = View.GONE
            selectInsert.isEnabled = true
            selectInsert.alpha = 1f
            options.visibility = View.GONE
            arrowInputOutput.visibility = View.GONE
            selectOutputFolder.visibility = View.GONE
        } else {
            val main = mainAudio ?: return
            insertionMs = compositeToMainTime(compositePositionMs).coerceIn(0L, main.durationMs)
            insertedAudio = source
            compositePositionMs = insertionMs
            insertName.text = "Inserir: ${source.name}"
            insertName.visibility = View.VISIBLE
            options.visibility = View.VISIBLE
            arrowInputOutput.visibility = View.VISIBLE
            selectOutputFolder.visibility = View.VISIBLE
        }
        configureTimeline()
        preparePlayers()
        refreshCommandPreview()
    }

    private fun readAudioSource(uri: Uri): AudioSource? {
        val name = queryDisplayName(uri) ?: "audio"
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.takeIf { it > 0L } ?: error("duração indisponível")
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            if (!hasAudio) error("arquivo sem faixa de áudio")
            AudioSource(uri, name, duration)
        } catch (error: Throwable) {
            Log.e(TAG, "Could not read $name", error)
            Toast.makeText(this, "Não consegui ler $name como áudio.", Toast.LENGTH_LONG).show()
            null
        } finally {
            retriever.release()
        }
    }

    private fun configureTimeline() {
        val main = mainAudio ?: return
        val inserted = insertedAudio
        timeline.configure(main.name, main.durationMs, inserted?.name, inserted?.durationMs ?: 0L, insertionMs)
        timeline.setCurrent(compositePositionMs)
        timeline.isEnabled = true
        if (!inputTime.hasFocus()) inputTime.setText(formatTime(insertionMs))
    }

    private fun preparePlayers() {
        releasePlayers()
        val main = mainAudio ?: return
        mainPrepared = false
        insertedPrepared = insertedAudio == null
        mainPlayer = createPlayer(main, true)
        insertedAudio?.let { insertedPlayer = createPlayer(it, false) }
    }

    private fun createPlayer(source: AudioSource, primary: Boolean): MediaPlayer {
        return MediaPlayer().apply {
            setDataSource(this@FfmpegInsertAudioActivity, source.uri)
            setOnPreparedListener {
                if (primary) mainPrepared = true else insertedPrepared = true
                applyPlaybackSpeed(it)
            }
            setOnCompletionListener {
                when {
                    !primary -> startMainAfterInsertion()
                    activeSegment == Segment.MAIN_BEFORE && insertedAudio != null -> startInsertedSegment()
                    activeSegment == Segment.MAIN_AFTER || insertedAudio == null -> finishPlayback()
                }
            }
            setOnErrorListener { _, _, _ ->
                status.text = "Não consegui reproduzir ${source.name}."
                pausePlayback()
                true
            }
            prepareAsync()
        }
    }

    private fun togglePlayback() {
        if (isPlaying()) {
            finishTimeEditing()
            pausePlayback()
            return
        }
        finishTimeEditing()
        if (!mainPrepared || !insertedPrepared) {
            Toast.makeText(this, "O áudio ainda está sendo preparado.", Toast.LENGTH_SHORT).show()
            return
        }
        if (compositePositionMs >= compositeDurationMs()) compositePositionMs = 0L
        startPlaybackAt(compositePositionMs)
    }

    private fun startPlaybackAt(positionMs: Long) {
        pausePlayersOnly()
        val inserted = insertedAudio
        when {
            inserted == null -> {
                activeSegment = Segment.MAIN_AFTER
                seekPlayer(mainPlayer, positionMs)
                mainPlayer?.start()
            }
            positionMs < insertionMs -> {
                activeSegment = Segment.MAIN_BEFORE
                seekPlayer(mainPlayer, positionMs)
                mainPlayer?.start()
            }
            positionMs < insertionMs + inserted.durationMs -> {
                activeSegment = Segment.INSERTED
                seekPlayer(insertedPlayer, positionMs - insertionMs)
                insertedPlayer?.start()
            }
            else -> {
                activeSegment = Segment.MAIN_AFTER
                seekPlayer(mainPlayer, positionMs - inserted.durationMs)
                mainPlayer?.start()
            }
        }
        updatePlayButton(true)
        handler.removeCallbacks(playbackTicker)
        handler.post(playbackTicker)
    }

    private fun updateCompositePlaybackPosition() {
        val main = mainAudio ?: return
        val inserted = insertedAudio
        when (activeSegment) {
            Segment.MAIN_BEFORE -> {
                val position = mainPlayer?.currentPosition?.toLong() ?: 0L
                if (inserted != null && position >= insertionMs) {
                    startInsertedSegment()
                    return
                }
                compositePositionMs = position.coerceIn(0L, main.durationMs)
            }
            Segment.INSERTED -> {
                val position = insertedPlayer?.currentPosition?.toLong() ?: 0L
                compositePositionMs = insertionMs + position.coerceIn(0L, inserted?.durationMs ?: 0L)
            }
            Segment.MAIN_AFTER -> {
                val position = mainPlayer?.currentPosition?.toLong() ?: 0L
                compositePositionMs = position + (inserted?.durationMs ?: 0L)
            }
            Segment.NONE -> return
        }
        compositePositionMs = compositePositionMs.coerceIn(0L, compositeDurationMs())
        timeline.setCurrent(compositePositionMs)
    }

    private fun startInsertedSegment() {
        mainPlayer?.pause()
        activeSegment = Segment.INSERTED
        seekPlayer(insertedPlayer, 0L)
        insertedPlayer?.start()
        compositePositionMs = insertionMs
    }

    private fun startMainAfterInsertion() {
        insertedPlayer?.pause()
        if (insertionMs >= (mainAudio?.durationMs ?: 0L)) {
            finishPlayback()
            return
        }
        activeSegment = Segment.MAIN_AFTER
        seekPlayer(mainPlayer, insertionMs)
        mainPlayer?.start()
        compositePositionMs = insertionMs + (insertedAudio?.durationMs ?: 0L)
        handler.removeCallbacks(playbackTicker)
        handler.post(playbackTicker)
    }

    private fun finishPlayback() {
        compositePositionMs = compositeDurationMs()
        timeline.setCurrent(compositePositionMs)
        pausePlayersOnly()
        activeSegment = Segment.NONE
        updatePlayButton(false)
        handler.removeCallbacks(playbackTicker)
    }

    private fun seekComposite(positionMs: Long) {
        val wasPlaying = isPlaying()
        finishTimeEditing()
        compositePositionMs = positionMs.coerceIn(0L, compositeDurationMs())
        if (wasPlaying) startPlaybackAt(compositePositionMs) else seekPlayersForComposite(compositePositionMs)
    }

    private fun finishTimeEditing() {
        if (inputTime.hasFocus()) inputTime.clearFocus()
    }

    private fun seekPlayersForComposite(positionMs: Long) {
        val inserted = insertedAudio
        when {
            inserted == null || positionMs < insertionMs -> seekPlayer(mainPlayer, positionMs)
            positionMs < insertionMs + inserted.durationMs -> seekPlayer(insertedPlayer, positionMs - insertionMs)
            else -> seekPlayer(mainPlayer, positionMs - inserted.durationMs)
        }
    }

    private fun applyTypedInsertionTime() {
        val main = mainAudio ?: return
        val parsed = parseTime(inputTime.text.toString()) ?: run {
            inputTime.setText(formatTime(insertionMs))
            return
        }
        pausePlayback()
        insertionMs = parsed.coerceIn(0L, main.durationMs)
        compositePositionMs = insertionMs
        configureTimeline()
        seekPlayersForComposite(compositePositionMs)
    }

    private fun compositeToMainTime(positionMs: Long): Long {
        val inserted = insertedAudio ?: return positionMs.coerceIn(0L, mainAudio?.durationMs ?: 0L)
        return when {
            positionMs <= insertionMs -> positionMs
            positionMs < insertionMs + inserted.durationMs -> insertionMs
            else -> positionMs - inserted.durationMs
        }.coerceIn(0L, mainAudio?.durationMs ?: 0L)
    }

    private fun compositeDurationMs(): Long = ((mainAudio?.durationMs ?: 0L) + (insertedAudio?.durationMs ?: 0L)).coerceAtLeast(1L)

    private fun changePlaybackSpeed(direction: Int) {
        val wasPlaying = isPlaying()
        finishTimeEditing()
        val index = speedSteps.indexOfFirst { kotlin.math.abs(it - playbackSpeed) < 0.01f }.let { if (it >= 0) it else 2 }
        playbackSpeed = speedSteps[(index + direction).coerceIn(0, speedSteps.lastIndex)]
        applyPlaybackSpeed(mainPlayer)
        applyPlaybackSpeed(insertedPlayer)
        updateSpeedButtons()
        if (wasPlaying) startPlaybackAt(compositePositionMs)
    }

    private fun applyPlaybackSpeed(player: MediaPlayer?) {
        if (player == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            player.playbackParams = player.playbackParams.setSpeed(playbackSpeed)
        } catch (_: Throwable) {
        }
    }

    private fun updateSpeedButtons() {
        speedDown.alpha = if (playbackSpeed <= speedSteps.first()) 0.35f else 1f
        speedUp.alpha = if (playbackSpeed >= speedSteps.last()) 0.35f else 1f
    }

    private fun showTransitionMenu() {
        if (!transitionButton.isEnabled) return
        PopupMenu(this, transitionButton).apply {
            AUDIO_TRANSITIONS.forEach { (label, _) -> menu.add(label) }
            setOnMenuItemClickListener {
                val label = it.title.toString()
                selectedTransition = AUDIO_TRANSITIONS.firstOrNull { option -> option.first == label }?.second
                    ?: TRANSITION_NONE
                transitionButton.text = "Transição: $label"
                refreshCommandPreview()
                true
            }
            show()
        }
    }

    private fun updateOptionState() {
        val enabled = !isProcessing
        transitionButton.isEnabled = enabled
        transitionTime.isEnabled = enabled
        transitionButton.alpha = if (enabled) 1f else 0.4f
        transitionTime.alpha = if (enabled) 1f else 0.4f
    }

    private fun startInsert() {
        val main = mainAudio ?: return
        val inserted = insertedAudio ?: return
        if (audioTrackCount(main.uri) == 0 || audioTrackCount(inserted.uri) == 0) {
            status.text = "Os dois arquivos precisam possuir pelo menos uma faixa de áudio."
            return
        }
        listOf(main, inserted).firstOrNull {
            audioTrackCount(it.uri) > 1 && selectedAudioTracks[it.uri.toString()] == null
        }?.let { source ->
            requestAudioTrack(source) { startInsert() }
            return
        }
        pausePlayback()
        clearOutputResult()
        val jobConfig = InsertAudioJobConfig(
            insertionMs = insertionMs,
            selectedTransition = selectedTransition,
            transitionSeconds = transitionTime.text.toString().replace(',', '.').toDoubleOrNull()?.coerceIn(0.0, 5.0) ?: 0.5,
            mainAudioTrack = selectedAudioTracks[main.uri.toString()] ?: 0,
            insertedAudioTrack = selectedAudioTracks[inserted.uri.toString()] ?: 0
        )
        setProcessing(true)
        val startedAt = SystemClock.elapsedRealtime()
        val tracker = FfmpegTaskTracker(status, listOf("Preparando arquivos", "Inserindo áudio", "Preparando arquivo para salvar"))
        Thread {
            val temporaryInputs = mutableListOf<File>()
            try {
                tracker.setTaskProgress(0, 0)
                val mainFile = copyUriToCache(main.uri, main.name, "insert_main")
                temporaryInputs += mainFile
                tracker.setTaskProgress(0, 50)
                val insertedFile = copyUriToCache(inserted.uri, inserted.name, "insert_secondary")
                temporaryInputs += insertedFile
                tracker.completeTask(0)

                val profile = detectAudioProfile(mainFile, jobConfig.mainAudioTrack)
                val rawExtension = main.name.substringAfterLast('.', "m4a").lowercase(Locale.ROOT)
                val safeExtension = rawExtension.takeIf { it in SUPPORTED_COPY_EXTENSIONS } ?: "m4a"
                val resultFile = File(cacheDir, "insert_${System.currentTimeMillis()}_${sanitizeBase(main.name)}.$safeExtension")
                val encoderName = encoderForProfile(safeExtension, profile)
                tracker.setTaskEncoder(1, encoderName)
                tracker.startTask(1)

                val session = executeWithProgress(
                    buildFullReencodeArguments(mainFile, insertedFile, resultFile, profile, jobConfig),
                    compositeDurationMs(), tracker, 1
                )
                if (ReturnCode.isCancel(session.returnCode)) throw ProcessingCancelled()
                if (!ReturnCode.isSuccess(session.returnCode) || !resultFile.exists() || resultFile.length() == 0L) {
                    error(ffmpegFailureMessage(session))
                }
                tracker.completeTask(1)
                tracker.completeTask(2)
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val efficiency = compositeDurationMs() / elapsed.coerceAtLeast(1L).toDouble()
                val mode = "Inserção precisa"
                tracker.success(
                    "Tempo de processamento: ${formatTime(elapsed)}\n" +
                        "Mídia processada: ${formatTime(compositeDurationMs())}\n" +
                        "Eficiência: ${String.format(Locale.US, "%.2fx", efficiency)}\n" +
                        "Modo: $mode"
                )
                runOnUiThread {
                    setProcessing(false)
                    lastOutputFile = resultFile
                    lastOutputName = "${sanitizeBase(main.name)}_com_audio.$safeExtension"
                    outputName.text = lastOutputName
                    outputName.visibility = View.VISIBLE
                    outputActions.visibility = View.VISIBLE
                    saveButton.visibility = View.VISIBLE
                    openFolderButton.visibility = View.GONE
                    shareButton.visibility = View.GONE
                    // Estatisticas fora do status (apos os botoes), para o
                    // usuario ver Salvar/Compartilhar logo apos os passos.
                    val statsText = tracker.successMessageOrEmpty()
                    if (statsText.isNotBlank()) {
                        outputStats.text = "Estatísticas:\n$statsText"
                        outputStats.visibility = View.VISIBLE
                    }
                    scroll.post { scroll.smoothScrollTo(0, outputActions.bottom) }
                }
            } catch (_: ProcessingCancelled) {
                runOnUiThread {
                    setProcessing(false)
                    tracker.fail("Operação cancelada.")
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Audio insertion failed", error)
                runOnUiThread {
                    setProcessing(false)
                    tracker.fail(error.message ?: "Falha inesperada")
                }
            } finally {
                temporaryInputs.forEach { it.delete() }
            }
        }.start()
    }

    private fun buildFullReencodeArguments(main: File, inserted: File, output: File, profile: AudioProfile, jobConfig: InsertAudioJobConfig): Array<String> {
        val at = jobConfig.insertionMs / 1000.0
        val mainEnd = (mainAudio?.durationMs ?: 1L) / 1000.0
        val insertedEnd = (insertedAudio?.durationMs ?: 1L) / 1000.0
        val audioLayout = FfmpegMediaPolicies.channelLayout(profile.channels)
        val normalize = "aresample=${profile.sampleRate},aformat=sample_fmts=fltp:sample_rates=${profile.sampleRate}:channel_layouts=$audioLayout"
        val filter = FfmpegMediaPolicies.insertAudioFilterComplex(
            mainInputSpecifier = FfmpegMediaPolicies.audioFilterInputSpecifier(0, jobConfig.mainAudioTrack),
            insertedInputSpecifier = FfmpegMediaPolicies.audioFilterInputSpecifier(1, jobConfig.insertedAudioTrack),
            mainDurationSeconds = mainEnd,
            insertedDurationSeconds = insertedEnd,
            insertionSeconds = at,
            normalizeFilter = normalize,
            requestedTransitionSeconds = jobConfig.transitionSeconds,
            fadeInOut = jobConfig.selectedTransition == TRANSITION_FADE,
            crossfadeCurve = audioCrossfadeCurve(jobConfig.selectedTransition).takeIf {
                jobConfig.selectedTransition !in setOf(TRANSITION_NONE, TRANSITION_FADE)
            }
        )
        val extension = output.extension.lowercase(Locale.ROOT)
        val encoder = encoderForProfile(extension, profile)
        return FfmpegMediaPolicies.insertAudioCommandArguments(
            mainInputPath = main.absolutePath,
            insertedInputPath = inserted.absolutePath,
            outputPath = output.absolutePath,
            filterComplex = filter,
            encoder = encoder,
            sampleRate = profile.sampleRate,
            channels = profile.channels,
            bitrate = profile.bitrate.takeIf { encoder !in setOf("flac", "pcm_s16le", "alac") },
            fastStart = extension in setOf("m4a", "mp4")
        )
    }

    private fun audioTrackCount(uri: Uri): Int {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(this, uri, null)
            (0 until extractor.trackCount).count { index ->
                extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
        } catch (_: Throwable) {
            0
        } finally {
            extractor.release()
        }
    }

    private fun requestAudioTrack(source: AudioSource, onSelected: () -> Unit) {
        val labels = audioTrackLabels(source.uri)
        AlertDialog.Builder(this)
            .setTitle("Escolha a faixa de ${source.name}")
            .setSingleChoiceItems(labels.toTypedArray(), 0) { dialog, which ->
                selectedAudioTracks[source.uri.toString()] = which
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
        } finally {
            extractor.release()
        }
    }

    private fun audioCrossfadeCurve(transition: String = selectedTransition): String {
        return AUDIO_TRANSITIONS.firstOrNull { it.second == transition }?.second ?: "tri"
    }

    private fun executeWithProgress(args: Array<String>, durationMs: Long, tracker: FfmpegTaskTracker, taskIndex: Int): FFmpegSession {
        FfmpegCommandPresenter.show(status, args.asIterable())
        Log.i(TAG, "FFmpeg: ${FfmpegMediaPolicies.formatCommand(args.asIterable())}")
        val latch = CountDownLatch(1)
        val result = AtomicReference<FFmpegSession>()
        val started = SystemClock.elapsedRealtime()
        val session = FFmpegKit.executeWithArgumentsAsync(args, { completed ->
            result.set(completed)
            latch.countDown()
        }, { }, { stats ->
            val percent = (stats.time / durationMs.coerceAtLeast(1L).toDouble() * 100.0).toInt().coerceIn(0, 99)
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
            val speed = stats.time / elapsed.toDouble()
            tracker.setTaskProgress(taskIndex, percent, String.format(Locale.US, "%.2fx", speed))
        })
        currentSessionId = session.sessionId
        latch.await()
        currentSessionId = null
        val completed = result.get() ?: session
        FfmpegCommandPresenter.completeLastShown(status, ReturnCode.isSuccess(completed.returnCode))
        return completed
    }

    private fun detectAudioProfile(file: File, audioTrack: Int = 0): AudioProfile {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                .getOrNull(audioTrack) ?: return AudioProfile(48000, 2, "192k", "aac")
            val sampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(48000)
            val channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2)
            val bitrate = runCatching { format.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrNull()
                ?.let { "${(it / 1000).coerceAtLeast(1)}k" } ?: "192k"
            val codec = format.getString(MediaFormat.KEY_MIME)?.substringAfter('/')?.lowercase(Locale.ROOT) ?: "aac"
            val pcmEncoder = when (runCatching { format.getInteger(MediaFormat.KEY_PCM_ENCODING) }.getOrNull()) {
                android.media.AudioFormat.ENCODING_PCM_8BIT -> "pcm_u8"
                android.media.AudioFormat.ENCODING_PCM_FLOAT -> "pcm_f32le"
                android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> "pcm_s24le"
                android.media.AudioFormat.ENCODING_PCM_32BIT -> "pcm_s32le"
                else -> "pcm_s16le"
            }
            AudioProfile(sampleRate, channels, bitrate, codec, pcmEncoder)
        } catch (_: Throwable) {
            AudioProfile(48000, 2, "192k", "aac")
        } finally {
            extractor.release()
        }
    }

    private fun detectAudioProfile(uri: Uri, audioTrack: Int = 0): AudioProfile {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(this, uri, null)
            audioProfile(extractor, audioTrack)
        } catch (_: Throwable) {
            AudioProfile(48000, 2, "192k", "aac")
        } finally {
            extractor.release()
        }
    }

    private fun audioProfile(extractor: MediaExtractor, audioTrack: Int): AudioProfile {
        val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            .filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            .getOrNull(audioTrack) ?: return AudioProfile(48000, 2, "192k", "aac")
        val sampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(48000)
        val channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2)
        val bitrate = runCatching { format.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrNull()
            ?.let { "${(it / 1000).coerceAtLeast(1)}k" } ?: "192k"
        val codec = format.getString(MediaFormat.KEY_MIME)?.substringAfter('/')?.lowercase(Locale.ROOT) ?: "aac"
        val pcmEncoder = when (runCatching { format.getInteger(MediaFormat.KEY_PCM_ENCODING) }.getOrNull()) {
            android.media.AudioFormat.ENCODING_PCM_8BIT -> "pcm_u8"
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> "pcm_f32le"
            android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> "pcm_s24le"
            android.media.AudioFormat.ENCODING_PCM_32BIT -> "pcm_s32le"
            else -> "pcm_s16le"
        }
        return AudioProfile(sampleRate, channels, bitrate, codec, pcmEncoder)
    }

    private fun refreshCommandPreview() {
        if (isProcessing || !::status.isInitialized) return
        val main = mainAudio
        val inserted = insertedAudio
        val mainTrack = main?.let { selectedAudioTracks[it.uri.toString()] } ?: 0
        val insertedTrack = inserted?.let { selectedAudioTracks[it.uri.toString()] } ?: 0
        val profile = main?.let { detectAudioProfile(it.uri, mainTrack) }
            ?: AudioProfile(48000, 2, "192k", "aac")
        val mainExtension = main?.name?.substringAfterLast('.', "m4a")
            ?.lowercase(Locale.ROOT)?.takeIf { it in SUPPORTED_COPY_EXTENSIONS } ?: "m4a"
        val jobConfig = InsertAudioJobConfig(
            insertionMs = parseTime(inputTime.text?.toString().orEmpty())
                ?.coerceIn(0L, main?.durationMs ?: Long.MAX_VALUE) ?: insertionMs,
            selectedTransition = selectedTransition,
            transitionSeconds = transitionTime.text?.toString().orEmpty().replace(',', '.')
                .toDoubleOrNull()?.coerceIn(0.0, 5.0) ?: 0.5,
            mainAudioTrack = mainTrack,
            insertedAudioTrack = insertedTrack
        )
        val arguments = buildFullReencodeArguments(
            File(main?.name ?: "input.ext"),
            File(inserted?.name ?: "input2.ext"),
            File("output.$mainExtension"),
            profile,
            jobConfig
        )
        FfmpegCommandPresenter.preview(status, arguments.asIterable())
    }

    private fun ffmpegFailureMessage(session: FFmpegSession): String {
        val lines = session.allLogsAsString.orEmpty().lines().map { it.trim() }.filter { it.isNotBlank() }
        val important = lines.filter {
            it.contains("error", true) || it.contains("failed", true) || it.contains("invalid", true) || it.contains("not supported", true)
        }
        return (important.takeLast(5) + lines.takeLast(8)).distinct().joinToString(" ").take(500).ifBlank { "O FFmpeg não concluiu a inserção." }
    }

    private fun setProcessing(processing: Boolean) {
        isProcessing = processing
        progress.visibility = if (processing) View.VISIBLE else View.GONE
        if (processing) {
            executeButton.setImageResource(R.drawable.ic_ffmpeg_cancel_red)
            executeButton.setBackgroundResource(R.drawable.ffmpeg_outline_red_button_bg)
            executeButton.contentDescription = "Cancelar"
        } else {
            executeButton.setImageResource(R.drawable.ic_ffmpeg_insert_audio)
            executeButton.setBackgroundResource(R.drawable.ffmpeg_outline_green_button_bg)
            executeButton.contentDescription = "Inserir áudio"
        }
        selectInsert.isEnabled = !processing && mainAudio != null
        selectOutputFolder.isEnabled = !processing
        findViewById<View>(R.id.button_select_main_audio).isEnabled = !processing
        timeline.isEnabled = !processing
        inputTime.isEnabled = !processing
        playPause.isEnabled = !processing
        speedDown.isEnabled = !processing
        speedUp.isEnabled = !processing
        updateOptionState()
    }

    private fun cancelProcessing() {
        status.text = "Cancelando..."
        currentSessionId?.let { FFmpegKit.cancel(it) } ?: FFmpegKit.cancel()
    }

    private fun saveOutputToUri(treeUri: Uri) {
        val source = lastOutputFile?.takeIf { it.exists() } ?: return
        val directory = DocumentFile.fromTreeUri(this, treeUri) ?: return
        val targetName = FfmpegMediaPolicies.uniqueOutputName(lastOutputName) { candidate ->
            directory.findFile(candidate) != null
        }
        val document = directory.createFile(audioMime(targetName), targetName) ?: return
        try {
            contentResolver.openOutputStream(document.uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
            finalOutputDirUri = treeUri
            lastOutputUri = document.uri
            outputName.text = document.name ?: lastOutputName
            saveButton.visibility = View.GONE
            openFolderButton.visibility = View.VISIBLE
            shareButton.visibility = View.VISIBLE
            status.append("\n\nArquivo salvo na pasta \"${directory.name ?: "selecionada"}\".")
        } catch (error: Throwable) {
            Log.e(TAG, "Could not save inserted audio", error)
            Toast.makeText(this, "Não consegui salvar o arquivo.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openOutput() {
        val uri = lastOutputUri ?: lastOutputFile?.let { FileProvider.getUriForFile(this, "$packageName.fileprovider", it) } ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, audioMime(lastOutputName))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Não encontrei um app para abrir o áudio.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareOutput() {
        val uri = lastOutputUri ?: lastOutputFile?.let { FileProvider.getUriForFile(this, "$packageName.fileprovider", it) } ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = audioMime(lastOutputName)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Compartilhar áudio"))
    }

    private fun openOutputFolder() {
        val uri = finalOutputDirUri ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Throwable) {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        }
    }

    private fun openOutputFolderPicker(requestCode: Int) = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), requestCode)

    private fun takeTreePermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }

    private fun copyUriToCache(uri: Uri, displayName: String, prefix: String): File {
        val extension = displayName.substringAfterLast('.', "audio")
        return File(cacheDir, "${prefix}_${System.nanoTime()}.$extension").also { file ->
            contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(file).use { input.copyTo(it) } }
                ?: error("Não consegui abrir $displayName")
        }
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

    private fun clearOutputResult() {
        lastOutputFile = null
        lastOutputUri = null
        lastOutputName = ""
        outputName.visibility = View.GONE
        outputActions.visibility = View.GONE
        outputStats.visibility = View.GONE
        status.text = ""
    }

    private fun pausePlayback() {
        pausePlayersOnly()
        activeSegment = Segment.NONE
        handler.removeCallbacks(playbackTicker)
        updatePlayButton(false)
    }

    private fun pausePlayersOnly() {
        try { if (mainPlayer?.isPlaying == true) mainPlayer?.pause() } catch (_: Throwable) {}
        try { if (insertedPlayer?.isPlaying == true) insertedPlayer?.pause() } catch (_: Throwable) {}
    }

    private fun isPlaying(): Boolean = try {
        mainPlayer?.isPlaying == true || insertedPlayer?.isPlaying == true
    } catch (_: Throwable) { false }

    private fun updatePlayButton(playing: Boolean) {
        playPause.setImageResource(if (playing) R.drawable.ic_ffmpeg_pause else R.drawable.ic_ffmpeg_play)
        playPause.contentDescription = if (playing) "Pausar" else "Reproduzir"
    }

    private fun seekPlayer(player: MediaPlayer?, positionMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) player?.seekTo(positionMs.coerceAtLeast(0L), MediaPlayer.SEEK_CLOSEST)
            else player?.seekTo(positionMs.coerceAtLeast(0L).toInt())
        } catch (_: Throwable) {
        }
    }

    private fun releasePlayers() {
        handler.removeCallbacks(playbackTicker)
        mainPlayer?.release()
        insertedPlayer?.release()
        mainPlayer = null
        insertedPlayer = null
        mainPrepared = false
        insertedPrepared = false
        activeSegment = Segment.NONE
        updatePlayButton(false)
    }

    override fun onPause() {
        pausePlayback()
        super.onPause()
    }

    override fun onDestroy() {
        releasePlayers()
        super.onDestroy()
    }

    private fun parseTime(value: String): Long? {
        val normalized = value.trim().replace(',', '.')
        val parts = normalized.split(':')
        return try {
            val seconds = when (parts.size) {
                1 -> parts[0].toDouble()
                2 -> parts[0].toDouble() * 60 + parts[1].toDouble()
                3 -> parts[0].toDouble() * 3600 + parts[1].toDouble() * 60 + parts[2].toDouble()
                else -> return null
            }
            (seconds * 1000.0).toLong().coerceAtLeast(0L)
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun formatTime(milliseconds: Long): String {
        val safe = milliseconds.coerceAtLeast(0L)
        val hours = safe / 3_600_000
        val minutes = (safe / 60_000) % 60
        val seconds = (safe / 1000) % 60
        val millis = safe % 1000
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    }

    private fun seconds(milliseconds: Long): String = decimal(milliseconds / 1000.0)
    private fun decimal(value: Double): String = String.format(Locale.US, "%.3f", value)
    private fun sanitizeBase(name: String): String = name.substringBeforeLast('.', name).replace(Regex("""[\\/:*?\"<>|]"""), "_").ifBlank { "audio" }
    private fun encoderForExtension(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "mp3" -> "libmp3lame"
        "flac" -> "flac"
        "ogg", "opus" -> "libopus"
        "wav" -> "pcm_s16le"
        else -> "aac"
    }

    private fun encoderForProfile(extension: String, profile: AudioProfile): String {
        val ext = extension.lowercase(Locale.ROOT)
        if (ext == "wav") return profile.pcmEncoder
        val codec = profile.codec.lowercase(Locale.ROOT)
        return when {
            codec.contains("alac") -> "alac"
            codec.contains("flac") -> "flac"
            codec.contains("pcm") || codec.contains("wav") -> "pcm_s16le"
            codec.contains("vorbis") -> "libvorbis"
            codec.contains("opus") -> "libopus"
            codec.contains("mp3") -> "libmp3lame"
            codec.contains("aac") -> "aac"
            ext == "ogg" || ext == "opus" -> if (codec.contains("vorbis")) "libvorbis" else "libopus"
            else -> encoderForExtension(ext)
        }
    }

    private fun audioMime(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        else -> "audio/mp4"
    }

    private data class AudioSource(val uri: Uri, val name: String, val durationMs: Long)
    private data class AudioProfile(
        val sampleRate: Int,
        val channels: Int,
        val bitrate: String,
        val codec: String = "aac",
        val pcmEncoder: String = "pcm_s16le"
    )
    private data class InsertAudioJobConfig(
        val insertionMs: Long,
        val selectedTransition: String,
        val transitionSeconds: Double,
        val mainAudioTrack: Int = 0,
        val insertedAudioTrack: Int = 0
    )
    private enum class Segment { NONE, MAIN_BEFORE, INSERTED, MAIN_AFTER }
    private class ProcessingCancelled : RuntimeException()

    companion object {
        private const val TAG = "FfmpegInsertAudio"
        private const val REQUEST_MAIN_AUDIO = 801
        private const val REQUEST_INSERT_AUDIO = 802
        private const val REQUEST_PRE_OUTPUT_DIR = 803
        private const val REQUEST_OUTPUT_DIR = 804
        private const val TRANSITION_NONE = "none"
        private const val TRANSITION_FADE = "fade"
        private val AUDIO_TRANSITIONS = listOf(
            "Sem transição" to TRANSITION_NONE,
            "Fade de entrada/saída" to TRANSITION_FADE,
            "Curva linear" to "tri",
            "Seno de quarto de onda" to "qsin",
            "Seno exponencial" to "esin",
            "Seno de meia onda" to "hsin",
            "Logarítmica" to "log",
            "Parábola invertida" to "ipar",
            "Quadrática" to "qua",
            "Cúbica" to "cub",
            "Raiz quadrada" to "squ",
            "Raiz cúbica" to "cbr",
            "Parábola" to "par",
            "Exponencial" to "exp",
            "Seno de quarto invertido" to "iqsin",
            "Seno de meia onda invertido" to "ihsin",
            "Assento exponencial duplo" to "dese",
            "Sigmoide exponencial dupla" to "desi",
            "Sigmoide logística" to "losi",
            "Função seno cardinal" to "sinc",
            "Seno cardinal invertido" to "isinc",
            "Quártica" to "quat",
            "Raiz quártica" to "quatr",
            "Seno de quarto ao quadrado" to "qsin2",
            "Seno de meia onda ao quadrado" to "hsin2",
            "Sem fade" to "nofade"
        )
        private val SUPPORTED_COPY_EXTENSIONS = setOf("m4a", "aac", "mp3", "wav", "flac", "ogg", "opus")
    }
}
