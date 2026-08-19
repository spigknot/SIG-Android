package br.gov.sp.pcsp.launcher

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

class FfmpegActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_ffmpeg)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.button_cut_media).setOnClickListener {
            startActivity(Intent(this, FfmpegCutActivity::class.java))
        }
        findViewById<View>(R.id.button_extract_audio).setOnClickListener {
            startActivity(Intent(this, FfmpegExtractAudioActivity::class.java))
        }
        findViewById<View>(R.id.button_rotate_video).setOnClickListener {
            startActivity(Intent(this, FfmpegRotateVideoActivity::class.java))
        }
        findViewById<View>(R.id.button_join_videos).setOnClickListener {
            startActivity(Intent(this, FfmpegJoinVideosActivity::class.java))
        }
        findViewById<View>(R.id.button_clean_audio).setOnClickListener {
            startActivity(Intent(this, FfmpegCleanAudioActivity::class.java))
        }
        findViewById<View>(R.id.button_insert_audio).setOnClickListener {
            startActivity(Intent(this, FfmpegInsertAudioActivity::class.java))
        }
    }
}
