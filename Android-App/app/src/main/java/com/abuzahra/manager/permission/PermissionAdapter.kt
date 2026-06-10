package com.abuzahra.manager.permission

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.abuzahra.manager.R

/**
 * PermissionAdapter - RecyclerView adapter for permission items.
 * Renders each permission as a card with icon, title, description, status, and toggle.
 */
class PermissionAdapter(
    private var items: List<PermissionItem>,
    private val onPermissionClick: (PermissionItem) -> Unit
) : RecyclerView.Adapter<PermissionAdapter.PermissionViewHolder>() {

    private val iconMap = mapOf(
        "camera" to intArrayOf(0xE3AF, 0xE412),        // videocam / photo_camera
        "microphone" to intArrayOf(0xE02A),              // mic
        "location" to intArrayOf(0xE0C8),                // location_on
        "notifications" to intArrayOf(0xE7F5),           // notifications
        "contacts" to intArrayOf(0xE0D0),                // contacts
        "storage" to intArrayOf(0xE1C2),                 // folder
        "notification_access" to intArrayOf(0xE7F4),     // notification_important
        "accessibility" to intArrayOf(0xE84F),           // accessibility_new
        "overlay" to intArrayOf(0xE8E2),                 // open_in_new / layers
        "battery" to intArrayOf(0xE1A5),                 // battery_charging_full
        "usage_stats" to intArrayOf(0xE4C9),             // analytics / bar_chart
        "device_admin" to intArrayOf(0xE8A8),            // admin_panel_settings
        "screen_capture" to intArrayOf(0xE3AB),          // screen_share
        "install" to intArrayOf(0xE2C5),                 // get_app / install_mobile
        "settings" to intArrayOf(0xE8B8)                 // settings
    )

    fun updateItems(newItems: List<PermissionItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun updateSingleItem(itemId: String, isGranted: Boolean) {
        val index = items.indexOfFirst { it.id == itemId }
        if (index >= 0) {
            items[index].isGranted = isGranted
            notifyItemChanged(index)
        }
    }

    fun getGrantedEssentialCount(): Int {
        return items.count { it.isGranted && it.isEssential }
    }

    fun getTotalEssentialCount(): Int {
        return items.count { it.isEssential }
    }

    fun areAllEssentialGranted(): Boolean {
        return items.filter { it.isEssential }.all { it.isGranted }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PermissionViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            R.layout.permission_card_item, parent, false
        )
        return PermissionViewHolder(view)
    }

    override fun onBindViewHolder(holder: PermissionViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = items.size

    inner class PermissionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardRoot: LinearLayout = itemView.findViewById(R.id.permCardRoot)
        private val iconContainer: LinearLayout = itemView.findViewById(R.id.permIconContainer)
        private val iconView: TextView = itemView.findViewById(R.id.permIcon)
        private val textTitle: TextView = itemView.findViewById(R.id.permTitle)
        private val textDesc: TextView = itemView.findViewById(R.id.permDesc)
        private val textStatus: TextView = itemView.findViewById(R.id.permStatus)
        private val btnAction: LinearLayout = itemView.findViewById(R.id.permBtnAction)

        fun bind(item: PermissionItem) {
            // Set icon
            iconView.text = getIconUnicode(item.iconRes)
            try {
                val colorInt = Color.parseColor(item.iconColor)
                val drawable = GradientDrawable()
                drawable.cornerRadius = 48f
                drawable.setColor(colorInt)
                iconContainer.background = drawable
            } catch (_: Exception) {}

            // Set texts
            textTitle.text = item.title
            textDesc.text = item.description

            // Update status based on granted state
            if (item.isGranted) {
                textStatus.text = "مفعّل"
                textStatus.setTextColor(Color.parseColor("#4ADE80"))
                btnAction.visibility = View.GONE
            } else {
                textStatus.text = if (item.isEssential) "مطلوب" else "اختياري"
                textStatus.setTextColor(Color.parseColor("#FF6B6B"))
                btnAction.visibility = View.VISIBLE
                btnAction.setOnClickListener {
                    onPermissionClick(item)
                }
            }

            // Sub-items (e.g. background location)
            val subItemsContainer = itemView.findViewById<LinearLayout>(R.id.permSubItems)
            subItemsContainer?.removeAllViews()
            item.subItems.forEach { sub ->
                val subView = TextView(itemView.context).apply {
                    text = if (sub.isGranted) "  ${sub.title}" else "  ${sub.title}"
                    textSize = 11f
                    setTextColor(
                        if (sub.isGranted) Color.parseColor("#4ADE80")
                        else Color.parseColor("#FF6B6B")
                    )
                    setPadding(0, 4, 0, 0)
                }
                subItemsContainer.addView(subView)
            }
        }
    }

    private fun getIconUnicode(iconRes: String): String {
        // Use emoji/unicode icons that work as text
        return when (iconRes) {
            "camera" -> "\uD83D\uDCF7"
            "microphone" -> "\uD83C\uDFA4"
            "location" -> "\uD83D\uDCCD"
            "notifications" -> "\uD83D\uDD14"
            "contacts" -> "\uD83D\uDC65"
            "storage" -> "\uD83D\uDCC1"
            "notification_access" -> "\uD83D\uDD0E"
            "accessibility" -> "\u267F"
            "overlay" -> "\uD83D\uDDA5\uFE0F"
            "battery" -> "\uD83D\uDD0B"
            "usage_stats" -> "\uD83D\uDCCA"
            "device_admin" -> "\uD83D\uDEE1\uFE0F"
            "screen_capture" -> "\uD83D\uDCFA"
            "install" -> "\uD83D\uDCE5"
            "settings" -> "\u2699\uFE0F"
            else -> "\u2753"
        }
    }
}