package com.abuzahra.admin.ui.logs

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.abuzahra.admin.R
import com.abuzahra.admin.data.model.Event
import com.abuzahra.admin.databinding.ItemEventBinding

class LogAdapter(
    private val onItemClick: ((Event) -> Unit)? = null
) : ListAdapter<Event, LogAdapter.LogViewHolder>(LogDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemEventBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class LogViewHolder(
        private val binding: ItemEventBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemClick?.invoke(getItem(pos))
                }
            }
        }

        fun bind(event: Event) {
            binding.tvCommand.text = if (event.deviceName.isNotEmpty()) {
                "${event.displayEvent} — ${event.deviceName}"
            } else {
                event.displayEvent
            }

            binding.tvTimestamp.text = event.relativeTime

            // Status indicator color based on event type
            val colorRes = when {
                event.event.contains("online", ignoreCase = true) -> R.color.online_color
                event.event.contains("offline", ignoreCase = true) -> R.color.offline_color
                event.event.contains("error", ignoreCase = true) ||
                    event.event.contains("fail", ignoreCase = true) -> R.color.error
                event.event.contains("battery_low", ignoreCase = true) -> R.color.warning
                else -> R.color.info
            }
            binding.statusIndicator.setBackgroundColor(
                ContextCompat.getColor(binding.root.context, colorRes)
            )

            // Chip shows event type category
            binding.chipStatus.text = event.eventTypeCategory

            val chipBgColor = when (event.eventTypeCategory) {
                "اتصال" -> ContextCompat.getColor(binding.root.context, R.color.info)
                "أوامر" -> ContextCompat.getColor(binding.root.context, R.color.primary)
                else -> ContextCompat.getColor(binding.root.context, R.color.warning)
            }
            binding.chipStatus.chipBackgroundColor =
                android.content.res.ColorStateList.valueOf(chipBgColor)
            binding.chipStatus.setTextColor(
                ContextCompat.getColor(binding.root.context, R.color.on_primary)
            )
        }
    }

    class LogDiffCallback : DiffUtil.ItemCallback<Event>() {
        override fun areItemsTheSame(oldItem: Event, newItem: Event): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Event, newItem: Event): Boolean =
            oldItem == newItem
    }
}