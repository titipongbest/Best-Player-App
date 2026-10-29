package com.best.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: VideoAdapter
    private lateinit var empty: View
    private lateinit var emptyText: TextView
    private lateinit var btnGrant: Button

    private val videoPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) play(uri, null)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        empty = findViewById(R.id.empty)
        emptyText = findViewById(R.id.empty_text)
        btnGrant = findViewById(R.id.btn_grant)
        btnGrant.setOnClickListener { requestPermission.launch(videoPermission) }
        findViewById<TextView>(R.id.credit).text = AppInfo.credit(this)
        findViewById<View>(R.id.btn_open).setOnClickListener { openDocument.launch(arrayOf("video/*")) }

        adapter = VideoAdapter(lifecycleScope) { play(it.uri, it.name) }
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        if (!hasPermission()) requestPermission.launch(videoPermission)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, videoPermission) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        if (!hasPermission()) {
            adapter.submit(emptyList())
            empty.visibility = View.VISIBLE
            emptyText.setText(R.string.need_permission)
            btnGrant.visibility = View.VISIBLE
            return
        }
        lifecycleScope.launch {
            val videos = VideoRepository.loadVideos(this@MainActivity)
            adapter.submit(videos)
            empty.visibility = if (videos.isEmpty()) View.VISIBLE else View.GONE
            emptyText.setText(R.string.no_videos)
            btnGrant.visibility = View.GONE
        }
    }

    private fun play(uri: Uri, title: String?) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .setData(uri)
                .putExtra(PlayerActivity.EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}
