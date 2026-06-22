package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
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
    private lateinit var selectedCount: TextView
    private lateinit var controls: View
    private lateinit var buttonTransition: TextView
    private lateinit var inputTransitionTime: EditText
    private lateinit var checkReencode: CheckBox
    private lateinit var checkSmartJoin: CheckBox
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_ffmpeg_join_videos)

        joinScroll = findViewById(R.id.join_scroll)
        timelineScroll = findViewById(R.id.timeline_scroll)
        timeline = findViewById(R.id.join_timeline)
        selectedCount = findViewById(R.id.selected_count)
        controls = findViewById(R.id.join_controls)
        buttonTransition = findViewById(R.id.button_transition)
        inputTransitionTime = findViewById(R.id.input_transition_time)
        checkReencode = findViewById(R.id.check_reencode)
        checkSmartJoin = findViewById(R.id.check_smart_join)
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

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.button_select_videos).setOnClickListener { openVideoPicker() }
        buttonSelectOutputFolder.setOnClickListener { openOutputFolderPicker(REQUEST_CHOOSE_PRE_OUTPUT_DIR) }
        buttonTransition.setOnClickListener { showTransitionMenu() }
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

        checkReencode.setOnCheckedChangeListener { _, _ -> updateReencodeControls() }
        checkSmartJoin.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && selectedTransition == TRANSITION_FADE_IN_OUT) {
                selectedTransition = "fade"
                buttonTransition.text = "Transição: $selectedTransition"
            }
        }
        timeline.onOrderChanged = { ids ->
            val byId = clips.associateBy { it.id }
            clips.clear()
            clips.addAll(ids.mapNotNull { byId[it] })
            updateSelectionUi()
        }
        updateReencodeControls()
        updateSelectionUi()
    }

    @Deprecated("Deprecated Android callback kept for this legacy XML activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return

        when (requestCode) {
            REQUEST_PICK_VIDEOS -> handlePickedVideos(data)
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

    private fun openVideoPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PICK_VIDEOS)
    }

    private fun handlePickedVideos(data: Intent?) {
        val flags = data?.flags ?: 0
        val uris = mutableListOf<Uri>()
        data?.clipData?.let { clipData ->
            for (index in 0 until clipData.itemCount) {
                uris += clipData.getItemAt(index).uri
            }
        }
        data?.data?.let { uris += it }
        if (uris.isEmpty()) return

        clearOutputResult()
        uris.distinct().forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
            loadClip(uri)?.let { clips += it }
        }
        updateSelectionUi()
    }

    private fun loadClip(uri: Uri): JoinClip? {
        val name = queryDisplayName(uri) ?: "video_${clips.size + 1}.mp4"
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(1L) ?: 1L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.coerceAtLeast(2) ?: 1280
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.coerceAtLeast(2) ?: 720
            val rotationDegrees = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val thumbnail = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            JoinClip(System.nanoTime(), uri, name, durationMs, width, height, rotationDegrees, hasAudio, thumbnail)
        } catch (e: Throwable) {
            Log.e(TAG, "Could not load clip $name", e)
            Toast.makeText(this, "Não consegui ler $name.", Toast.LENGTH_SHORT).show()
            null
        } finally {
            retriever.release()
        }
    }

    private fun updateSelectionUi() {
        selectedCount.text = when (clips.size) {
            0 -> "Nenhum vídeo selecionado"
            1 -> "1 vídeo selecionado"
            else -> "${clips.size} vídeos selecionados"
        }
        controls.visibility = if (clips.isNotEmpty()) View.VISIBLE else View.GONE
        arrowInputOutput.visibility = if (clips.isNotEmpty()) View.VISIBLE else View.GONE
        buttonSelectOutputFolder.visibility = if (clips.isNotEmpty()) View.VISIBLE else View.GONE
        timeline.setClips(clips.map { FfmpegJoinTimelineView.Clip(it.id, it.name, it.durationMs, it.thumbnail) })
        setJoinEnabled(clips.size >= 2 && !isProcessing)
    }

    private fun updateReencodeControls() {
        val enabled = checkReencode.isChecked && !isProcessing
        buttonTransition.isEnabled = enabled
        inputTransitionTime.isEnabled = enabled
        val alpha = if (enabled) 1f else 0.42f
        buttonTransition.alpha = alpha
        inputTransitionTime.alpha = alpha
    }

    private fun setJoinEnabled(enabled: Boolean) {
        buttonJoin.alpha = if (enabled) 1f else 0.45f
        buttonJoin.isClickable = enabled
        buttonJoin.isFocusable = enabled
    }

    private fun showTransitionMenu() {
        PopupMenu(this, buttonTransition).apply {
            TRANSITIONS.forEach { transition ->
                if (checkSmartJoin.isChecked && transition == TRANSITION_FADE_IN_OUT) return@forEach
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
        AlertDialog.Builder(this)
            .setMessage("Sem reencodar, a junção usa -c copy e tende a ser rápida e sem perda, mas só funciona bem quando os vídeos têm formatos compatíveis.\n\nCom reencode, o app normaliza os vídeos e aplica a transição reencodando a saída, mantendo bitrate, resolução e fps sempre que conseguir detectar.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showTransitionHelp() {
        AlertDialog.Builder(this)
            .setMessage("Fade in/out é uma forma rápida de fazer a transição: o app copia quase todo o vídeo e reencoda apenas pequenos trechos de fade.\n\nAs outras opções são transições do FFmpeg. Elas podem ficar mais sofisticadas, mas exigem reencodar a saída de forma mais pesada.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showSmartJoinHelp() {
        AlertDialog.Builder(this)
            .setMessage("Tenta copiar a maior parte dos videos e reencodar apenas os trechos próximos à transição.\nVantagem: Acelera muito o processo.\nDesvantagem: Pode gerar pequenos bugs no vídeo.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun startJoin() {
        if (clips.size < 2) return
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
                val outputName = buildJoinedOutputName()
                val tempOutput = File(cacheDir, "join_${System.currentTimeMillis()}_$outputName")

                val result = if (checkSmartJoin.isChecked) {
                    executeSmartJoinExperiment(copiedInputs, tempOutput)
                } else if (checkReencode.isChecked) {
                    if (isFadeInOutTransition()) {
                        executeMinimalTransitionJoin(copiedInputs, tempOutput)
                    } else {
                        val taskLabel = "Aplicando transições (h264_mediacodec + aac)"
                        val session = executeFfmpegWithProgress(
                            buildReencodeArguments(copiedInputs, tempOutput, withTransition = true),
                            totalDurationMs(),
                            taskLabel
                        )
                        JoinExecutionResult(
                            success = ReturnCode.isSuccess(session.returnCode) && tempOutput.exists() && tempOutput.length() > 0L,
                            cancelled = ReturnCode.isCancel(session.returnCode),
                            failureMessage = ffmpegFailureMessage(taskLabel, session)
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
                    joinScroll.post { joinScroll.smoothScrollTo(0, outputActions.bottom) }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to join videos", e)
                runOnUiThread {
                    setProcessing(false)
                    failActiveStep("Erro: ${e.message ?: "falha inesperada"}")
                }
            } finally {
                copiedInputs.forEach { it.delete() }
            }
        }.start()
    }

    private fun buildDirectConcatArguments(inputs: List<File>, outputFile: File): Array<String> {
        val listFile = File(cacheDir, "join_list_${System.currentTimeMillis()}.txt")
        listFile.writeText(inputs.joinToString("\n") { "file '${it.absolutePath.replace("\\", "/")}'" }, Charsets.UTF_8)
        return arrayOf(
            "-y",
            "-f", "concat",
            "-safe", "0",
            "-i", listFile.absolutePath,
            "-c", "copy",
            "-movflags", "+faststart",
            outputFile.absolutePath
        )
    }

    private fun executeSmartJoinExperiment(inputs: List<File>, outputFile: File): JoinExecutionResult {
        Log.d(TAG, "Executing SmartJoin Experiment branch")
        val workDir = File(cacheDir, "smartjoin_mkv_${System.currentTimeMillis()}").apply { mkdirs() }
        val pieces = mutableListOf<SmartJoinPiece>()
        val transitionSeconds = safeTransitionSeconds()
        val profile = detectOutputProfile(inputs.firstOrNull(), clips.firstOrNull())

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

                    val bodySession = executeFfmpegWithProgress(args.toTypedArray(), (bodyDuration * 1000.0).toLong().coerceAtLeast(1L), bodyLabel)
                    if (ReturnCode.isCancel(bodySession.returnCode)) return JoinExecutionResult(false, true, "")
                    if (!ReturnCode.isSuccess(bodySession.returnCode) || !bodyFile.exists() || bodyFile.length() == 0L) {
                        return JoinExecutionResult(false, false, ffmpegFailureMessage(bodyLabel, bodySession))
                    }
                    pieces += SmartJoinPiece(bodyFile, bodyDuration)
                }

                // 2. Gerar Transição xfade (TS)
                if (index < inputs.lastIndex) {
                    val transitionFileTs = File(workDir, "transition_${index.toString().padStart(3, '0')}.ts")
                    val transitionLabel = "Gerando xfade ${index + 1}/${clips.size - 1} (TS)"
                    
                    val args = buildTransitionArgumentsMkv(
                        firstInput = inputs[index],
                        secondInput = inputs[index + 1],
                        firstClip = clips[index],
                        secondClip = clips[index + 1],
                        outputFile = transitionFileTs,
                        transitionSeconds = transitionSeconds,
                        profile = profile
                    )

                    val transitionSession = executeFfmpegWithProgress(args, (transitionSeconds * 1000.0).toLong().coerceAtLeast(1L), transitionLabel)
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
        profile: OutputProfile
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
        
        val targetCodec = if (profile.videoCodec == "hevc") "libx265" else "libx264"
        if (isEncoderAvailable(targetCodec)) {
            args.addAll(listOf("-c:v", targetCodec, "-preset", "ultrafast"))
        } else {
            args.addAll(listOf("-c:v", profile.videoEncoder))
        }

        args.addAll(
            listOf(
                "-b:v", profile.videoBitrate,
                "-minrate", profile.videoBitrate,
                "-maxrate", profile.videoBitrate,
                "-bufsize", profile.videoBufferSize,
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

            for (index in inputs.indices) {
                val clip = clips[index]
                val clipSeconds = (clip.durationMs / 1000.0).coerceAtLeast(0.001)
                val bodyStart = if (index == 0) 0.0 else transitionSeconds
                val bodyEnd = if (index == inputs.lastIndex) clipSeconds else (clipSeconds - transitionSeconds)
                val bodyDuration = bodyEnd - bodyStart

                if (bodyDuration > 0.12) {
                    val bodyFile = File(workDir, "body_${index.toString().padStart(3, '0')}.ts")
                    val bodyLabel = if (clip.hasAudio) {
                        "Copiando trecho ${index + 1}/${clips.size} (aac áudio)"
                    } else {
                        "Copiando trecho ${index + 1}/${clips.size}"
                    }
                    val bodySession = executeFfmpegWithProgress(
                        buildBodyCopyArguments(inputs[index], bodyFile, bodyStart, bodyDuration, clip, profile),
                        (bodyDuration * 1000.0).toLong().coerceAtLeast(1L),
                        bodyLabel
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
                    val fadeOutLabel = "Gerando fade-out ${index + 1}/${clips.size - 1} (${profile.videoEncoder} + aac)"
                    val fadeOutStart = (clipSeconds - transitionSeconds).coerceAtLeast(0.0)
                    val fadeOutSession = executeFfmpegWithProgress(
                        buildFadeEdgeArguments(
                            inputFile = inputs[index],
                            outputFile = fadeOutFile,
                            startSeconds = fadeOutStart,
                            transitionSeconds = transitionSeconds,
                            clip = clips[index],
                            profile = profile,
                            fadeIn = false
                        ),
                        (transitionSeconds * 1000.0).toLong().coerceAtLeast(1L),
                        fadeOutLabel
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
                    pieces += SmartJoinPiece(fadeOutFile, transitionSeconds)

                    val fadeInFile = File(workDir, "fade_in_${index.toString().padStart(3, '0')}.ts")
                    val fadeInLabel = "Gerando fade-in ${index + 1}/${clips.size - 1} (${profile.videoEncoder} + aac)"
                    val fadeInSession = executeFfmpegWithProgress(
                        buildFadeEdgeArguments(
                            inputFile = inputs[index + 1],
                            outputFile = fadeInFile,
                            startSeconds = 0.0,
                            transitionSeconds = transitionSeconds,
                            clip = clips[index + 1],
                            profile = profile,
                            fadeIn = true
                        ),
                        (transitionSeconds * 1000.0).toLong().coerceAtLeast(1L),
                        fadeInLabel
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
                    pieces += SmartJoinPiece(fadeInFile, transitionSeconds)
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

    private fun buildTransitionArguments(
        firstInput: File,
        secondInput: File,
        firstClip: JoinClip,
        secondClip: JoinClip,
        outputFile: File,
        transitionSeconds: Double,
        profile: OutputProfile
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
        
        val targetCodec = if (profile.videoCodec == "hevc") "libx265" else "libx264"
        if (isEncoderAvailable(targetCodec)) {
            args.addAll(listOf("-c:v", targetCodec, "-preset", "ultrafast"))
        } else {
            args.addAll(listOf("-c:v", profile.videoEncoder))
        }

        args.addAll(
            listOf(
                "-bsf:v", profile.videoBitstreamFilter,
                "-b:v", profile.videoBitrate,
                "-minrate", profile.videoBitrate,
                "-maxrate", profile.videoBitrate,
                "-bufsize", profile.videoBufferSize,
                "-r", profile.fps,
                "-vsync", "cfr",
                "-g", "1",
                "-bf", "0",
                "-force_key_frames", "expr:gte(t,n_forced*0.25)",
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
        transitionSeconds: Double,
        clip: JoinClip,
        profile: OutputProfile,
        fadeIn: Boolean
    ): Array<String> {
        val fadeType = if (fadeIn) "in" else "out"
        val videoFilter = "[0:v]${videoNormalizeFilter(profile)}," +
            "fade=t=$fadeType:st=0:d=${formatDecimal(transitionSeconds)}," +
            "settb=AVTB,setpts=PTS-STARTPTS[vout]"
        val audioFilter = if (clip.hasAudio) {
            "[0:a]${audioNormalizeFilter(profile)}," +
                "afade=t=$fadeType:st=0:d=${formatDecimal(transitionSeconds)}," +
                "aformat=sample_fmts=fltp:sample_rates=${profile.audioSampleRate}:channel_layouts=${profile.audioLayout}," +
                "asetpts=N/SR/TB[aout]"
        } else {
            "anullsrc=channel_layout=${profile.audioLayout}:sample_rate=${profile.audioSampleRate}," +
                "atrim=0:${formatDecimal(transitionSeconds)},asetpts=N/SR/TB[aout]"
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
                "-t", formatDecimal(transitionSeconds),
                "-i", inputFile.absolutePath,
                "-filter_complex", "$videoFilter;$audioFilter",
                "-map", "[vout]",
                "-map", "[aout]"
            )
        )
        
        val targetCodec = if (profile.videoCodec == "hevc") "libx265" else "libx264"
        if (isEncoderAvailable(targetCodec)) {
            args.addAll(listOf("-c:v", targetCodec, "-preset", "ultrafast"))
        } else {
            // Se o codec de software nativo não existir na compilação do ffmpeg-kit do usuário,
            // temos que usar o encoder detectado (mesmo sendo hardware) e torcer pra não travar.
            args.addAll(listOf("-c:v", profile.videoEncoder))
        }

        args.addAll(
            listOf(
                "-bsf:v", profile.videoBitstreamFilter,
                "-b:v", profile.videoBitrate,
                "-minrate", profile.videoBitrate,
                "-maxrate", profile.videoBitrate,
                "-bufsize", profile.videoBufferSize,
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
        val xfade = "[v0][v1]xfade=transition=$selectedTransition:duration=${formatDecimal(transitionSeconds)}:offset=${formatDecimal(transitionOffset)},trim=start=${formatDecimal(transitionOffset)}:end=${formatDecimal(transitionEnd)},fps=${profile.fps},setparams=range=tv:color_primaries=bt709:color_trc=bt709:colorspace=bt709,settb=AVTB,setpts=N/(${profile.fps}*TB)[vout]"
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
        args.addAll(
            listOf(
                "-filter_complex", filter,
                "-map", "[vout]",
                "-map", "[aout]",
                "-c:v", "h264_mediacodec",
                "-b:v", outputProfile.videoBitrate,
                "-minrate", outputProfile.videoBitrate,
                "-maxrate", outputProfile.videoBitrate,
                "-bufsize", outputProfile.videoBufferSize,
                "-r", outputProfile.fps,
                "-c:a", "aac",
                "-b:a", outputProfile.audioBitrate,
                "-ar", outputProfile.audioSampleRate.toString(),
                "-ac", outputProfile.audioChannels.toString(),
                "-movflags", "+faststart",
                outputFile.absolutePath
            )
        )
        return args.toTypedArray()
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
                parts += "[$lastV][v$index]xfade=transition=$selectedTransition:duration=${formatDecimal(transitionSeconds)}:offset=${formatDecimal(offset)}[$videoOut]"
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
        return requested.coerceIn(0.1, (shortest - 0.1).coerceAtLeast(0.1))
    }

    private fun transitionWindowSeconds(firstClip: JoinClip, secondClip: JoinClip, transitionSeconds: Double): Double {
        val firstSeconds = (firstClip.durationMs / 1000.0).coerceAtLeast(transitionSeconds)
        val secondSeconds = (secondClip.durationMs / 1000.0).coerceAtLeast(transitionSeconds)
        val maxWindow = minOf(firstSeconds, secondSeconds)
        return (transitionSeconds * 2.0).coerceAtMost(maxWindow).coerceAtLeast(transitionSeconds)
    }

    private fun executeFfmpegWithProgress(arguments: Array<String>, expectedDurationMs: Long, taskLabel: String): FFmpegSession {
        val latch = CountDownLatch(1)
        val sessionRef = AtomicReference<FFmpegSession>()
        val safeDuration = expectedDurationMs.coerceAtLeast(1L)
        val startedAt = SystemClock.elapsedRealtime()
        updateStep(taskLabel, 0, StepState.RUNNING)
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
                        updateStep(taskLabel, percent, StepState.RUNNING, formatEfficiency(statistics.time, startedAt))
                    }
                }
            }
        )
        currentSessionId = session.sessionId
        latch.await()
        currentSessionId = null
        val completedSession = sessionRef.get() ?: session
        when {
            ReturnCode.isSuccess(completedSession.returnCode) -> updateStep(taskLabel, 100, StepState.DONE)
            ReturnCode.isCancel(completedSession.returnCode) -> updateStep(taskLabel, null, StepState.ERROR, "cancelado")
            else -> updateStep(taskLabel, null, StepState.ERROR)
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
        val details = session.allLogsAsString
            .orEmpty()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeLast(8)
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
            buttonJoin.contentDescription = "Juntar vídeos"
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
            val document = destDir.createFile("video/mp4", outputName)
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
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Não encontrei um app para abrir o vídeo.", Toast.LENGTH_SHORT).show()
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
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Compartilhar arquivo"))
        } catch (_: Throwable) {
            Toast.makeText(this, "Não consegui compartilhar o arquivo.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearOutputResult() {
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

    private fun initProcessingSteps() {
        processingSteps.clear()
        processingSteps += ProcessingStep("Preparar arquivos de entrada")
        if (checkSmartJoin.isChecked) {
            clips.forEachIndexed { index, _ ->
                processingSteps += ProcessingStep("Copiando corpo ${index + 1}/${clips.size} (TS)")
                if (index < clips.lastIndex) {
                    processingSteps += ProcessingStep("Gerando xfade ${index + 1}/${clips.size - 1} (TS)")
                }
            }
            processingSteps += ProcessingStep("Juntando experimento (TS -> MP4)")
        } else if (checkReencode.isChecked) {
            if (isFadeInOutTransition()) {
                clips.forEachIndexed { index, _ ->
                    processingSteps += ProcessingStep("Copiando trecho ${index + 1}/${clips.size}")
                    if (index < clips.lastIndex) {
                        processingSteps += ProcessingStep("Gerando fade-out ${index + 1}/${clips.size - 1}")
                        processingSteps += ProcessingStep("Gerando fade-in ${index + 1}/${clips.size - 1}")
                    }
                }
                processingSteps += ProcessingStep("Juntando trechos preservados")
            } else {
                processingSteps += ProcessingStep("Aplicando transições (h264_mediacodec + aac)")
            }
        } else {
            processingSteps += ProcessingStep("Juntando sem reencodar")
        }
        processingSteps += ProcessingStep("Preparar arquivo para salvar")
        renderProcessingSteps()
    }

    private fun isFadeInOutTransition(): Boolean = selectedTransition == TRANSITION_FADE_IN_OUT

    private fun updateStep(label: String, percent: Int?, state: StepState, detail: String? = null) {
        val action = {
            val step = processingSteps.firstOrNull { it.label == label }
                ?: processingSteps.firstOrNull { label.startsWith("${it.label} (") }
            if (step != null) {
                if (!(step.state == StepState.DONE && state == StepState.RUNNING)) {
                    if (step.label != label) step.label = label
                    percent?.let { step.percent = it.coerceIn(0, 100) }
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
            val prefix = when (step.state) {
                StepState.PENDING -> "    "
                else -> "${step.percent}% "
            }
            builder.append(prefix)
            builder.append(step.label)
            step.detail?.let { builder.append(" | ").append(it) }
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

    private fun buildJoinedOutputName(): String {
        val baseName = clips.joinToString("+") { clip ->
            sanitizeFileNameBase(clip.name.substringBeforeLast('.', clip.name))
        }.ifBlank {
            "videos_juntos"
        }
        return "$baseName.mp4"
    }

    private fun sanitizeFileNameBase(name: String): String {
        return name
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .ifBlank { "video" }
    }

    private fun detectVideoBitrate(inputFile: File?): String? {
        if (inputFile == null) return null
        return try {
            val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-i", inputFile.absolutePath))
            val logs = session.allLogsAsString.orEmpty()
            bestVideoBitrate(inputFile, logs, logs.lines().firstOrNull { it.contains("Video:", ignoreCase = true) }.orEmpty())
        } catch (_: Throwable) {
            null
        }
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

    private var knownEncoders: String? = null

    private fun isEncoderAvailable(encoder: String): Boolean {
        if (knownEncoders == null) {
            try {
                val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-encoders"))
                knownEncoders = session.allLogsAsString.orEmpty()
            } catch (e: Throwable) {
                knownEncoders = ""
            }
        }
        return knownEncoders!!.contains(encoder, ignoreCase = true)
    }

    private fun detectOutputProfile(inputFile: File?, firstClip: JoinClip?): OutputProfile {
        val fallbackWidth = makeEven(firstClip?.width ?: 1280).coerceAtLeast(2)
        val fallbackHeight = makeEven(firstClip?.height ?: 720).coerceAtLeast(2)
        if (inputFile == null) {
            return OutputProfile(
                width = fallbackWidth,
                height = fallbackHeight,
                fps = "30",
                rotationDegrees = normalizeRotationForMetadata(firstClip?.rotationDegrees ?: 0),
                videoCodec = DEFAULT_VIDEO_CODEC,
                videoEncoder = videoEncoderFor(DEFAULT_VIDEO_CODEC),
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
                videoEncoder = videoEncoderFor(videoCodec),
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
                videoEncoder = videoEncoderFor(DEFAULT_VIDEO_CODEC),
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

    private fun detectContainerBitrate(inputFile: File): String? {
        return detectContainerBitrateKbps(inputFile)?.let { "${it.coerceAtLeast(1)}k" }
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

    private fun videoEncoderFor(codec: String): String {
        return when (codec) {
            "hevc" -> "hevc_mediacodec"
            else -> "h264_mediacodec"
        }
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
        val thumbnail: Bitmap?
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

    private data class ProcessingStep(
        var label: String,
        var percent: Int = 0,
        var state: StepState = StepState.PENDING,
        var detail: String? = null
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
        private const val DEFAULT_VIDEO_CODEC = "h264"
        private const val TRANSITION_FADE_IN_OUT = "Fade in/out"
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
    }
}
