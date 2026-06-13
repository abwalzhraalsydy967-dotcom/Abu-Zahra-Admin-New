package com.abuzahra.admin.ui.device

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.abuzahra.admin.R
import com.abuzahra.admin.data.model.Command
import com.abuzahra.admin.data.model.CommandDefinitions
import com.abuzahra.admin.databinding.ItemCommandBinding

class CommandAdapter(
    private val onCommandClick: (CommandDefinitions.CommandDef) -> Unit
) : ListAdapter<CommandDefinitions.CommandDef, CommandAdapter.CommandViewHolder>(CommandDefDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommandViewHolder {
        val binding = ItemCommandBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CommandViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CommandViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CommandViewHolder(
        private val binding: ItemCommandBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.layoutCommand.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onCommandClick(getItem(position))
                }
            }
        }

        fun bind(commandDef: CommandDefinitions.CommandDef) {
            binding.tvCommandName.text = commandDef.name
        }
    }

    class CommandDefDiffCallback : DiffUtil.ItemCallback<CommandDefinitions.CommandDef>() {
        override fun areItemsTheSame(
            oldItem: CommandDefinitions.CommandDef,
            newItem: CommandDefinitions.CommandDef
        ): Boolean = oldItem.key == newItem.key

        override fun areContentsTheSame(
            oldItem: CommandDefinitions.CommandDef,
            newItem: CommandDefinitions.CommandDef
        ): Boolean = oldItem == newItem
    }
}