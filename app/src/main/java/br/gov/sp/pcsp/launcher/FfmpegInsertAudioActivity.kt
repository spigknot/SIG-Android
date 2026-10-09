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
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
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
import com.arthenica.ffmpegkit.FFprobeKit
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToLong
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** Tela de inserção de áudio, seleção de faixas e prévia com os mesmos filtros da exportação. */

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
    private lateinit var smartInsertCheck: CheckBox
    private lateinit var smartInsertHelp: TextView
    private lateinit var insertHint: TextView
    private var smartInsertEnabled = false
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
    private val previewSource = FfmpegPreviewSource(handler)
    private var startAfterSeek = false
    private val previewSeeker = FfmpegPreviewSeeker(handler) {
        if (startAfterSeek && filteredPreviewReady) {
            startAfterSeek = false
            filteredPreviewPlayer?.start()
            updatePlayButton(true)
            handler.removeCallbacks(playbackTicker)
            handler.post(playbackTicker)
        }
    }
    private var mainAudio: AudioSource? = null
    private var insertedAudio: AudioSource? = null
    private val selectedAudioTracks = mutableMapOf<String, Int>()
    private var insertionMs = 0L
    private var compositePositionMs = 0L
    private var filteredPreviewPlayer: MediaPlayer? = null
    private var filteredPreviewFile: File? = null
    private var filteredPreviewPlan: SmartInsertPlanner.Render? = null
    private var filteredPreviewKey: String? = null
    private var filteredPreviewReady = false
    @Volatile private var previewGeneration = 0L
    @Volatile private var previewRendering = false
    private var playbackSpeed = 1f
    private val speedSteps = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f)
    private var selectedTransition = TRANSITION_NONE
    private var isProcessing = false
    @Volatile private var selectionGeneration = 0L
    @Volatile private var selectionSessionId: Long? = null
    @Volatile private var processingCancelled = false
    @Volatile private var previewCancelled = false
    @Volatile private var previewSessionId: Long? = null
    @Volatile private var currentSessionId: Long? = null
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

    private val recovery by lazy { FfmpegRecoveryUi(this,"insert",::recoveryRequest,::restoreRecovery) { startInsert() } }
    private fun recoveryRequest(): JSONObject = JSONObject().put("main",sourceRecovery(mainAudio!!)).put("inserted",sourceRecovery(insertedAudio!!)).put("insertion",insertionMs).put("transition",selectedTransition)
    private fun restoreRecovery(r: JSONObject) {
        mainAudio=restoreSourceRecovery(r.getJSONObject("main"));insertedAudio=restoreSourceRecovery(r.getJSONObject("inserted"));insertionMs=r.getLong("insertion");selectedTransition=r.getString("transition")
    }

    private fun sourceRecovery(source: AudioSource): JSONObject = JSONObject().put("uri",source.uri.toString()).put("name",source.name).put("duration",source.durationMs)
        .put("cached",source.cachedFile?.absolutePath).put("track",selectedAudioTracks[trackKey(source)] ?: 0)
    private fun restoreSourceRecovery(r: JSONObject): AudioSource {
        val cached=r.optString("cached").takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile }
        val source=AudioSource(recovery.uri(r.getString("uri")),r.getString("name"),r.getLong("duration"),cached)
        selectedAudioTracks[trackKey(source)]=r.getInt("track");return source
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
        smartInsertCheck = findViewById(R.id.check_smart_insert)
        smartInsertHelp = findViewById(R.id.help_smart_insert)
        insertHint = findViewById(R.id.text_insert_hint)
        smartInsertCheck.setOnCheckedChangeListener { _, marcado ->
            smartInsertEnabled = marcado
            stopFilteredPreview()
            updateInsertHint()
            refreshCommandPreview()
        }
        smartInsertHelp.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(
                    "Smart Insert (experimental): cortes precisos com cópia do principal em PCM/WAV, ALAC/M4A e FLAC nativo. " +
                        "Somente o inserido e, quando necessário, a pequena emenda são recodificados.\n\n" +
                        "As curvas suavizam apenas o áudio inserido, sem reduzir a duração total. Zero segundo e Sem transição não aplicam efeito.\n\n" +
                        "AAC, MP3, Opus, Vorbis e outros formatos sem emendas confiáveis usam recodificação contínua com aviso, mantendo o efeito selecionado. " +
                        "Recodificar áudio costuma ser leve."
                )
                .setPositiveButton("OK", null)
                .show()
        }
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
            isTaskRunning = { isProcessing || previewRendering },
            cancelTask = { if(isProcessing)cancelProcessing() else stopFilteredPreview() }
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
        transitionTime.doAfterTextChanged { stopFilteredPreview();refreshCommandPreview() }
        updateOptionState()
        updateSpeedButtons()
        refreshCommandPreview()
        handleIncomingShareIntent(intent)
        window.decorView.post { recovery.offer() }
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
            if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) { }
        val version=++selectionGeneration
        selectionSessionId?.let { FFmpegKit.cancel(it) }
        pausePlayback();stopFilteredPreview()
        status.text="Lendo faixas e duração do áudio..."
        executeButton.isEnabled=false
        Thread {
            var cached: File?=null
            try {
                val name=MediaUriSupport.queryDisplayName(contentResolver,uri) ?: "audio"
                val file=copyUriToCache(uri,name,"insert_selected") {
                    if(version!=selectionGeneration)throw ProcessingCancelled()
                }
                cached=file
                val latch=CountDownLatch(1)
                val session=FFprobeKit.executeWithArgumentsAsync(arrayOf("-v","error","-show_streams","-show_format","-of","json",file.absolutePath)) { latch.countDown() }
                selectionSessionId=session.sessionId
                if(version!=selectionGeneration)FFmpegKit.cancel(session.sessionId)
                latch.await()
                if(version!=selectionGeneration)throw ProcessingCancelled()
                check(ReturnCode.isSuccess(session.returnCode)) { "Não foi possível ler o áudio selecionado." }
                val info=JSONObject(session.output)
                val array=info.getJSONArray("streams")
                val count=(0 until array.length()).count { array.getJSONObject(it).optString("codec_type")=="audio" }
                check(count>0) { "O arquivo não contém faixas de áudio." }
                val tracks=(0 until count).map { SmartInsertPlanner.readAudio(info,it) }
                val source=AudioSource(uri,name,(tracks.first().duration*1000).roundToLong(),file,tracks)
                runOnUiThread {
                    if(version!=selectionGeneration || isDestroyed || isProcessing) {
                        file.delete()
                    } else {
                        clearOutputResult();pausePlayback();stopFilteredPreview()
                        if(primary) {
                            mainAudio?.let { selectedAudioTracks.remove(trackKey(it)) }
                            insertedAudio?.let { selectedAudioTracks.remove(trackKey(it)) }
                            mainAudio?.cachedFile?.delete();insertedAudio?.cachedFile?.delete()
                            mainAudio=source;insertedAudio=null;insertionMs=0;compositePositionMs=0
                            mainName.text=source.name;insertName.visibility=View.GONE
                            selectInsert.isEnabled=true;selectInsert.alpha=1f
                            options.visibility=View.GONE;arrowInputOutput.visibility=View.GONE;selectOutputFolder.visibility=View.GONE
                        } else {
                            val main=mainAudio
                            if(main==null) { file.delete();return@runOnUiThread }
                            insertedAudio?.let { selectedAudioTracks.remove(trackKey(it)) }
                            insertedAudio?.cachedFile?.delete()
                            insertionMs=compositeToMainTime(compositePositionMs).coerceIn(0L,main.durationMs)
                            insertedAudio=source;compositePositionMs=insertionMs
                            insertName.text="Inserir: ${source.name}";insertName.visibility=View.VISIBLE
                            options.visibility=View.VISIBLE;arrowInputOutput.visibility=View.VISIBLE;selectOutputFolder.visibility=View.VISIBLE
                        }
                        configureTimeline();preparePlayers();updateInsertHint();refreshCommandPreview()
                        executeButton.isEnabled=true
                    }
                }
                cached=null
            } catch (_: ProcessingCancelled) {
                // Uma seleção mais recente ou a saída da tela descartou esta leitura.
            } catch(error: Throwable) {
                Log.e(TAG,"Could not read audio",error)
                runOnUiThread { if(version==selectionGeneration && !isDestroyed) {
                    executeButton.isEnabled=true;status.text=error.message ?: "Não consegui ler o áudio."
                } }
            } finally {
                cached?.delete()
                if(version==selectionGeneration)selectionSessionId=null
            }
        }.start()
    }

    private fun updateInsertHint() {
        if(!::insertHint.isInitialized)return
        val source=mainAudio
        val track=source?.let { selectedAudioTracks[trackKey(it)] } ?: 0
        val audio=source?.nativeAudio?.getOrNull(track)
        val extension=source?.name?.substringAfterLast('.',"")?.lowercase(Locale.ROOT).orEmpty()
        insertHint.text=if(!smartInsertEnabled) {
            "Inserção precisa: recodificação contínua respeitando o ponto e o efeito escolhidos."
        } else if(audio!=null && SmartInsertPlanner.canCopy(audio,extension)) {
            if(audio.codec in SmartInsertPlanner.pcmCodecs)
                "Smart Insert: copia o principal por amostras e trata somente o inserido. Sem efeito e com PCM compatível, ambos são copiados."
            else "Smart Insert: copia o principal; recodifica somente o inserido e a pequena emenda do corte."
        } else {
            "Smart Insert: ${audio?.codec?.uppercase(Locale.ROOT) ?: "este formato"} usa recodificação contínua para evitar erros nas emendas, mantendo o efeito escolhido. Recodificar áudio costuma ser leve."
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

    private fun preparePlayers() { stopFilteredPreview() }

    private fun togglePlayback() {
        if(previewRendering) { stopFilteredPreview();return }
        if(isPlaying() || startAfterSeek) { finishTimeEditing();pausePlayback();return }
        finishTimeEditing()
        val main=mainAudio ?: return
        listOfNotNull(main,insertedAudio).firstOrNull {
            audioTrackCount(it.uri)>1 && selectedAudioTracks[trackKey(it)]==null
        }?.let { source -> requestAudioTrack(source) { togglePlayback() };return }
        if(compositePositionMs>=compositeDurationMs())compositePositionMs=0
        startFilteredPreview()
    }

    private fun startFilteredPreview() {
        val main=mainAudio ?: return
        val inserted=insertedAudio
        val options=try { selectedOptions() } catch(error: IllegalStateException) { status.text=error.message;return }
        val key=listOf(main.cachedFile?.absolutePath,inserted?.cachedFile?.absolutePath,options).toString()
        if(filteredPreviewKey==key && filteredPreviewPlayer!=null && filteredPreviewReady) {
            startFilteredPlayer();return
        }
        stopFilteredPreview()
        val version=previewGeneration
        previewCancelled=false;previewRendering=true
        status.text="Preparando prévia com a faixa e o efeito selecionados..."
        updatePlayButton(true)
        Thread {
            val work=File(cacheDir,"insert_preview_${UUID.randomUUID()}")
            val temporary=mutableListOf<File>()
            var retained=false
            fun checkCancelled() { if(previewCancelled || version!=previewGeneration)throw ProcessingCancelled() }
            try {
                check(work.mkdirs()) { "Não foi possível criar a prévia." }
                fun input(source: AudioSource): File = source.cachedFile?.takeIf { it.isFile }
                    ?: copyUriToCache(source.uri,source.name,"insert_preview_input",::checkCancelled).also { temporary+=it }
                val pipeline=SmartInsertPipeline(
                    execute={ args,_ ->
                        checkCancelled()
                        val latch=CountDownLatch(1)
                        val session=FFmpegKit.executeWithArgumentsAsync(args) { latch.countDown() }
                        previewSessionId=session.sessionId
                        if(previewCancelled || version!=previewGeneration)FFmpegKit.cancel(session.sessionId)
                        try {
                            latch.await();checkCancelled()
                            if(ReturnCode.isCancel(session.returnCode))throw ProcessingCancelled()
                            check(ReturnCode.isSuccess(session.returnCode)) { ffmpegFailureMessage(session) }
                        } finally { if(previewSessionId==session.sessionId)previewSessionId=null }
                    },
                    probe={ args -> checkCancelled();probeInsert(args,true).also { checkCancelled() } },
                    cancelled=::checkCancelled
                )
                val output=File(work,"preview.wav")
                val result=if(inserted==null)pipeline.singlePreview(input(main),output,options.mainTrack)
                    else pipeline.run(input(main),input(inserted),output,options,preview=true)
                checkCancelled()
                runOnUiThread {
                    if(version!=previewGeneration || previewCancelled || isDestroyed || isProcessing) {
                        Thread { work.deleteRecursively() }.start()
                    } else {
                        filteredPreviewFile=output;filteredPreviewPlan=result.plan;filteredPreviewKey=key
                        mainAudio=mainAudio?.copy(durationMs=(result.plan.main*1000.0/result.audio.rate).roundToLong())
                        if(inserted!=null)insertedAudio=insertedAudio?.copy(durationMs=(result.plan.inserted*1000.0/result.audio.rate).roundToLong())
                        configureTimeline()
                        val player=MediaPlayer()
                        filteredPreviewPlayer=player
                        try {
                            player.setOnPreparedListener {
                                if(version==previewGeneration && !previewCancelled) {
                                    previewRendering=false;filteredPreviewReady=true;previewSeeker.attach(it);applyPlaybackSpeed(it);it.pause();startFilteredPlayer()
                                }
                            }
                            player.setOnCompletionListener { if(filteredPreviewPlayer===it)finishPlayback() }
                            player.setOnErrorListener { failed,_,_ ->
                                if(filteredPreviewPlayer!==failed)return@setOnErrorListener true
                                status.text="Não foi possível reproduzir a prévia.";stopFilteredPreview();true
                            }
                            previewSource.prepare(player, { player.setDataSource(output.absolutePath) }) { error ->
                                if (filteredPreviewPlayer === player) {
                                    stopFilteredPreview();status.text=error.message
                                }
                            }
                        } catch(error: Throwable) { stopFilteredPreview();status.text=error.message }
                    }
                }
                retained=true
            } catch(_: ProcessingCancelled) { }
            catch(error: Throwable) {
                Log.e(TAG,"Filtered preview failed",error)
                runOnUiThread { if(version==previewGeneration && !isDestroyed) {
                    previewRendering=false;updatePlayButton(false);status.text=error.message ?: "Não foi possível preparar a prévia."
                } }
            } finally { temporary.forEach { it.delete() };if(!retained)work.deleteRecursively() }
        }.start()
    }

    private fun startFilteredPlayer() {
        val player=filteredPreviewPlayer ?: return
        val plan=filteredPreviewPlan ?: return
        if(!filteredPreviewReady)return
        val sample=SmartInsertPlanner.sampleCount(compositePositionMs/1000.0,plan.rate)
        val position=(plan.outputPosition(sample)*1000.0/plan.rate).roundToLong()
        if(!previewSeeker.isSeeking && kotlin.math.abs(player.currentPosition.toLong()-position)<=20L) {
            player.start();updatePlayButton(true)
            handler.removeCallbacks(playbackTicker);handler.post(playbackTicker)
            return
        }
        startAfterSeek=true
        previewSeeker.request(position, immediate=true)
    }

    private fun stopFilteredPreview() {
        startAfterSeek=false
        previewSeeker.reset()
        previewCancelled=true;previewGeneration++
        previewSessionId?.let { FFmpegKit.cancel(it) }
        previewRendering=false
        previewSource.release(filteredPreviewPlayer);filteredPreviewPlayer=null;filteredPreviewReady=false
        filteredPreviewFile?.parentFile?.takeIf { it.parentFile==cacheDir && it.name.startsWith("insert_preview_") }?.let { directory ->
            Thread { directory.deleteRecursively() }.start()
        }
        filteredPreviewFile=null;filteredPreviewPlan=null;filteredPreviewKey=null
        handler.removeCallbacks(playbackTicker)
        if(::playPause.isInitialized)updatePlayButton(false)
    }

    private fun updateCompositePlaybackPosition() {
        val player=filteredPreviewPlayer ?: return
        val plan=filteredPreviewPlan ?: return
        if(!filteredPreviewReady || previewSeeker.isSeeking)return
        val sample=SmartInsertPlanner.sampleCount(player.currentPosition/1000.0,plan.rate)
        compositePositionMs=(plan.compositePosition(sample)*1000.0/plan.rate).roundToLong().coerceIn(0,compositeDurationMs())
        timeline.setCurrent(compositePositionMs)
    }

    private fun finishPlayback() {
        compositePositionMs=compositeDurationMs();timeline.setCurrent(compositePositionMs)
        pausePlayersOnly();updatePlayButton(false);handler.removeCallbacks(playbackTicker)
    }

    private fun seekComposite(positionMs: Long) {
        finishTimeEditing()
        compositePositionMs=positionMs.coerceIn(0,compositeDurationMs())
        val plan=filteredPreviewPlan
        if(plan!=null && filteredPreviewReady) {
            val sample=SmartInsertPlanner.sampleCount(compositePositionMs/1000.0,plan.rate)
            seekPlayer(filteredPreviewPlayer,(plan.outputPosition(sample)*1000.0/plan.rate).roundToLong())
        }
        timeline.setCurrent(compositePositionMs)
    }

    private fun finishTimeEditing() { if(inputTime.hasFocus())inputTime.clearFocus() }

    private fun applyTypedInsertionTime() {
        val main = mainAudio ?: return
        val parsed = parseTime(inputTime.text.toString()) ?: run {
            inputTime.setText(formatTime(insertionMs))
            return
        }
        val next=parsed.coerceIn(0L,main.durationMs)
        if(next!=insertionMs)stopFilteredPreview()
        pausePlayback()
        insertionMs = next
        compositePositionMs = insertionMs
        configureTimeline()
        timeline.setCurrent(compositePositionMs)
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
        applyPlaybackSpeed(filteredPreviewPlayer)
        updateSpeedButtons()
        if (!wasPlaying) pausePlayersOnly()
    }

    private fun applyPlaybackSpeed(player: MediaPlayer?) {
        if (player == null || !filteredPreviewReady || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val playing = player.isPlaying
            player.playbackParams = player.playbackParams.setSpeed(playbackSpeed)
            if (!playing) player.pause()
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
                stopFilteredPreview()
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

    private fun selectedOptions(): SmartInsertPipeline.Options {
        val seconds = if (selectedTransition == TRANSITION_NONE) 0.0 else
            transitionTime.text.toString().replace(',', '.').toDoubleOrNull()
                ?.takeIf { it.isFinite() && it >= 0 } ?: error("Tempo de transição inválido.")
        return SmartInsertPipeline.Options(insertionMs / 1000.0, seconds, selectedTransition, smartInsertEnabled,
            mainAudio?.let { selectedAudioTracks[trackKey(it)] } ?: 0,
            insertedAudio?.let { selectedAudioTracks[trackKey(it)] } ?: 0)
    }

    private fun trackKey(source: AudioSource): String = source.cachedFile?.absolutePath ?: source.uri.toString()

    private fun startInsert() {
        val main = mainAudio ?: return
        val inserted = insertedAudio ?: return
        listOf(main, inserted).firstOrNull {
            audioTrackCount(it.uri) > 1 && selectedAudioTracks[trackKey(it)] == null
        }?.let { source -> requestAudioTrack(source) { startInsert() }; return }
        val options = try { selectedOptions() } catch (error: IllegalStateException) {
            status.text = error.message; return
        }
        recovery.begin()
        stopFilteredPreview()
        pausePlayback()
        processingCancelled = false
        setProcessing(true)
        val startedAt = SystemClock.elapsedRealtime()
        val tracker = FfmpegTaskTracker(status, listOf("Preparando arquivos", "Inserindo áudio", "Validando arquivo para salvar"))
        Thread {
            val inputs = mutableListOf<File>()
            var keepRecoveryOutput=false
            var resultFile: File? = null
            try {
                val mainFile = cachedInput(main, "insert_main", inputs)
                val insertedFile = cachedInput(inserted, "insert_secondary", inputs)
                checkInsertCancellation()
                tracker.completeTask(0)
                val extension = main.name.substringAfterLast('.', "m4a").lowercase(Locale.ROOT)
                    .takeIf { it in SUPPORTED_COPY_EXTENSIONS } ?: "m4a"
                val output = recovery.file("output", ".$extension") { File(cacheDir,"insert_${UUID.randomUUID()}.$extension") }
                resultFile = output
                tracker.startTask(1)
                val pipeline = SmartInsertPipeline(
                    execute = { args, duration ->
                        checkInsertCancellation()
                        val session = executeWithProgress(args,(duration*1000).roundToLong(),tracker,1)
                        checkInsertCancellation()
                        if (ReturnCode.isCancel(session.returnCode)) throw ProcessingCancelled()
                        check(ReturnCode.isSuccess(session.returnCode)) { ffmpegFailureMessage(session) }
                    },
                    probe = { args -> probeInsert(args) },
                    cancelled = { checkInsertCancellation() },
                    log = { message -> Log.i(TAG,message); runOnUiThread { insertHint.text = message } },
                    packetProbe = { args -> probeInsertOutput(args) },
                    workDirectory = recovery.work("smart_insert") { File(output.parentFile,"smart_insert_${UUID.randomUUID()}") },
                    keepWork = recovery.job != null
                )
                val result = pipeline.run(mainFile,insertedFile,output,options)
                keepRecoveryOutput=true
                checkInsertCancellation()
                tracker.setTaskEncoder(1,result.audio.encoder)
                tracker.completeTask(1);tracker.completeTask(2)
                val elapsed = SystemClock.elapsedRealtime()-startedAt
                val mediaMs = (result.plan.total*1000.0/result.audio.rate).roundToLong()
                val mode = if (result.partial) "Smart Insert (cópia parcial)" else if (options.smart)
                    "Smart Insert (compatibilização contínua)" else "Inserção precisa"
                tracker.success("Tempo de processamento: ${formatTime(elapsed)}\n" +
                    "Mídia processada: ${formatTime(mediaMs)}\n" +
                    "Eficiência: ${String.format(Locale.US,"%.2fx",mediaMs/elapsed.coerceAtLeast(1L).toDouble())}\nModo: $mode")
                runOnUiThread {
                    setProcessing(false)
                    if (processingCancelled || isDestroyed) {
                        output.delete();tracker.fail("Operação cancelada.")
                    } else {
                        lastOutputFile?.takeIf { it != output }?.delete()
                        lastOutputFile = output;lastOutputUri = null;finalOutputDirUri = null
                        lastOutputName = "${sanitizeBase(main.name)}_com_audio.$extension"
                        outputName.text = lastOutputName;outputName.visibility = View.VISIBLE
                        outputActions.visibility = View.VISIBLE;saveButton.visibility = View.VISIBLE
                        openFolderButton.visibility = View.GONE;shareButton.visibility = View.GONE
                        outputStats.text = listOfNotNull(result.warning, "Estatísticas:\n${tracker.successMessageOrEmpty()}").joinToString("\n\n")
                        outputStats.setTextColor(if (result.warning == null) android.graphics.Color.parseColor("#FF2ECC71") else android.graphics.Color.parseColor("#FFFFC857"))
                        outputStats.visibility = View.VISIBLE
                        scroll.post { scroll.smoothScrollTo(0,outputActions.bottom) }
                    }
                }
            } catch (_: ProcessingCancelled) {
                recovery.delete(resultFile)
                runOnUiThread { setProcessing(false);tracker.fail("Operação cancelada.") }
            } catch (error: Throwable) {
                recovery.delete(resultFile)
                Log.e(TAG,"Audio insertion failed",error)
                runOnUiThread { setProcessing(false);tracker.fail(error.message ?: "Falha inesperada") }
            } finally { recovery.finish(keepRecoveryOutput,processingCancelled);inputs.forEach { recovery.delete(it) } }
        }.start()
    }

    private fun cachedInput(source: AudioSource, prefix: String, temporary: MutableList<File>): File =
        (if(recovery.job==null)source.cachedFile?.takeIf { it.isFile } else null) ?: copyUriToCache(source.cachedFile?.takeIf { it.isFile }?.let { Uri.fromFile(it) } ?: source.uri,source.name,prefix) { checkInsertCancellation() }.also { temporary += it;recovery.alias(source.uri,it) }

    private fun checkInsertCancellation() { if (processingCancelled) throw ProcessingCancelled() }

    private fun probeInsert(args: Array<String>, preview: Boolean = false): JSONObject = JSONObject(probeInsertOutput(args,preview))

    private fun probeInsertOutput(args: Array<String>, preview: Boolean = false): String {
        fun checkCancelled() {
            if (preview) { if (previewCancelled) throw ProcessingCancelled() } else checkInsertCancellation()
        }
        checkCancelled()
        val latch=CountDownLatch(1)
        val probeFile=File.createTempFile("insert_probe_",".txt",cacheDir)
        val redirected=args.toMutableList().apply { addAll(0,listOf("-o",probeFile.absolutePath)) }
        val session=FFprobeKit.executeWithArgumentsAsync(redirected.toTypedArray()) { latch.countDown() }
        if (preview) previewSessionId=session.sessionId else currentSessionId=session.sessionId
        try {
            if ((preview && previewCancelled) || (!preview && processingCancelled)) FFmpegKit.cancel(session.sessionId)
            latch.await();checkCancelled()
            check(ReturnCode.isSuccess(session.returnCode)) { "Não foi possível analisar a faixa de áudio selecionada." }
            return probeFile.readText()
        } finally {
            probeFile.delete()
            if(preview && previewSessionId==session.sessionId)previewSessionId=null
            if(!preview && currentSessionId==session.sessionId)currentSessionId=null
        }
    }

    private fun buildFullReencodeArguments(main: File, inserted: File, output: File, profile: AudioProfile, jobConfig: InsertAudioJobConfig): Array<String> {
        val rate=profile.sampleRate
        val encoder=encoderForProfile(output.extension,profile)
        val codec=when(encoder) { "libmp3lame" -> "mp3"; "libopus" -> "opus"; "libvorbis" -> "vorbis"; else -> encoder }
        val audio=SmartInsertPlanner.Audio(codec,rate,profile.channels,FfmpegMediaPolicies.channelLayout(profile.channels),
            SmartInsertPlanner.sampleCount((mainAudio?.durationMs ?: 1000L)/1000.0,rate),"",0,profile.bitrate,jobConfig.mainAudioTrack)
        val plan=SmartInsertPlanner.render(audio.samples,
            SmartInsertPlanner.sampleCount((insertedAudio?.durationMs ?: 1000L)/1000.0,rate),
            SmartInsertPlanner.sampleCount(jobConfig.insertionMs/1000.0,rate),rate,
            if(jobConfig.selectedTransition==TRANSITION_NONE)0.0 else jobConfig.transitionSeconds,
            jobConfig.selectedTransition,jobConfig.smartInsert)
        return SmartInsertPipeline({ _,_ -> }, { JSONObject() }).continuousArguments(main,inserted,output,audio,plan,
            jobConfig.mainAudioTrack,jobConfig.insertedAudioTrack)
    }

    private fun audioTrackCount(uri: Uri): Int {
        listOfNotNull(mainAudio,insertedAudio).firstOrNull { it.uri==uri }?.nativeAudio?.takeIf { it.isNotEmpty() }?.let { return it.size }
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
                selectedAudioTracks[trackKey(source)] = which
                stopFilteredPreview()
                source.nativeAudio.getOrNull(which)?.let { audio ->
                    if(mainAudio?.let { trackKey(it)==trackKey(source) }==true) { mainAudio=mainAudio?.copy(durationMs=(audio.duration*1000).roundToLong());insertionMs=insertionMs.coerceIn(0,mainAudio!!.durationMs) }
                    else insertedAudio=insertedAudio?.copy(durationMs=(audio.duration*1000).roundToLong())
                    configureTimeline();updateInsertHint()
                }
                dialog.dismiss()
                onSelected()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun audioTrackLabels(uri: Uri): List<String> {
        listOfNotNull(mainAudio,insertedAudio).firstOrNull { it.uri==uri }?.nativeAudio?.takeIf { it.isNotEmpty() }?.let { tracks ->
            return tracks.mapIndexed { index,audio -> "Faixa ${index+1} — ${audio.codec.uppercase(Locale.ROOT)} · ${audio.channels} canal(is) · ${audio.rate} Hz" }
        }
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
        recovery.reuse(args)?.let { return it }
        recovery.starting(args)
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
        if (processingCancelled) FFmpegKit.cancel(session.sessionId)
        latch.await()
        currentSessionId = null
        val completed = result.get() ?: session
        FfmpegCommandPresenter.completeLastShown(status, ReturnCode.isSuccess(completed.returnCode))
        recovery.completed(args, completed)
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
        listOfNotNull(mainAudio,insertedAudio).firstOrNull { it.uri==uri }?.nativeAudio?.getOrNull(audioTrack)?.let {
            return AudioProfile(it.rate,it.channels,it.bitrate,it.codec,it.codec.takeIf { c -> c in SmartInsertPlanner.pcmCodecs } ?: "pcm_s16le")
        }
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
        val mainTrack = main?.let { selectedAudioTracks[trackKey(it)] } ?: 0
        val insertedTrack = inserted?.let { selectedAudioTracks[trackKey(it)] } ?: 0
        val profile = main?.let { detectAudioProfile(it.uri, mainTrack) }
            ?: AudioProfile(48000, 2, "192k", "aac")
        val mainExtension = main?.name?.substringAfterLast('.', "m4a")
            ?.lowercase(Locale.ROOT)?.takeIf { it in SUPPORTED_COPY_EXTENSIONS } ?: "m4a"
        val jobConfig = InsertAudioJobConfig(
            insertionMs = parseTime(inputTime.text?.toString().orEmpty())
                ?.coerceIn(0L, main?.durationMs ?: Long.MAX_VALUE) ?: insertionMs,
            selectedTransition = selectedTransition,
            transitionSeconds = transitionTime.text?.toString().orEmpty().replace(',', '.')
                .toDoubleOrNull() ?: 0.5,
            smartInsert = smartInsertEnabled,
            mainAudioTrack = mainTrack,
            insertedAudioTrack = insertedTrack
        )
        if(selectedTransition!=TRANSITION_NONE && (!jobConfig.transitionSeconds.isFinite() || jobConfig.transitionSeconds<0)) { status.text="Tempo de transição inválido.";return }
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
        recovery.cancel()
        processingCancelled = true
        status.text = "Cancelando..."
        currentSessionId?.let { FFmpegKit.cancel(it) }
    }

    private fun saveOutputToUri(treeUri: Uri) {
        val source = lastOutputFile?.takeIf { it.exists() } ?: return
        val directory = DocumentFile.fromTreeUri(this, treeUri) ?: return
        val targetName = FfmpegMediaPolicies.uniqueOutputName(lastOutputName) { candidate ->
            directory.findFile(candidate) != null
        }
        val document = directory.createFile(audioMime(targetName), targetName) ?: return
        try {
            val destination=contentResolver.openOutputStream(document.uri) ?: error("Não foi possível abrir a saída para salvar.")
            destination.use { output -> source.inputStream().use { it.copyTo(output) } }
            finalOutputDirUri = treeUri
            lastOutputUri = document.uri
            outputName.text = document.name ?: lastOutputName
            recovery.saved()
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

    private fun copyUriToCache(uri: Uri, displayName: String, prefix: String, checkCancelled: () -> Unit = {}): File {
        recovery.input(uri,displayName) { target ->
            contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output ->
                val buffer=ByteArray(1 shl 20)
                while(true) { checkInsertCancellation();val count=input.read(buffer);if(count<0)break;output.write(buffer,0,count) }
            } } ?: error("Não consegui abrir $displayName")
        }?.let { return it }
        val extension=displayName.substringAfterLast('.',"audio").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "audio"
        val file=File(cacheDir,"${prefix}_${UUID.randomUUID()}.$extension")
        try {
            contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(file).use { output ->
                val buffer=ByteArray(1 shl 20)
                while(true) {
                    checkCancelled()
                    val count=input.read(buffer)
                    if(count<0)break
                    output.write(buffer,0,count)
                }
            } } ?: error("Não consegui abrir $displayName")
            return file
        } catch(error: Throwable) { file.delete();throw error }
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
        startAfterSeek=false
        previewSeeker.cancelPending()
        pausePlayersOnly()
        handler.removeCallbacks(playbackTicker)
        updatePlayButton(false)
    }

    private fun pausePlayersOnly() { try { filteredPreviewPlayer?.pause() } catch(_: Throwable) { } }

    private fun isPlaying(): Boolean = try { filteredPreviewReady && filteredPreviewPlayer?.isPlaying==true } catch(_: Throwable) { false }

    private fun updatePlayButton(playing: Boolean) {
        playPause.setImageResource(if (playing) R.drawable.ic_ffmpeg_pause else R.drawable.ic_ffmpeg_play)
        playPause.contentDescription = if (playing) "Pausar" else "Reproduzir"
    }

    private fun seekPlayer(player: MediaPlayer?, positionMs: Long) {
        if (player === filteredPreviewPlayer) previewSeeker.request(positionMs)
    }

    private fun releasePlayers() { stopFilteredPreview() }

    override fun onPause() {
        if(previewRendering)stopFilteredPreview() else pausePlayback()
        super.onPause()
    }

    override fun onDestroy() {
        selectionGeneration++
        selectionSessionId?.let { FFmpegKit.cancel(it) }
        if(isProcessing)cancelProcessing()
        releasePlayers()
        mainAudio?.cachedFile?.delete();insertedAudio?.cachedFile?.delete()
        previewSource.close()
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
            if (!seconds.isFinite() || seconds < 0 || seconds > Long.MAX_VALUE / 1000.0) return null
            (seconds * 1000.0).roundToLong()
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

    private data class AudioSource(val uri: Uri, val name: String, val durationMs: Long,
        val cachedFile: File? = null, val nativeAudio: List<SmartInsertPlanner.Audio> = emptyList())
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
        val smartInsert: Boolean = false,
        val mainAudioTrack: Int = 0,
        val insertedAudioTrack: Int = 0
    )
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
        private val SUPPORTED_COPY_EXTENSIONS = setOf("m4a", "aac", "mp3", "wav", "flac", "ogg", "opus", "wma")
    }
}
