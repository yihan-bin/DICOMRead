package com.example.dicomread

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.example.dicomread.databinding.ItemSeriesThumbnailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SeriesThumbnailAdapter(
    private val seriesList: List<DicomSeries>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<SeriesThumbnailAdapter.ThumbnailViewHolder>() {

    private var selectedPosition = 0

    fun setSelected(position: Int) {
        if (position == selectedPosition) return
        val previousPosition = selectedPosition
        selectedPosition = position
        notifyItemChanged(previousPosition)
        notifyItemChanged(selectedPosition)
    }

    inner class ThumbnailViewHolder(val binding: ItemSeriesThumbnailBinding) : RecyclerView.ViewHolder(binding.root) {
        private var loaderJob: Job? = null
        init {
            itemView.setOnClickListener {
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    onClick(bindingAdapterPosition)
                }
            }
        }

        fun bind(series: DicomSeries, isSelected: Boolean) {
            binding.tvSeriesDescription.text = series.seriesDescription
            // binding.tvModality.text = series.modality // 这行代码在你的布局里被注释掉了

            val selectionColor = if (isSelected) R.color.thumbnail_selected_bg else android.R.color.transparent
            // 你的布局中没有 card，而是整个 itemView。如果是 card，请用 binding.card.setCardBackgroundColor
            itemView.setBackgroundColor(ContextCompat.getColor(itemView.context, selectionColor))


            loaderJob?.cancel()
            val firstImageUriString = series.fileUris.firstOrNull()
            if (firstImageUriString != null) {
                binding.ivThumbnail.setImageResource(R.drawable.ic_placeholder) // 先设置占位图

                loaderJob = itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                    val tempFile = copyUriToCache(Uri.parse(firstImageUriString))
                    if (tempFile != null) {
                        try {
                            // --- 开始修正 ---
                            // 1. 安全地调用 getDicomImageSize
                            val size: IntArray? = DicomModule.getDicomImageSize(tempFile.absolutePath)

                            // 2. 使用 let 进行空安全检查
                            size?.let {
                                if (it[0] > 0 && it[1] > 0) {
                                    val thumbBitmap = Bitmap.createBitmap(it[0], it[1], Bitmap.Config.ARGB_8888)
                                    // 3. 使用正确的函数签名进行调用，并检查返回的 int 状态码
                                    val result = DicomModule.loadDicomImage(tempFile.absolutePath, thumbBitmap)
                                    if (result == 0) { // 0 代表成功
                                        withContext(Dispatchers.Main) {
                                            binding.ivThumbnail.setImageBitmap(thumbBitmap)
                                        }
                                    }
                                }
                            }
                            // --- 结束修正 ---
                        } catch (e: Exception) {
                            // Handle error, e.g., log it
                            Log.e("ThumbnailAdapter", "Error loading thumbnail for ${tempFile.absolutePath}", e)
                        } finally {
                            tempFile.delete()
                        }
                    }
                }
            } else {
                binding.ivThumbnail.setImageResource(R.drawable.ic_placeholder)
            }
        }



        private fun copyUriToCache(uri: Uri): File? {
            val context = itemView.context
            return try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val tempFile = File(context.cacheDir, "thumb_temp_${System.currentTimeMillis()}.dcm")
                val outputStream = FileOutputStream(tempFile)
                inputStream?.copyTo(outputStream)
                inputStream?.close()
                outputStream.close()
                tempFile
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbnailViewHolder {
        val binding = ItemSeriesThumbnailBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ThumbnailViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ThumbnailViewHolder, position: Int) {
        holder.bind(seriesList[position], position == selectedPosition)
    }



    override fun getItemCount(): Int = seriesList.size
}


