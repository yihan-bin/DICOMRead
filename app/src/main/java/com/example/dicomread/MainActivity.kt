package com.example.dicomread

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.dicomread.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.permissionx.guolindev.PermissionX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var studyAdapter: StudyAdapter
    private var scanJob: Job? = null
    private lateinit var sharedPreferences: SharedPreferences
    private var currentFolderUri: Uri? = null
    private var isShowingFromAppStorage = false

    private val dedicatedDicomFolder: File by lazy {
        File(filesDir, "dicom_studies").apply { mkdirs() }
    }
    // ★★★ 理由: 统一管理3D模型缓存目录，方便后续的删除和管理，实现需求 #3 ★★★
    private val modelsCacheDir: File by lazy {
        File(cacheDir, "3d_models_cache").apply { mkdirs() }
    }

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            saveFolderUri(uri)
            updateUiWithSelectedFolder(uri)
            scanExternalFolder(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sharedPreferences = getSharedPreferences("dicom_prefs", MODE_PRIVATE)

        setupRecyclerView()
        setupButtons()
        requestPermissions()
        loadSavedFolder()
        scanDedicatedFolder()
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("权限请求")
                    .setMessage("为了扫描您设备上的DICOM文件夹，本应用需要“所有文件访问”权限。")
                    .setPositiveButton("去授权") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = Uri.fromParts("package", packageName, null)
                            startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(this, "无法打开权限设置界面", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        } else {
            PermissionX.init(this)
                .permissions(Manifest.permission.READ_EXTERNAL_STORAGE)
                .request { allGranted, _, _ ->
                    if (!allGranted) {
                        Toast.makeText(this, "未授予存储权限，无法扫描外部文件。", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    private fun setupRecyclerView() {
        studyAdapter = StudyAdapter(
            onClick = { study ->
                StudyRepository.addStudy(study)
                val intent = Intent(this, ViewerActivity::class.java).apply {
                    putExtra("EXTRA_STUDY_UID", study.studyUid)
                }
                startActivity(intent)
            },
            onLongClick = { study ->
                if (isShowingFromAppStorage) {
                    showDeleteStudyConfirmationDialog(study)
                } else {
                    Toast.makeText(this, "只能删除保存在专属存储中的研究。", Toast.LENGTH_SHORT).show()
                }
            }
        )
        binding.rvStudies.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = studyAdapter
        }
    }

    private fun setupButtons() {
        binding.btnLoadFromAppStorage.setOnClickListener { scanDedicatedFolder() }
        binding.btnClearAppStorage.setOnClickListener { showClearStorageConfirmationDialog() }
        binding.btnSelectFolder.setOnClickListener { folderPickerLauncher.launch(null) }
        binding.btnScan.setOnClickListener {
            currentFolderUri?.let { scanExternalFolder(it) } ?: Toast.makeText(this, "请先选择一个外部文件夹。", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scanDedicatedFolder() {
        scanJob?.cancel()
        updateScanStatus(true, "正在从专属存储加载...")
        isShowingFromAppStorage = true

        scanJob = lifecycleScope.launch(Dispatchers.IO) {
            val allFiles = dedicatedDicomFolder.walkTopDown()
                .filter { it.isFile && (it.name.endsWith(".dcm", true) || it.extension.isEmpty()) }
                .toList()

            val studies = if (allFiles.isNotEmpty()) processFiles(allFiles.map { it.absolutePath }) else emptyList()

            withContext(Dispatchers.Main) {
                StudyRepository.setStudies(studies)
                studyAdapter.submitList(studies.sortedBy { it.patientName })
                val message = if (studies.isEmpty()) "专属存储为空。" else "已从专属存储加载 ${studies.size} 个研究。"
                updateScanStatus(false, message)
            }
        }
    }

    private fun scanExternalFolder(rootUri: Uri) {
        scanJob?.cancel()
        isShowingFromAppStorage = false
        updateScanStatus(true, "正在扫描外部文件夹...")
        studyAdapter.submitList(emptyList())

        scanJob = lifecycleScope.launch(Dispatchers.IO) {
            val rootDocFile = DocumentFile.fromTreeUri(applicationContext, rootUri)
            if (rootDocFile == null || !rootDocFile.isDirectory) {
                withContext(Dispatchers.Main) { updateScanStatus(false, "错误：无法访问该文件夹。") }
                return@launch
            }

            val dicomFileUris = mutableListOf<String>()
            withContext(Dispatchers.Main) { binding.tvStatus.text = "正在查找文件..." }
            findAllDicomDocFiles(rootDocFile, dicomFileUris)

            val studies = if (dicomFileUris.isNotEmpty()) processFiles(dicomFileUris) else emptyList()

            withContext(Dispatchers.Main) {
                studyAdapter.submitList(studies.sortedBy { it.patientName })
                updateScanStatus(false, "外部文件夹扫描完成！发现 ${studies.size} 个研究。")
                StudyRepository.setStudies(studies)
                if (studies.isNotEmpty()) showCopyToDedicatedStorageDialog(studies)
            }
        }
    }

    private fun showCopyToDedicatedStorageDialog(studies: List<DicomStudy>) {
        MaterialAlertDialogBuilder(this)
            .setTitle("保存到应用专属存储")
            .setMessage("扫描完成！共发现 ${studies.size} 个研究。\n\n是否要将它们复制到应用的专属存储空间，以便将来更快地访问和管理？")
            .setNegativeButton("仅本次查看", null)
            .setPositiveButton("复制") { _, _ -> copyStudiesToDedicatedFolder(studies) }
            .setCancelable(true)
            .show()
    }

    private fun copyStudiesToDedicatedFolder(studies: List<DicomStudy>) {
        updateScanStatus(true, "正在复制到专属存储...")
        lifecycleScope.launch(Dispatchers.IO) {
            var seriesCopied = 0
            var seriesSkipped = 0

            studies.forEach { study ->
                val studyDir = File(dedicatedDicomFolder, study.studyUid)
                study.series.forEach { series ->
                    val seriesDir = File(studyDir, series.seriesUid)

                    if (seriesDir.exists()) {
                        seriesSkipped++
                        return@forEach
                    }

                    seriesDir.mkdirs()
                    series.fileUris.forEach { uriString ->
                        val uri = Uri.parse(uriString)
                        val fileName = DocumentFile.fromSingleUri(applicationContext, uri)?.name ?: "file_${System.currentTimeMillis()}.dcm"
                        val destFile = File(seriesDir, fileName)
                        try {
                            contentResolver.openInputStream(uri)?.use { input ->
                                FileOutputStream(destFile).use { output -> input.copyTo(output) }
                            }
                        } catch (e: IOException) {
                            Log.e("Copy", "Failed to copy $uriString", e)
                        }
                    }
                    seriesCopied++
                    withContext(Dispatchers.Main) {
                        binding.tvStatus.text = "已复制 $seriesCopied 个新序列, 跳过 $seriesSkipped 个..."
                    }
                }
            }
            withContext(Dispatchers.Main) {
                val message = "复制完成！新增 $seriesCopied 个序列，跳过 $seriesSkipped 个已存在的序列。"
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                scanDedicatedFolder()
            }
        }
    }

    private fun showClearStorageConfirmationDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("确认清空")
            .setMessage("您确定要删除所有保存在应用专属存储中的DICOM文件和3D模型吗？此操作不可撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("全部删除") { _, _ -> clearDedicatedFolder() }
            .show()
    }

    private fun clearDedicatedFolder() {
        lifecycleScope.launch(Dispatchers.IO) {
            // ★★★ 理由: 实现需求 #3，清空时同时删除DICOM文件和3D模型缓存 ★★★
            val dicomDeleteSuccess = dedicatedDicomFolder.deleteRecursively()
            val modelDeleteSuccess = modelsCacheDir.deleteRecursively()
            dedicatedDicomFolder.mkdirs()
            modelsCacheDir.mkdirs()
            withContext(Dispatchers.Main) {
                if (dicomDeleteSuccess && modelDeleteSuccess) {
                    Toast.makeText(this@MainActivity, "专属存储已清空。", Toast.LENGTH_SHORT).show()
                    scanDedicatedFolder()
                } else {
                    Toast.makeText(this@MainActivity, "清空存储失败。", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showDeleteStudyConfirmationDialog(study: DicomStudy) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除研究")
            .setMessage("您确定要从专属存储中删除患者 '${study.patientName}' 的研究以及其对应的所有3D模型缓存吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> deleteStudyFromDedicatedFolder(study) }
            .show()
    }

    private fun deleteStudyFromDedicatedFolder(study: DicomStudy) {
        lifecycleScope.launch(Dispatchers.IO) {
            val studyDir = File(dedicatedDicomFolder, study.studyUid)
            val dicomDeleteSuccess = if (studyDir.exists()) studyDir.deleteRecursively() else true

            // ★★★ 理由: 实现需求 #3，删除研究时，遍历其所有序列，删除对应的3D模型缓存 ★★★
            var modelsDeletedCount = 0
            study.series.forEach { series ->
                modelsCacheDir.listFiles { _, name -> name.startsWith(series.seriesUid) && name.endsWith(".vtp") }?.forEach { modelFile ->
                    if (modelFile.delete()) modelsDeletedCount++
                }
            }

            withContext(Dispatchers.Main) {
                if(dicomDeleteSuccess) {
                    val message = "研究已删除" + if (modelsDeletedCount > 0) " (及 $modelsDeletedCount 个3D模型)" else ""
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    scanDedicatedFolder()
                } else {
                    Toast.makeText(this@MainActivity, "删除研究文件失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun findAllDicomDocFiles(directory: DocumentFile, fileUriList: MutableList<String>) {
        directory.listFiles().forEach { file ->
            if (file.isDirectory) {
                findAllDicomDocFiles(file, fileUriList)
            } else if (file.isFile) {
                val name = file.name?.lowercase()
                if (name != null && (name.endsWith(".dcm") || !name.contains("."))) {
                    fileUriList.add(file.uri.toString())
                }
            }
        }
    }

    private fun processFiles(filePathsOrUris: List<String>): List<DicomStudy> {
        val studyDataMap = mutableMapOf<String, MutableMap<String, Pair<DicomTags, MutableList<Pair<String, Int>>>>>()
        val tempFilesCreated = mutableListOf<File>()
        try {
            filePathsOrUris.forEachIndexed { index, pathOrUri ->
                val (effectivePath, tempFile) = getEffectivePath(pathOrUri)
                tempFile?.let { tempFilesCreated.add(it) }

                if (effectivePath != null) {
                    val tags = DicomModule.getDicomTags(effectivePath)
                    if (tags != null && tags.studyUID.isNotBlank() && tags.seriesUID.isNotBlank() && tags.instanceNumber != -1) {
                        val seriesMap = studyDataMap.getOrPut(tags.studyUID) { mutableMapOf() }
                        val seriesEntry = seriesMap.getOrPut(tags.seriesUID) { Pair(tags, mutableListOf()) }
                        seriesEntry.second.add(Pair(pathOrUri, tags.instanceNumber))
                    }
                }
                if (tempFile != null) {
                    tempFile.delete()
                    tempFilesCreated.remove(tempFile)
                }
            }
        } finally {
            tempFilesCreated.forEach { it.delete() }
        }

        return studyDataMap.map { (studyUid, seriesMap) ->
            val firstSeriesTags = seriesMap.values.firstOrNull()?.first
            DicomStudy(
                studyUid = studyUid,
                patientName = firstSeriesTags?.patientName ?: "未知患者",
                studyDate = firstSeriesTags?.studyDate ?: "未知日期",
                modality = firstSeriesTags?.modality?.trim() ?: "N/A",
                series = seriesMap.map { (_, seriesData) ->
                    val tags = seriesData.first
                    val sortedUris = seriesData.second.sortedBy { it.second }.map { it.first }
                    DicomSeries(
                        seriesUid = tags.seriesUID,
                        seriesDescription = tags.seriesDescription,
                        fileUris = ArrayList(sortedUris),
                        pixelSpacing = tags.pixelSpacing,
                        modality = tags.modality.trim()
                    )
                }.sortedBy { it.seriesDescription }
            )
        }
    }

    private fun getEffectivePath(pathOrUri: String): Pair<String?, File?> {
        return if (pathOrUri.startsWith("content://")) {
            val tempFile = copyUriToCache(Uri.parse(pathOrUri))
            Pair(tempFile?.absolutePath, tempFile)
        } else {
            Pair(pathOrUri, null)
        }
    }

    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val tempFile = File.createTempFile("scan_temp", ".dcm", cacheDir)
            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream -> inputStream.copyTo(outputStream) }
            }
            tempFile
        } catch (e: Exception) {
            Log.e("DicomScan", "Failed to copy URI to cache: $uri", e)
            null
        }
    }

    private fun updateScanStatus(inProgress: Boolean, message: String) {
        binding.progressBar.isVisible = inProgress
        binding.btnScan.isEnabled = !inProgress
        binding.btnSelectFolder.isEnabled = !inProgress
        binding.btnLoadFromAppStorage.isEnabled = !inProgress
        binding.btnClearAppStorage.isEnabled = !inProgress
        binding.tvStatus.text = message
    }

    private fun saveFolderUri(uri: Uri) {
        sharedPreferences.edit().putString("folder_uri", uri.toString()).apply()
        currentFolderUri = uri
    }

    private fun loadSavedFolder() {
        val uriString = sharedPreferences.getString("folder_uri", null)
        if (uriString != null) {
            currentFolderUri = Uri.parse(uriString)
            val hasPermission = contentResolver.persistedUriPermissions.any { it.uri == currentFolderUri && it.isReadPermission }
            if (hasPermission) {
                updateUiWithSelectedFolder(currentFolderUri!!)
            } else {
                sharedPreferences.edit().remove("folder_uri").apply()
                currentFolderUri = null
                updateUiWithSelectedFolder(null)
            }
        }
    }

    private fun updateUiWithSelectedFolder(uri: Uri?) {
        if (uri != null) {
            val docFile = DocumentFile.fromTreeUri(this, uri)
            binding.tvSelectedFolder.text = "当前外部文件夹: ${docFile?.name ?: "未知"}"
            binding.btnScan.isEnabled = true
        } else {
            binding.tvSelectedFolder.text = "未选择外部文件夹"
            binding.btnScan.isEnabled = false
        }
    }
}
