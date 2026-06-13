package com.abuzahra.admin.ui.files

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.abuzahra.admin.R
import com.abuzahra.admin.data.model.RemoteFile
import com.abuzahra.admin.databinding.ItemFileBinding

class FileListAdapter(
    private val onFileClick: (RemoteFile) -> Unit,
    private val onDownloadClick: (RemoteFile) -> Unit,
    private val onParentClick: () -> Unit
) : ListAdapter<RemoteFile, FileListAdapter.FileViewHolder>(FileDiffCallback()) {

    private var showParent = false

    fun setShowParent(show: Boolean) {
        val oldShow = showParent
        showParent = show
        if (oldShow != show) {
            notifyItemInserted(0)
        }
    }

    override fun getItemCount(): Int {
        return super.getItemCount() + if (showParent) 1 else 0
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == 0 && showParent) TYPE_PARENT else TYPE_FILE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val binding = ItemFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FileViewHolder(binding, viewType)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        if (position == 0 && showParent) {
            holder.bindParent()
        } else {
            val filePos = if (showParent) position - 1 else position
            holder.bind(getItem(filePos))
        }
    }

    inner class FileViewHolder(
        private val binding: ItemFileBinding,
        private val viewType: Int
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == 0 && showParent) {
                    onParentClick()
                } else if (viewType == TYPE_FILE) {
                    val filePos = if (showParent) pos - 1 : pos
                    if (filePos >= 0 && filePos < currentList.size) {
                        val file = getItem(filePos)
                        if (file.isDirectory) {
                            onFileClick(file)
                        }
                    }
                }
            }

            binding.ivDownload.setOnClickListener {
                val pos = bindingAdapterPosition
                if (viewType == TYPE_FILE && pos != RecyclerView.NO_POSITION) {
                    val filePos = if (showParent) pos - 1 : pos
                    if (filePos >= 0 && filePos < currentList.size) {
                        onDownloadClick(getItem(filePos))
                    }
                }
            }
        }

        fun bind(file: RemoteFile) {
            binding.tvFileName.text = file.name

            val icon = if (file.isDirectory) {
                R.drawable.ic_folder
            } else {
                getFileIcon(file.extension)
            }
            binding.ivFileIcon.setImageResource(icon)

            binding.tvFileDetails.text = if (file.isDirectory) {
                "مجلد"
            } else {
                file.displaySize
            }

            binding.ivDownload.visibility = if (file.isDirectory) {
                android.view.View.GONE
            } else {
                android.view.View.VISIBLE
            }
        }

        fun bindParent() {
            binding.tvFileName.text = ".."
            binding.ivFileIcon.setImageResource(R.drawable.ic_folder)
            binding.tvFileDetails.text = "المجلد السابق"
            binding.ivDownload.visibility = android.view.View.GONE
        }

        private fun getFileIcon(extension: String): Int {
            return when (extension.lowercase()) {
                "jpg", "jpeg", "png", "gif", "bmp", "webp" -> R.drawable.ic_screenshot
                "mp4", "avi", "mkv", "mov", "3gp" -> R.drawable.ic_screenshot
                "mp3", "wav", "ogg", "flac", "aac" -> R.drawable.ic_file
                "pdf" -> R.drawable.ic_file
                "doc", "docx", "xls", "xlsx", "ppt", "pptx" -> R.drawable.ic_file
                "txt", "log", "csv", "json", "xml" -> R.drawable.ic_file
                "apk" -> R.drawable.ic_phone
                "zip", "rar", "7z", "tar", "gz" -> R.drawable.ic_file
                else -> R.drawable.ic_file
            }
        }
    }

    class FileDiffCallback : DiffUtil.ItemCallback<RemoteFile>() {
        override fun areItemsTheSame(oldItem: RemoteFile, newItem: RemoteFile): Boolean =
            oldItem.path == newItem.path

        override fun areContentsTheSame(oldItem: RemoteFile, newItem: RemoteFile): Boolean =
            oldItem == newItem
    }

    companion object {
        const val TYPE_PARENT = 0
        const val TYPE_FILE = 1
    }
}