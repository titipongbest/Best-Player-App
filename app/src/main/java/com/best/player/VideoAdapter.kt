package com.best.player

import android.graphics.Bitmap
import android.text.format.Formatter
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class VideoAdapter(
    private val scope: CoroutineScope,
    private val onClick: (VideoItem) -> Unit,
) : RecyclerView.Adapter<VideoAdapter.Holder>() {

    private var items: List<VideoItem> = emptyList()
    private val thumbCache = LruCache<Long, Bitmap>(120)

    fun submit(list: List<VideoItem>) {
        items = list
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        holder.title.text = item.name
        holder.duration.text = TimeFormat.format(item.durationMs)
        val res = if (item.width > 0 && item.height > 0) "${item.width}×${item.height} · " else ""
        holder.meta.text = res + Formatter.formatShortFileSize(ctx, item.sizeBytes)
        holder.itemView.setOnClickListener { onClick(item) }

        holder.thumbJob?.cancel()
        val cached = thumbCache.get(item.id)
        holder.thumb.setImageBitmap(cached)
        if (cached == null) {
            holder.thumbJob = scope.launch {
                val bmp = VideoRepository.loadThumbnail(ctx, item) ?: return@launch
                thumbCache.put(item.id, bmp)
                if (holder.bindingAdapterPosition == position) holder.thumb.setImageBitmap(bmp)
            }
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val duration: TextView = view.findViewById(R.id.duration)
        val title: TextView = view.findViewById(R.id.title)
        val meta: TextView = view.findViewById(R.id.meta)
        var thumbJob: Job? = null
    }
}
