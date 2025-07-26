package com.example.dicomread

object StudyRepository {
    private val studyMap = mutableMapOf<String, DicomStudy>()

    fun setStudies(newStudies: List<DicomStudy>) {
        studyMap.clear()
        newStudies.forEach { study ->
            studyMap[study.studyUid] = study
        }
    }

    fun addStudy(study: DicomStudy) {
        studyMap[study.studyUid] = study
    }

    fun getStudyByUid(studyUid: String): DicomStudy? {
        return studyMap[studyUid]
    }

    // ★★★ 新增：为对比功能提供所有研究的列表 ★★★
    fun getAllStudies(): List<DicomStudy> {
        // Return a sorted list for a better UX in the selection dialog
        return studyMap.values.toList().sortedWith(compareBy({ it.patientName }, { it.studyDate }))
    }

    fun clear() {
        studyMap.clear()
    }
}

