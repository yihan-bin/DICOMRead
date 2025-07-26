package com.example.dicomread

import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ModelRenderer : GLSurfaceView.Renderer {

    // JNI 接口 - 与native-lib.cpp保持一致
    private external fun nativeOnSurfaceCreated()
    private external fun nativeOnSurfaceChanged(width: Int, height: Int)
    private external fun nativeOnDrawFrame(handle: Long, mvpMatrix: FloatArray, mvMatrix: FloatArray, modelMatrix: FloatArray, alpha: Float)
    private external fun nativeReleaseGpuResources(handle: Long)

    // 矩阵
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val mvMatrix = FloatArray(16)

    // 用户交互参数
    @Volatile var angleX: Float = 0f
    @Volatile var angleY: Float = 0f
    @Volatile var scaleFactor: Float = 1.0f
    @Volatile private var alphaMultiplier: Float = 1.0f

    // ★★★ 新增: 平移参数 ★★★
    @Volatile private var translationX: Float = 0f
    @Volatile private var translationY: Float = 0f

    private var modelHandle: Long = 0L
    @Volatile private var surfaceCreated = false

    fun setModel(handle: Long) {
        if (this.modelHandle != 0L && this.modelHandle != handle && surfaceCreated) {
            nativeReleaseGpuResources(this.modelHandle)
        }
        this.modelHandle = handle
    }

    fun setAlphaMultiplier(alpha: Float) {
        this.alphaMultiplier = alpha.coerceIn(0f, 1f)
    }

    // ★★★ 新增: 设置平移 (相对移动) ★★★
    fun pan(dx: Float, dy: Float) {
        this.translationX += dx
        this.translationY += dy
    }

    // ★★★ 新增: 设置平移 (绝对位置) ★★★
    fun setTranslation(tx: Float, ty: Float) {
        this.translationX = tx
        this.translationY = ty
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        surfaceCreated = true
        nativeOnSurfaceCreated()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        nativeOnSurfaceChanged(width, height)
        val ratio: Float = if (height > 0) width.toFloat() / height.toFloat() else 1f
        // 调整近平面和远平面以更好地适应归一化后的模型
        Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 1.5f, 50f)
    }

    override fun onDrawFrame(gl: GL10?) {
        // ★★★ 修改: 调整相机位置以适应归一化后的模型 ★★★
        Matrix.setLookAtM(viewMatrix, 0,
            0f, 0f, 3f,    // 眼睛位置 Z=3, 观察归一化到[-1,1]的模型
            0f, 0f, 0f,    // 看向原点
            0f, 1.0f, 0.0f // 上方向
        )

        // 构建模型矩阵: T * R * S
        Matrix.setIdentityM(modelMatrix, 0)
        // 1. 平移
        Matrix.translateM(modelMatrix, 0, translationX, translationY, 0f)
        // 2. 旋转
        Matrix.rotateM(modelMatrix, 0, angleX, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, angleY, 0f, 1f, 0f)
        // 3. 缩放
        Matrix.scaleM(modelMatrix, 0, scaleFactor, scaleFactor, scaleFactor)

        // 计算 ModelView 和 ModelViewProjection 矩阵
        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)

        nativeOnDrawFrame(modelHandle, mvpMatrix, mvMatrix, modelMatrix, alphaMultiplier)
    }

    fun releaseAllGpuResources() {
        if (modelHandle != 0L && surfaceCreated) {
            nativeReleaseGpuResources(modelHandle)
            modelHandle = 0L
        }
    }
}
