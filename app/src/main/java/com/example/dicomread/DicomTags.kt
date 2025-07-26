package com.example.dicomread

data class DicomTags(
    val studyUID: String,
    val seriesUID: String,
    val seriesDescription: String,
    val pixelSpacing: DoubleArray, // [row, col]
    val patientName: String,
    val studyDate: String,
    val modality: String,
    // ★★★ 新增: 用于序列内切片排序的关键字段 ★★★
    val instanceNumber: Int
) {
    // equals 和 hashCode 自动处理新字段, 但为清晰起见手动实现
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DicomTags
        if (studyUID != other.studyUID) return false
        if (seriesUID != other.seriesUID) return false
        if (!pixelSpacing.contentEquals(other.pixelSpacing)) return false
        if (instanceNumber != other.instanceNumber) return false
        return true
    }

    override fun hashCode(): Int {
        var result = studyUID.hashCode()
        result = 31 * result + seriesUID.hashCode()
        result = 31 * result + pixelSpacing.contentHashCode()
        result = 31 * result + instanceNumber
        return result
    }
}
