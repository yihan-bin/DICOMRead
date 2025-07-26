package com.example.dicomread

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.dicomread.databinding.ActivityComparisonBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ComparisonActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PRIMARY_STUDY_UID = "primary_study_uid"
        const val EXTRA_PRIMARY_SERIES_UID = "primary_series_uid"
        const val EXTRA_SECONDARY_STUDY_UID = "secondary_study_uid"
        const val EXTRA_SECONDARY_SERIES_UID = "secondary_series_uid"
    }

    private lateinit var binding: ActivityComparisonBinding
    private var primarySeries: DicomSeries? = null
    private var secondarySeries: DicomSeries? = null

    private var primaryLoadJob: Job? = null
    private var secondaryLoadJob: Job? = null

    private enum class ControlMode {
        PRIMARY, SECONDARY, SYNC
    }
    private var currentControlMode = ControlMode.SYNC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityComparisonBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val pStudyUid = intent.getStringExtra(EXTRA_PRIMARY_STUDY_UID)
        val pSeriesUid = intent.getStringExtra(EXTRA_PRIMARY_SERIES_UID)
        val sStudyUid = intent.getStringExtra(EXTRA_SECONDARY_STUDY_UID)
        val sSeriesUid = intent.getStringExtra(EXTRA_SECONDARY_SERIES_UID)

        if (pStudyUid == null || pSeriesUid == null || sStudyUid == null || sSeriesUid == null) {
            Toast.makeText(this, "Missing information for comparison", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        val pStudy = StudyRepository.getStudyByUid(pStudyUid)
        val sStudy = StudyRepository.getStudyByUid(sStudyUid)
        primarySeries = pStudy?.series?.find { it.seriesUid == pSeriesUid }
        secondarySeries = sStudy?.series?.find { it.seriesUid == sSeriesUid }

        if (primarySeries == null || secondarySeries == null) {
            Toast.makeText(this, "Could not find series to compare", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        setupViews()
        setupListeners()
    }

    private fun setupViews() {
        binding.sliderPrimarySlice.valueFrom = 0f
        binding.sliderPrimarySlice.valueTo = (primarySeries!!.fileUris.size - 1).toFloat().coerceAtLeast(0f)
        binding.sliderPrimarySlice.stepSize = 1f
        binding.sliderPrimarySlice.value = 0f

        binding.sliderSecondarySlice.valueFrom = 0f
        binding.sliderSecondarySlice.valueTo = (secondarySeries!!.fileUris.size - 1).toFloat().coerceAtLeast(0f)
        binding.sliderSecondarySlice.stepSize = 1f
        binding.sliderSecondarySlice.value = 0f

        binding.imageViewPrimary.alpha = binding.sliderPrimaryAlpha.value
        binding.imageViewSecondary.alpha = binding.sliderSecondaryAlpha.value

        if (primarySeries!!.fileUris.isNotEmpty()) loadPrimaryImage(0)
        if (secondarySeries!!.fileUris.isNotEmpty()) loadSecondaryImage(0)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // ★★★ 修正：将旋转功能直接集成到 ZoomableImageView 中，而不是使用独立的手势检测器 ★★★
        // 移除独立的旋转检测器，改为在触摸拦截器中正确处理手势传递
        binding.touchInterceptor.setOnTouchListener { _, event ->
            when (currentControlMode) {
                ControlMode.PRIMARY -> {
                    // 直接将事件传递给主图像视图，ZoomableImageView 会处理所有手势（包括旋转）
                    binding.imageViewPrimary.onTouchEvent(event)
                }
                ControlMode.SECONDARY -> {
                    // 直接将事件传递给对比图像视图
                    binding.imageViewSecondary.onTouchEvent(event)
                }
                ControlMode.SYNC -> {
                    // ★★★ 修正：同步操作时，先处理主图像，然后同步变换到副图像 ★★★
                    val result = binding.imageViewPrimary.onTouchEvent(event)

                    // 获取主图像的当前变换矩阵并应用到副图像
                    val primaryMatrix = binding.imageViewPrimary.imageMatrix
                    binding.imageViewSecondary.imageMatrix = android.graphics.Matrix(primaryMatrix)
                    binding.imageViewSecondary.invalidate()

                    result
                }
            }
            true
        }

        binding.radioGroupControlMode.setOnCheckedChangeListener { _, checkedId ->
            currentControlMode = when (checkedId) {
                R.id.radio_control_primary -> ControlMode.PRIMARY
                R.id.radio_control_secondary -> ControlMode.SECONDARY
                else -> ControlMode.SYNC
            }
        }

        // Alpha sliders
        binding.sliderPrimaryAlpha.addOnChangeListener { _, value, _ ->
            binding.imageViewPrimary.alpha = value
        }
        binding.sliderSecondaryAlpha.addOnChangeListener { _, value, _ ->
            binding.imageViewSecondary.alpha = value
        }

        // Slice sliders
        binding.sliderPrimarySlice.addOnChangeListener { _, value, fromUser ->
            if (fromUser) loadPrimaryImage(value.toInt())
        }
        binding.sliderSecondarySlice.addOnChangeListener { _, value, fromUser ->
            if (fromUser) loadSecondaryImage(value.toInt())
        }
    }

    private fun loadPrimaryImage(index: Int) {
        primaryLoadJob?.cancel()
        val series = primarySeries ?: return
        if (index !in series.fileUris.indices) return

        binding.progressPrimary.visibility = View.VISIBLE
        binding.tvPrimarySliceInfo.text = "主序列: ${index + 1}/${series.fileUris.size}"
        primaryLoadJob = lifecycleScope.launch {
            val bitmap = loadBitmapFromPathOrUri(series.fileUris[index])
            binding.progressPrimary.visibility = View.GONE
            if (bitmap != null) {
                binding.imageViewPrimary.setImageBitmap(bitmap, series.pixelSpacing)
            }
        }
    }

    private fun loadSecondaryImage(index: Int) {
        secondaryLoadJob?.cancel()
        val series = secondarySeries ?: return
        if (index !in series.fileUris.indices) return

        binding.progressSecondary.visibility = View.VISIBLE
        binding.tvSecondarySliceInfo.text = "对比序列: ${index + 1}/${series.fileUris.size}"
        secondaryLoadJob = lifecycleScope.launch {
            val bitmap = loadBitmapFromPathOrUri(series.fileUris[index])
            binding.progressSecondary.visibility = View.GONE
            if (bitmap != null) {
                binding.imageViewSecondary.setImageBitmap(bitmap, series.pixelSpacing)
            }
        }
    }

    private suspend fun loadBitmapFromPathOrUri(pathOrUri: String): Bitmap? = withContext(Dispatchers.IO) {
        val isContentUri = pathOrUri.startsWith("content://")
        var tempFile: File? = null
        val filePathToProcess: String

        try {
            filePathToProcess = if (isContentUri) {
                tempFile = File(cacheDir, "compare_temp_${System.nanoTime()}.dcm")
                if (copyUriToFile(Uri.parse(pathOrUri), tempFile)) tempFile.absolutePath else return@withContext null
            } else {
                pathOrUri
            }
            val size = DicomModule.getDicomImageSize(filePathToProcess)
            if (size != null && size[0] > 0 && size[1] > 0) {
                val bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888)
                if (DicomModule.loadDicomImage(filePathToProcess, bitmap) == 0) {
                    return@withContext bitmap
                }
            }
        } catch (e: Exception) {
            Log.e("ComparisonActivity", "Failed to load image from $pathOrUri", e)
        } finally {
            tempFile?.delete()
        }
        return@withContext null
    }

    private fun copyUriToFile(uri: Uri, destFile: File): Boolean {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: IOException) {
            Log.e("ComparisonActivity", "Failed to copy URI to file.", e)
            false
        }
    }
}
