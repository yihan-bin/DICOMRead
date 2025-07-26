package com.example.dicomread

import android.graphics.Bitmap

object DicomModule {
    init {
        System.loadLibrary("dicomread")
    }

    // --- 2D Functions (保持您提供的版本不变) ---
    external fun getDicomTags(filePath: String): DicomTags?
    external fun getDicomImageSize(filePath: String): IntArray?
    external fun loadDicomImage(filePath: String, bitmap: Bitmap): Int

    // --- 3D Functions (已更新以匹配C++新架构) ---

    /**
     * 从DICOM序列目录生成3D模型，并将其缓存在C++内存中。
     * @return 返回一个长整型句柄(Long)。如果生成失败，返回0。
     */
    external fun generateAndCache3DModel(
        directoryPath: String,
        isoLevel: Float,
        sampleRate: Float,
        smoothingIterations: Int,
        smoothingFactor: Float
    ): Long

    /**
     * 将C++内存中由句柄指定的模型保存到文件。
     * @param handle 模型句柄，由 generateAndCache3DModel 或 loadAndCacheModelFromFile 返回。
     * @param filePath 要保存到的目标文件路径 (例如 .vtp 格式)。
     * @return 如果保存成功，返回true。
     */
    external fun saveModelToFile(handle: Long, filePath: String): Boolean

    /**
     * 从一个已保存的模型文件（如.vtp）加载模型数据到C++内存中。
     * @param filePath 模型文件路径。
     * @return 返回一个新的模型句柄。如果加载失败，返回0。
     */
    external fun loadAndCacheModelFromFile(filePath: String): Long

    /**
     * 释放C++内存中由句柄指定的模型数据（仅CPU部分）。
     * GPU资源由ModelRenderer通过nativeReleaseGpuResources释放。
     * @param handle 要释放的模型句柄。
     */
    external fun release3DModel(handle: Long)
}
