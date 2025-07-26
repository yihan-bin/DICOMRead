package com.example.dicomread

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class DicomSeries(
    val seriesUid: String,
    val seriesDescription: String,
    // ★★★ Changed to ArrayList<String> to be more robust with Parcelize ★★★
    val fileUris: ArrayList<String>,
    val pixelSpacing: DoubleArray?,
    val modality: String
) : Parcelable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DicomSeries
        if (seriesUid != other.seriesUid) return false
        if (pixelSpacing != null) {
            if (other.pixelSpacing == null) return false
            if (!pixelSpacing.contentEquals(other.pixelSpacing)) return false
        } else if (other.pixelSpacing != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = seriesUid.hashCode()
        result = 31 * result + (pixelSpacing?.contentHashCode() ?: 0)
        return result
    }
}

@Parcelize
data class DicomStudy(
    val studyUid: String,
    val series: List<DicomSeries>,
    val patientName: String,
    val studyDate: String,
    val modality: String
) : Parcelable

