package com.abuzahra.admin.ui.dashboard

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.abuzahra.admin.R
import com.abuzahra.admin.data.model.Device
import com.abuzahra.admin.databinding.ItemDeviceBinding

class DeviceAdapter(
    private val onItemClick: (Device) -> Unit
) : ListAdapter<Device, DeviceAdapter.DeviceViewHolder>(DeviceDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DeviceViewHolder(
        private val binding: ItemDeviceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.card.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }
        }

        fun bind(device: Device) {
            binding.tvDeviceName.text = device.name.ifEmpty { device.model }
            binding.tvDeviceModel.text = device.model
            binding.tvBattery.text = device.displayBattery
            binding.tvLastSeen.text = device.displayLastSeen

            // Status dot color
            val statusColor = if (device.isOnline) {
                ContextCompat.getColor(binding.root.context, R.color.online_color)
            } else {
                ContextCompat.getColor(binding.root.context, R.color.offline_color)
            }
            binding.statusDot.setBackgroundColor(statusColor)

            // Battery icon tint
            val batteryColorRes = when {
                device.batteryLevel > 50 -> R.color.battery_high
                device.batteryLevel > 20 -> R.color.battery_medium
                else -> R.color.battery_low
            }
            binding.ivBatteryIcon.imageTintList =
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(binding.root.context, batteryColorRes)
                )
        }
    }

    class DeviceDiffCallback : DiffUtil.ItemCallback<Device>() {
        override fun areItemsTheSame(oldItem: Device, newItem: Device): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Device, newItem: Device): Boolean {
            return oldItem == newItem
        }
    }
}