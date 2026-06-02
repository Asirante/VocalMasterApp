package com.app.vocalmaster

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SongListAdapter(
    private val onClick: (VocalSong) -> Unit
) : RecyclerView.Adapter<SongListAdapter.VH>() {

    private val items = mutableListOf<VocalSong>()

    fun submit(list: List<VocalSong>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        holder.title.text = s.meta.displayName
        holder.sub.text = buildString {
            append(formatDuration(s.durationMs))
            append("  ·  평균키 ").append(formatKey(s.avgKeyHz))
            append("  ·  부른 횟수 ").append(s.stat.playCount)
            append("  ·  최고 ").append(s.stat.bestScore)
        }
        holder.star.visibility = if (s.stat.favorite) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onClick(s) }
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvItemTitle)
        val sub: TextView = v.findViewById(R.id.tvItemSub)
        val star: View = v.findViewById(R.id.ivStar)
    }

    companion object {
        fun formatDuration(ms: Long?): String {
            if (ms == null) return "--:--"
            val totalSec = ms / 1000
            return "%d:%02d".format(totalSec / 60, totalSec % 60)
        }
        fun formatKey(hz: Float?): String =
            if (hz == null) "-" else "%.0fHz".format(hz)
    }
}
