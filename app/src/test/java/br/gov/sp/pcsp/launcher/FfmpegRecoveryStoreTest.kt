package br.gov.sp.pcsp.launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class FfmpegRecoveryStoreTest {
    @Test fun journalInterruptedBetweenRenamesRecoversTheBackup() {
        val root=Files.createTempDirectory("sig-recovery").toFile()
        try {
            val job=FfmpegRecoveryStore.create(root,"cut",JSONObject().put("seconds",3))
            assertTrue(File(job.directory,"job.json").renameTo(File(job.directory,"job.backup")))
            val resumed=FfmpegRecoveryStore.load(job.directory)!!
            assertEquals(3,resumed.request.getInt("seconds"));resumed.finish("failed")
            assertTrue(File(job.directory,"job.json").isFile)
        } finally { root.deleteRecursively() }
    }
    @Test fun restartOnlyReusesCompletedIntactSteps() {
        val root=Files.createTempDirectory("sig-recovery").toFile()
        try {
            val job=FfmpegRecoveryStore.create(root,"cut",JSONObject().put("seconds",3))
            val input=job.input("content://fixture",".mp4") { it.writeText("source") }
            val output=job.file("body",".ts")
            val command=arrayOf("-i",input.absolutePath,output.absolutePath)
            job.begin(command);output.writeText("partial")
            assertFalse(FfmpegRecoveryStore.load(job.directory)!!.reusable(command))
            output.writeText("complete");job.complete(command)
            val resumed=FfmpegRecoveryStore.load(job.directory)!!
            assertTrue(resumed.reusable(command));assertEquals(3,resumed.request.getInt("seconds"))
            assertEquals(input,resumed.input(input.toURI().toString(),".mp4") { fail("Must not copy again") })
            output.writeText("modified");assertFalse(resumed.reusable(command))
        } finally { root.deleteRecursively() }
    }
    @Test fun partialInputsAndChangedInputsAreNeverTrusted() {
        val root=Files.createTempDirectory("sig-recovery").toFile()
        try {
            val job=FfmpegRecoveryStore.create(root,"insert",JSONObject())
            val input=job.input("content://audio",".m4a") { it.writeText("original") }
            input.writeText("changed")
            assertThrows(IllegalStateException::class.java) { job.input("content://audio",".m4a") { fail() } }
            assertTrue(FfmpegRecoveryStore.protected(job.directory))
            job.finish("failed");assertNotNull(FfmpegRecoveryStore.pending(root,"insert"))
            job.finish("ready");assertNotNull(FfmpegRecoveryStore.pending(root,"insert"))
            job.finish("saved");assertNull(FfmpegRecoveryStore.pending(root,"insert"));assertFalse(FfmpegRecoveryStore.protected(job.directory))
        } finally { root.deleteRecursively() }
    }
    @Test fun parallelSegmentOutputsAreReusedOnlyAsACompleteSet() {
        val root=Files.createTempDirectory("sig-recovery").toFile()
        try {
            val job=FfmpegRecoveryStore.create(root,"rotate",JSONObject())
            val directory=job.file("parts","").apply { mkdirs() }
            val pattern=File(directory,"part_%05d.mkv")
            val command=arrayOf("-f","segment",pattern.absolutePath)
            job.begin(command)
            val first=File(directory,"part_00000.mkv").apply { writeText("one") }
            val second=File(directory,"part_00001.mkv").apply { writeText("two") }
            assertFalse(job.reusable(command));job.complete(command);assertTrue(job.reusable(command))
            first.delete();assertFalse(job.reusable(command));assertTrue(second.exists())
        } finally { root.deleteRecursively() }
    }
}
