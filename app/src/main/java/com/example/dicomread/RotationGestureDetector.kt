package com.example.dicomread

import android.view.MotionEvent
import kotlin.math.atan2

class RotationGestureDetector(private val listener: OnRotationGestureListener?) {

    interface OnRotationGestureListener {
        fun onRotate(degrees: Float, focusX: Float, focusY: Float)
    }

    private var ptrID1: Int = INVALID_POINTER_ID
    private var ptrID2: Int = INVALID_POINTER_ID
    private var lastAngle: Float = 0f

    fun onTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> ptrID1 = event.getPointerId(event.actionIndex)
            MotionEvent.ACTION_POINTER_DOWN -> {
                ptrID2 = event.getPointerId(event.actionIndex)
                lastAngle = getAngle(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (ptrID1 != INVALID_POINTER_ID && ptrID2 != INVALID_POINTER_ID) {
                    val newAngle = getAngle(event)
                    val rotationDegrees = Math.toDegrees((newAngle - lastAngle).toDouble()).toFloat()
                    listener?.onRotate(rotationDegrees, getFocusX(event), getFocusY(event))
                    lastAngle = newAngle
                }
            }
            MotionEvent.ACTION_UP -> ptrID1 = INVALID_POINTER_ID
            MotionEvent.ACTION_POINTER_UP -> {
                val releasedPtrId = event.getPointerId(event.actionIndex)
                if (ptrID1 == releasedPtrId) ptrID1 = ptrID2
                ptrID2 = INVALID_POINTER_ID
            }
            MotionEvent.ACTION_CANCEL -> {
                ptrID1 = INVALID_POINTER_ID
                ptrID2 = INVALID_POINTER_ID
            }
        }
    }

    private fun getAngle(event: MotionEvent): Float {
        val idx1 = event.findPointerIndex(ptrID1)
        val idx2 = event.findPointerIndex(ptrID2)
        if (idx1 == -1 || idx2 == -1) return 0f
        val dx = event.getX(idx1) - event.getX(idx2)
        val dy = event.getY(idx1) - event.getY(idx2)
        return atan2(dy.toDouble(), dx.toDouble()).toFloat()
    }

    private fun getFocusX(event: MotionEvent): Float {
        val idx1 = event.findPointerIndex(ptrID1)
        val idx2 = event.findPointerIndex(ptrID2)
        if (idx1 == -1 || idx2 == -1) return 0f
        return (event.getX(idx1) + event.getX(idx2)) * 0.5f
    }

    private fun getFocusY(event: MotionEvent): Float {
        val idx1 = event.findPointerIndex(ptrID1)
        val idx2 = event.findPointerIndex(ptrID2)
        if (idx1 == -1 || idx2 == -1) return 0f
        return (event.getY(idx1) + event.getY(idx2)) * 0.5f
    }

    companion object {
        private const val INVALID_POINTER_ID = -1
    }
}
