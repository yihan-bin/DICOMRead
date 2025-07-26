package com.example.dicomread

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.dicomread.databinding.ItemStudyBinding
import java.text.SimpleDateFormat
import java.util.Locale

// ★★★ 修改构造函数以接收长按监听器 ★★★
class StudyAdapter(
    private val onClick: (DicomStudy) -> Unit,
    private val onLongClick: (DicomStudy) -> Unit
) : ListAdapter<DicomStudy, StudyAdapter.StudyViewHolder>(StudyDiffCallback) {

    // ★★★ ViewHolder现在也接收长按监听器 ★★★
    class StudyViewHolder(
        private val binding: ItemStudyBinding,
        val onClick: (DicomStudy) -> Unit,
        val onLongClick: (DicomStudy) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentStudy: DicomStudy? = null

        init {
            itemView.setOnClickListener {
                currentStudy?.let { study -> onClick(study) }
            }
            // ★★★ 设置长按监听 ★★★
            itemView.setOnLongClickListener {
                currentStudy?.let { study -> onLongClick(study) }
                true // 返回 true 表示事件已被消费
            }
        }

        fun bind(study: DicomStudy) {
            currentStudy = study
            binding.tvPatientName.text = study.patientName
            binding.tvStudyDate.text = formatDate(study.studyDate)
            binding.tvStudyUid.text = "Study UID: ${study.studyUid}"
            binding.tvSeriesCount.text = "${study.series.size} 序列"
            binding.tvSeriesM.text = "${study.modality}"
        }

        private fun formatDate(dicomDate: String): String {
            return try {
                val inputFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
                val outputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val date = inputFormat.parse(dicomDate)
                if (date != null) outputFormat.format(date) else dicomDate
            } catch (e: Exception) {
                dicomDate
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StudyViewHolder {
        val binding = ItemStudyBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        // ★★★ 传递监听器到 ViewHolder ★★★
        return StudyViewHolder(binding, onClick, onLongClick)
    }

    override fun onBindViewHolder(holder: StudyViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

object StudyDiffCallback : DiffUtil.ItemCallback<DicomStudy>() {
    override fun areItemsTheSame(oldItem: DicomStudy, newItem: DicomStudy): Boolean {
        return oldItem.studyUid == newItem.studyUid
    }
    override fun areContentsTheSame(oldItem: DicomStudy, newItem: DicomStudy): Boolean {
        return oldItem == newItem
    }
}

