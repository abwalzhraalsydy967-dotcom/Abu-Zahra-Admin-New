package com.abuzahra.admin.ui.device

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.abuzahra.admin.R
import com.abuzahra.admin.data.model.Command
import com.abuzahra.admin.databinding.ItemEventBinding

class EventAdapter(
    private val onItemClick: ((Command) -> Unit)? = null
) : ListAdapter<Command, EventAdapter.EventViewHolder>(EventDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val binding = ItemEventBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return EventViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class EventViewHolder(
        private val binding: ItemEventBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && onItemClick != null) {
                    onItemClick(getItem(pos))
                }
            }
        }

        fun bind(command: Command) {
            binding.tvCommand.text = command.command
            binding.tvTimestamp.text = command.displayTime

            // Status indicator color
            val colorRes = when (command.statusColor) {
                0 -> R.color.success  // success
                1 -> R.color.error    // failed
                else -> R.color.pending_color  // pending
            }
            binding.statusIndicator.setBackgroundColor(
                ContextCompat.getColor(binding.root.context, colorRes)
            )

            // Status chip
            binding.chipStatus.text = command.displayStatus
            val chipColor = when (command.statusColor) {
                0 -> com.google.android.material.chip.ChipBackgroundColor(
                    ContextCompat.getColor(binding.root.context, R.color.success)
                )
                1 -> com.google.android.material.chip.ChipBackgroundColor(
                    ContextCompat.getColor(binding.root.context, R.color.error)
                )
                else -> com.google.android.material.chip.ChipBackgroundColor(
                    ContextCompat.getColor(binding.root.context, R.color.pending_color)
                )
            }
            binding.chipStatus.chipBackgroundColor = chipColor

            // Set text color for the chip
            val textColor = if (command.statusColor == 2) {
                ContextCompat.getColor(binding.root.context, R.color.text_primary)
            } else {
                ContextCompat.getColor(binding.root.context, R.color.on_primary)
            }
            binding.chipStatus.setTextColor(textColor)
        }
    }

    class EventDiffCallback : DiffUtil.ItemCallback<Command>() {
        override fun areItemsTheSame(oldItem: Command, newItem: Command): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Command, newItem: Command): Boolean =
            oldItem == newItem
    }
}