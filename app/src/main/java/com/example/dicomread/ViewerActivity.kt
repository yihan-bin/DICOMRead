package com.example.dicomread

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.LruCache
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.dicomread.databinding.ActivityViewerBinding
import com.example.dicomread.databinding.Dialog3dOptionsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

class ViewerActivity : AppCompatActivity(), ZoomableImageView.OnSwipeListener {

    private lateinit var binding: ActivityViewerBinding
    private var study: DicomStudy? = null
    private lateinit var thumbnailAdapter: SeriesThumbnailAdapter
    private var currentSeriesIndex = 0
    private var currentSliceIndex = 0
    private var mainImageLoadJob: Job? = null
    private lateinit var bitmapCache: LruCache<Int, Bitmap>

    // ★★★ 理由: 统一管理3D模型缓存目录，与MainActivity保持一致，实现需求 #3 ★★★
    private val modelsCacheDir: File by lazy {
        File(cacheDir, "3d_models_cache").apply { mkdirs() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val memClass = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val cacheSize = 1024 * 1024 * memClass.memoryClass / 8
        bitmapCache = object : LruCache<Int, Bitmap>(cacheSize) {
            override fun sizeOf(key: Int, bitmap: Bitmap): Int = bitmap.byteCount
        }

        val studyUid = intent.getStringExtra("EXTRA_STUDY_UID")
        if (studyUid == null) {
            Toast.makeText(this, "Missing Study UID", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        study = StudyRepository.getStudyByUid(studyUid)

        if (study == null) {
            Toast.makeText(this, "找不到研究数据。", Toast.LENGTH_LONG).show()
            finish(); return
        }

        binding.imageView.setOnSwipeListener(this)
        setupToolbar()
        setupThumbnailRecyclerView()
        setupControls()
        selectSeries(0)
    }

    // ★★★ 修改：改进3D选项对话框的参数提示显示 ★★★
    private fun show3DOptionsDialog() {
        val series = study?.series?.getOrNull(currentSeriesIndex) ?: return
        if (series.fileUris.size < 10) {
            Toast.makeText(this, "序列切片过少(<10)，无法进行3D重建。", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogBinding = Dialog3dOptionsBinding.inflate(LayoutInflater.from(this))
        val isCT = series.modality.uppercase().contains("CT")

        // 根据模态（CT/MR）设置不同的默认参数
        if (isCT) {
            dialogBinding.labelIsoLevel.text = "阈值 (HU): 300"
            dialogBinding.sliderIsoLevel.valueFrom = -1000f; dialogBinding.sliderIsoLevel.valueTo = 3000f
            dialogBinding.sliderIsoLevel.value = 300f;
            dialogBinding.sliderIsoLevel.stepSize = 10f
        } else {
            dialogBinding.labelIsoLevel.text = "阈值 (强度): 150"
            dialogBinding.sliderIsoLevel.valueFrom = 0f; dialogBinding.sliderIsoLevel.valueTo = 1500f
            dialogBinding.sliderIsoLevel.value = 150f;
            dialogBinding.sliderIsoLevel.stepSize = 5f
        }

        // ★★★ 改进：实时更新参数显示，满足需求 #12 ★★★
        dialogBinding.labelSampleRate.text = "降采样率: 1.0x"
        dialogBinding.labelSmoothing.text = "平滑次数: 15"

        dialogBinding.sliderIsoLevel.addOnChangeListener { _, value, _ ->
            dialogBinding.labelIsoLevel.text = if(isCT) "阈值 (HU): ${value.toInt()}" else "阈值 (强度): ${value.toInt()}"
        }
        dialogBinding.sliderSampleRate.addOnChangeListener { _, value, _ ->
            dialogBinding.labelSampleRate.text = "降采样率: ${String.format("%.1f", value)}x"
        }
        dialogBinding.sliderSmoothing.addOnChangeListener { _, value, _ ->
            dialogBinding.labelSmoothing.text = "平滑次数: ${value.toInt()}"
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("3D模型生成选项")
            .setView(dialogBinding.root)
            .setNegativeButton("取消", null)
            .setPositiveButton("生成") { _, _ ->
                val isoLevel = dialogBinding.sliderIsoLevel.value
                val sampleRate = dialogBinding.sliderSampleRate.value
                val smoothingIterations = dialogBinding.sliderSmoothing.value.toInt()
                handleGenerate3D(isoLevel, sampleRate, smoothingIterations)
            }
            .show()
    }


    // ★★★ 理由: 实现需求 #3，智能缓存逻辑 ★★★
    // 1. 根据参数生成唯一文件名，如果文件存在则直接加载。
    // 2. 如果文件不存在，则生成新模型，并以该文件名保存。
    private fun handleGenerate3D(isoLevel: Float, sampleRate: Float, smoothingIterations: Int) {
        val series = study?.series?.getOrNull(currentSeriesIndex) ?: return
        val dialog = MaterialAlertDialogBuilder(this).setTitle("处理3D模型").setMessage("正在初始化...").setCancelable(false).create()
        dialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            // 根据参数生成唯一的哈希码作为文件名的一部分
            val paramsString = "iso${isoLevel}_sample${sampleRate}_smooth${smoothingIterations}"
            val modelFileName = "${series.seriesUid}_${paramsString.hashCode()}.vtp"
            val cachedModelFile = File(modelsCacheDir, modelFileName)

            var modelHandle: Long = 0

            // 1. 检查缓存
            if (cachedModelFile.exists() && cachedModelFile.length() > 0) {
                withContext(Dispatchers.Main) { dialog.setMessage("正在从本地缓存加载模型...") }
                modelHandle = DicomModule.loadAndCacheModelFromFile(cachedModelFile.absolutePath)
                if (modelHandle == 0L) {
                    withContext(Dispatchers.Main) { Toast.makeText(this@ViewerActivity, "从缓存加载失败，将重新生成。", Toast.LENGTH_LONG).show() }
                    cachedModelFile.delete() // 无效缓存，删除
                }
            }

            // 2. 如果没有缓存或加载失败，则生成新模型
            if (modelHandle == 0L) {
                modelHandle = generateNewModel(series, isoLevel, sampleRate, smoothingIterations, cachedModelFile, dialog)
            }

            // 3. 启动3D查看器
            withContext(Dispatchers.Main) {
                dialog.dismiss()
                if (modelHandle != 0L) {
                    launch3DViewer(modelHandle)
                } else {
                    Toast.makeText(this@ViewerActivity, "无法生成或加载3D模型。", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun generateNewModel(series: DicomSeries, isoLevel: Float, sampleRate: Float, smoothingIterations: Int, modelCacheFile: File, dialog: AlertDialog): Long {
        val tempDir = File(cacheDir, "3d_gen_${System.currentTimeMillis()}").apply { mkdirs() }
        var modelHandle: Long = 0
        try {
            // 复制文件到临时目录
            withContext(Dispatchers.Main) { dialog.setMessage("正在准备文件 (0/${series.fileUris.size})...") }
            series.fileUris.forEachIndexed { index, pathOrUri ->
                val destFile = File(tempDir, "slice_${String.format("%05d", index)}.dcm")
                copyUriOrPathToFile(pathOrUri, destFile)
                if ((index + 1) % 10 == 0 || index == series.fileUris.size - 1) {
                    withContext(Dispatchers.Main) { dialog.setMessage("正在准备文件 (${index + 1}/${series.fileUris.size})...") }
                }
            }

            // 调用JNI生成模型
            withContext(Dispatchers.Main) { dialog.setMessage("文件准备完毕，正在生成模型(这可能需要一些时间)...") }
            modelHandle = DicomModule.generateAndCache3DModel(
                directoryPath = tempDir.absolutePath, isoLevel = isoLevel, sampleRate = sampleRate,
                smoothingIterations = smoothingIterations, smoothingFactor = 0.5f
            )

            if (modelHandle == 0L) throw Exception("模型生成失败，C++层返回句柄为0。")

            // 缓存到磁盘
            withContext(Dispatchers.Main) { dialog.setMessage("生成成功！正在缓存到磁盘...") }
            if (!DicomModule.saveModelToFile(modelHandle, modelCacheFile.absolutePath)) {
                Log.w("3DGeneration", "未能将模型缓存到: ${modelCacheFile.absolutePath}")
            }

        } catch (e: Exception) {
            Log.e("3DGeneration", "生成新模型时出错", e)
            withContext(Dispatchers.Main) { Toast.makeText(this@ViewerActivity, "错误: ${e.message}", Toast.LENGTH_LONG).show() }
            if(modelHandle != 0L) DicomModule.release3DModel(modelHandle) // 如果出错，确保释放已创建的句柄
            modelHandle = 0L
        } finally {
            tempDir.deleteRecursively() // 清理临时文件
        }
        return modelHandle
    }

    private fun launch3DViewer(modelHandle: Long) {
        val series = study?.series?.getOrNull(currentSeriesIndex)
        val pixelSpacing = series?.pixelSpacing

        // 估算物理尺寸：如果有像素间距信息，使用它来计算；否则使用默认值
        val physicalSizeMm = if (pixelSpacing != null && pixelSpacing.size >= 2 && pixelSpacing[0] > 0) {
            // 假设图像大小约为512x512，计算物理宽度
            (512 * pixelSpacing[0]).toFloat()
        } else {
            200f // 默认200mm
        }

        val intent = Viewer3DActivity.newIntent(this, modelHandle, physicalSizeMm)
        startActivity(intent)
    }

    private fun loadMainDicomImage() {
        mainImageLoadJob?.cancel()
        val series = study?.series?.getOrNull(currentSeriesIndex) ?: return
        if (!series.fileUris.indices.contains(currentSliceIndex)) return
        updateUIBeforeLoad()
        val cacheKey = currentSeriesIndex * 10000 + currentSliceIndex
        val cachedBitmap = bitmapCache.get(cacheKey)
        if (cachedBitmap != null) {
            binding.progressBar.visibility = View.GONE
            binding.imageView.setImageBitmap(cachedBitmap, series.pixelSpacing)
            preloadAdjacentImages(series, currentSliceIndex)
            return
        }
        val pathOrUri = series.fileUris[currentSliceIndex]
        mainImageLoadJob = lifecycleScope.launch {
            val (effectivePath, tempFile) = getEffectivePath(pathOrUri)
            try {
                if (effectivePath != null) {
                    val bitmap = loadBitmapFromPath(effectivePath)
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        if (bitmap != null) {
                            bitmapCache.put(cacheKey, bitmap)
                            binding.imageView.setImageBitmap(bitmap, series.pixelSpacing)
                            preloadAdjacentImages(series, currentSliceIndex)
                        } else {
                            binding.tvImagePath.text = "图像加载失败"
                        }
                    }
                }
            } finally {
                tempFile?.delete()
            }
        }
    }

    private suspend fun copyUriOrPathToFile(pathOrUri: String, destFile: File): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            if (pathOrUri.startsWith("content://")) {
                contentResolver.openInputStream(Uri.parse(pathOrUri))?.use { input ->
                    FileOutputStream(destFile).use { output -> input.copyTo(output) }
                }
            } else {
                File(pathOrUri).copyTo(destFile, true)
            }
            true
        } catch (e: Exception) {
            Log.e("ViewerActivity", "文件复制失败: $pathOrUri -> ${destFile.name}", e)
            false
        }
    }
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = study?.patientName
        supportActionBar?.subtitle = "研究日期: ${formatDate(study?.studyDate ?: "")}"
    }
    private fun setupThumbnailRecyclerView() {
        thumbnailAdapter = SeriesThumbnailAdapter(study!!.series) { seriesIndex ->
            selectSeries(seriesIndex)
        }
        binding.rvSeriesThumbnails.apply {
            layoutManager = LinearLayoutManager(this@ViewerActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = thumbnailAdapter
        }
    }
    private fun setupControls() {
        binding.btnPrev.setOnClickListener { onSwipeDown() }
        binding.btnNext.setOnClickListener { onSwipeUp() }
    }
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.viewer_menu, menu)
        return true
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_generate_3d -> { show3DOptionsDialog(); true }
            R.id.action_compare_series -> { showSeriesSelectionDialogForComparison(); true }
            android.R.id.home -> { onBackPressedDispatcher.onBackPressed(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }
    private fun showSeriesSelectionDialogForComparison() {
        val allStudies = StudyRepository.getAllStudies()
        if (allStudies.flatMap { it.series }.size <= 1) {
            Toast.makeText(this, "没有其他序列可供比较。", Toast.LENGTH_SHORT).show()
            return
        }
        data class SeriesSelectionInfo(val displayText: String, val studyUid: String, val seriesUid: String)
        val allSeriesInfo = allStudies.flatMap { study ->
            study.series.map { series ->
                SeriesSelectionInfo(
                    displayText = "${study.patientName} (${formatDate(study.studyDate)}) - ${series.seriesDescription}",
                    studyUid = study.studyUid,
                    seriesUid = series.seriesUid
                )
            }
        }
        val displayItems = allSeriesInfo.map { it.displayText }.toTypedArray()
        val currentPrimarySeries = study?.series?.getOrNull(currentSeriesIndex)
        MaterialAlertDialogBuilder(this)
            .setTitle("选择一个序列进行对比")
            .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, displayItems)) { _, which ->
                val selectedSeriesInfo = allSeriesInfo[which]
                val primaryStudy = study ?: return@setAdapter
                val primarySeries = currentPrimarySeries ?: return@setAdapter
                if (primaryStudy.studyUid == selectedSeriesInfo.studyUid && primarySeries.seriesUid == selectedSeriesInfo.seriesUid) {
                    Toast.makeText(this, "无法与自身进行比较。", Toast.LENGTH_SHORT).show()
                    return@setAdapter
                }
                val intent = Intent(this, ComparisonActivity::class.java).apply {
                    putExtra(ComparisonActivity.EXTRA_PRIMARY_STUDY_UID, primaryStudy.studyUid)
                    putExtra(ComparisonActivity.EXTRA_PRIMARY_SERIES_UID, primarySeries.seriesUid)
                    putExtra(ComparisonActivity.EXTRA_SECONDARY_STUDY_UID, selectedSeriesInfo.studyUid)
                    putExtra(ComparisonActivity.EXTRA_SECONDARY_SERIES_UID, selectedSeriesInfo.seriesUid)
                }
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    override fun onSwipeUp() {
        val series = study?.series?.getOrNull(currentSeriesIndex) ?: return
        if (currentSliceIndex < series.fileUris.size - 1) {
            currentSliceIndex++
            loadMainDicomImage()
        }
    }
    override fun onSwipeDown() {
        if (currentSliceIndex > 0) {
            currentSliceIndex--
            loadMainDicomImage()
        }
    }
    private fun selectSeries(seriesIndex: Int) {
        if (study?.series?.indices?.contains(seriesIndex) != true) return
        currentSeriesIndex = seriesIndex
        currentSliceIndex = 0
        thumbnailAdapter.setSelected(seriesIndex)
        binding.rvSeriesThumbnails.scrollToPosition(seriesIndex)
        binding.imageView.resetZoom()
        loadMainDicomImage()
    }
    private fun preloadAdjacentImages(series: DicomSeries, centerIndex: Int) {
        listOf(centerIndex - 1, centerIndex + 1).forEach { index ->
            if (series.fileUris.indices.contains(index)) {
                val cacheKey = currentSeriesIndex * 10000 + index
                if(bitmapCache.get(cacheKey) == null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val (path, file) = getEffectivePath(series.fileUris[index])
                        try {
                            if (path != null) {
                                val bitmap = loadBitmapFromPath(path)
                                if (bitmap != null) bitmapCache.put(cacheKey, bitmap)
                            }
                        } finally {
                            file?.delete()
                        }
                    }
                }
            }
        }
    }
    private suspend fun getEffectivePath(pathOrUri: String): Pair<String?, File?> {
        return if (pathOrUri.startsWith("content://")) {
            val tempFile = withContext(Dispatchers.IO) { File.createTempFile("viewer_temp",".dcm", cacheDir) }
            if (copyUriOrPathToFile(pathOrUri, tempFile)) Pair(tempFile.absolutePath, tempFile) else Pair(null, tempFile)
        } else {
            Pair(pathOrUri, null)
        }
    }
    private suspend fun loadBitmapFromPath(path: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val size = DicomModule.getDicomImageSize(path)
            if (size != null && size[0] > 0 && size[1] > 0) {
                val bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888)
                if (DicomModule.loadDicomImage(path, bitmap) == 0) return@withContext bitmap
            }
        } catch (e: Exception) {
            Log.e("ViewerActivity", "Failed to load DICOM image from $path", e)
        }
        null
    }
    private fun updateUIBeforeLoad() {
        val series = study?.series?.getOrNull(currentSeriesIndex) ?: return
        binding.progressBar.visibility = View.VISIBLE
        binding.imageView.setImageBitmap(null, null)
        binding.tvImagePath.text = "序列 ${currentSeriesIndex + 1}/${study!!.series.size} (${series.seriesDescription})\n图像 ${currentSliceIndex + 1}/${series.fileUris.size}"
        binding.btnPrev.isEnabled = currentSliceIndex > 0
        binding.btnNext.isEnabled = currentSliceIndex < series.fileUris.size - 1
    }
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
    private fun formatDate(dicomDate: String): String {
        if (dicomDate.length != 8) return dicomDate
        return try {
            val inputFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
            val outputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val date = inputFormat.parse(dicomDate)!!
            outputFormat.format(date)
        } catch (e: Exception) {
            dicomDate
        }
    }
}
