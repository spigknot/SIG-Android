package br.gov.sp.pcsp.launcher

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.widget.TextView

class FfmpegTaskTracker(
    private val textView: TextView,
    initialTasks: List<String>
) {
    private val tasks = mutableListOf<String>()
    private val taskProgresses = mutableListOf<Int>()
    private val taskDetails = mutableListOf<String>()
    private val taskStates = mutableListOf<TaskState>()
    private var isFailed = false
    private var failureMessage = ""
    private var isSuccess = false
    private var successMessage = ""
    private var liveStatus = ""

    private var currentLinearIndex = 0

    enum class TaskState { PENDING, RUNNING, COMPLETED, FAILED }

    private val colorCompleted = Color.parseColor("#FF2ECC71") // Verde
    private val colorActive = Color.parseColor("#FFFFFFFF")    // Branco
    private val colorPending = Color.parseColor("#88FFFFFF")   // Cinza
    private val colorError = Color.parseColor("#FFFF4A4A")     // Vermelho

    init {
        // Alinha à esquerda já que será uma lista de passos
        textView.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        appendTasks(initialTasks)
    }

    fun appendTasks(newTasks: List<String>) {
        tasks.addAll(newTasks)
        for (i in newTasks.indices) {
            taskProgresses.add(0)
            taskDetails.add("")
            taskStates.add(TaskState.PENDING)
        }
        render()
    }

    // Para uso sequencial legado:
    fun setProgress(progress: Int) {
        setProgress(progress, "")
    }

    fun setProgress(progress: Int, detail: String) {
        if (isFailed || currentLinearIndex >= tasks.size) return
        setTaskProgress(currentLinearIndex, progress, detail)
    }

    fun completeCurrentTask() {
        if (isFailed || currentLinearIndex >= tasks.size) return
        completeTask(currentLinearIndex)
        currentLinearIndex++
    }

    // Uso concorrente e específico:
    fun setTaskProgress(index: Int, progress: Int) {
        setTaskProgress(index, progress, "")
    }

    fun setTaskProgress(index: Int, progress: Int, detail: String) {
        if (isFailed || index >= tasks.size) return
        // FFmpeg pode entregar uma estatistica atrasada apos o callback de conclusao.
        // Uma etapa concluida nao deve voltar visualmente para 99%.
        if (taskStates[index] == TaskState.COMPLETED) return
        taskStates[index] = TaskState.RUNNING
        taskProgresses[index] = progress.coerceIn(0, 100)
        taskDetails[index] = detail
        render()
    }

    fun completeTask(index: Int) {
        if (isFailed || index >= tasks.size) return
        taskStates[index] = TaskState.COMPLETED
        taskProgresses[index] = 100
        render()
    }

    fun finishAll() {
        if (isFailed) return
        for (i in tasks.indices) {
            taskStates[i] = TaskState.COMPLETED
            taskProgresses[i] = 100
        }
        currentLinearIndex = tasks.size
        render()
    }

    fun fail(message: String) {
        isFailed = true
        failureMessage = message
        render()
    }

    fun success(message: String) {
        if (isFailed) return
        finishAll()
        isSuccess = true
        successMessage = message
        render()
    }

    /** Informacao transitória, exibida abaixo das etapas sem se tornar uma tarefa. */
    fun setLiveStatus(message: String) {
        if (isFailed) return
        liveStatus = message
        render()
    }

    fun clearLiveStatus() {
        if (liveStatus.isBlank()) return
        liveStatus = ""
        render()
    }

    fun reset() {
        currentLinearIndex = 0
        isFailed = false
        failureMessage = ""
        isSuccess = false
        successMessage = ""
        liveStatus = ""
        for (i in tasks.indices) {
            taskStates[i] = TaskState.PENDING
            taskProgresses[i] = 0
            taskDetails[i] = ""
        }
        render()
    }

    private fun render() {
        textView.post {
            val builder = SpannableStringBuilder()
            
            for (i in tasks.indices) {
                val taskName = tasks[i]
                val state = taskStates[i]
                val progress = taskProgresses[i]
                val detail = taskDetails[i].takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                val lineStart = builder.length
                
                when (state) {
                    TaskState.COMPLETED -> {
                        builder.append("$taskName 100%\n")
                        builder.setSpan(ForegroundColorSpan(colorCompleted), lineStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    TaskState.FAILED -> {
                        builder.append("$taskName: FALHOU\n")
                        builder.setSpan(ForegroundColorSpan(colorError), lineStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    TaskState.RUNNING -> {
                        builder.append("$taskName - $progress%$detail\n")
                        builder.setSpan(ForegroundColorSpan(colorActive), lineStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    TaskState.PENDING -> {
                        builder.append("$taskName\n")
                        builder.setSpan(ForegroundColorSpan(colorPending), lineStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }

            if (liveStatus.isNotBlank()) {
                val lineStart = builder.length
                builder.append("$liveStatus\n")
                builder.setSpan(ForegroundColorSpan(colorActive), lineStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            if (isFailed && failureMessage.isNotBlank()) {
                val lineStart = builder.length
                builder.append("\nErro: $failureMessage\n")
                builder.setSpan(ForegroundColorSpan(colorError), lineStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (isSuccess && successMessage.isNotBlank()) {
                val lineStart = builder.length
                builder.append("\n\nEstatísticas:\n$successMessage\n")
                builder.setSpan(ForegroundColorSpan(colorCompleted), lineStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            textView.text = builder.trimEnd()
        }
    }
}
