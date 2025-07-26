package com.example.dicomread

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.dicomread.databinding.ActivityViewer3dBinding
import kotlin.math.sqrt

class Viewer3DActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_MODEL_HANDLE = "model_handle"
        private const val EXTRA_PHYSICAL_SIZE = "physical_size" // ★★★ 新增：传递物理尺寸信息 ★★★

        fun newIntent(context: Context, modelHandle: Long, physicalSizeMm: Float = 200f): Intent {
            return Intent(context, Viewer3DActivity::class.java).apply {
                putExtra(EXTRA_MODEL_HANDLE, modelHandle)
                putExtra(EXTRA_PHYSICAL_SIZE, physicalSizeMm)
            }
        }
    }

    private lateinit var binding: ActivityViewer3dBinding
    private lateinit var renderer: ModelRenderer

    private lateinit var gestureDetector: GestureDetector
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private var previousX: Float = 0f
    private var previousY: Float = 0f
    private var modelHandle: Long = 0L
    private var physicalSizeMm: Float = 200f // ★★★ 新增：存储物理尺寸 ★★★

    // ★★★ 新增：手势状态管理，改进交互体验 ★★★
    private var isScaling = false
    private var isPanning = false
    private var panStartDistance = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewer3dBinding.inflate(layoutInflater)
        setContentView(binding.root)

        modelHandle = intent.getLongExtra(EXTRA_MODEL_HANDLE, 0L)
        physicalSizeMm = intent.getFloatExtra(EXTRA_PHYSICAL_SIZE, 200f)

        if (modelHandle == 0L) {
            Toast.makeText(this, "错误: 无效的模型句柄。", Toast.LENGTH_LONG).show()
            finish(); return
        }

        renderer = ModelRenderer()

        binding.glSurfaceView.apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        setupGestureDetectors()
        setupUIListeners()

        binding.glSurfaceView.queueEvent {
            renderer.setModel(modelHandle)
            setBestInitialView()
            updateScaleBar() // ★★★ 初始化比例尺 ★★★
            binding.glSurfaceView.requestRender()
        }
    }

    private fun setBestInitialView() {
        renderer.angleX = -60f
        renderer.angleY = 20f
        renderer.scaleFactor = 1.0f
        renderer.setTranslation(0f, 0f)
    }

    // ★★★ 新增：更新比例尺显示 ★★★
    private fun updateScaleBar() {
        runOnUiThread {
            binding.scaleBarView.update(physicalSizeMm, renderer.scaleFactor)
        }
    }

    // ★★★ 修改：优化手势检测逻辑，提供更好的单指/双指区分 ★★★
    private fun setupGestureDetectors() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                setBestInitialView()
                updateScaleBar()
                binding.glSurfaceView.requestRender()
                Toast.makeText(this@Viewer3DActivity, "视图已重置", Toast.LENGTH_SHORT).show()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                // 只有在单指且不在缩放状态下才执行旋转
                if (e2.pointerCount == 1 && !isScaling && !isPanning) {
                    renderer.angleY -= distanceX * 0.4f
                    renderer.angleX -= distanceY * 0.4f
                    binding.glSurfaceView.requestRender()
                    return true
                }
                return false
            }
        })

        scaleGestureDetector = ScaleGestureDetector(this, object: ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                renderer.scaleFactor *= detector.scaleFactor
                renderer.scaleFactor = renderer.scaleFactor.coerceIn(0.1f, 10.0f)
                updateScaleBar() // ★★★ 缩放时更新比例尺 ★★★
                binding.glSurfaceView.requestRender()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUIListeners() {
        binding.glSurfaceView.setOnTouchListener { _, event ->
            var handled = scaleGestureDetector.onTouchEvent(event)

            // ★★★ 改进：双指平移逻辑，更智能的手势识别 ★★★
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2 && !isScaling) {
                        // 计算两指距离，如果距离变化不大，则认为是平移手势
                        val dx = event.getX(0) - event.getX(1)
                        val dy = event.getY(0) - event.getY(1)
                        panStartDistance = sqrt(dx * dx + dy * dy)
                        previousX = (event.getX(0) + event.getX(1)) / 2f
                        previousY = (event.getY(0) + event.getY(1)) / 2f
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 2 && !isScaling) {
                        val dx = event.getX(0) - event.getX(1)
                        val dy = event.getY(0) - event.getY(1)
                        val currentDistance = sqrt(dx * dx + dy * dy)

                        // 如果两指距离变化不大（阈值50像素），则认为是平移
                        if (kotlin.math.abs(currentDistance - panStartDistance) < 50f) {
                            if (!isPanning) isPanning = true

                            val centerX = (event.getX(0) + event.getX(1)) / 2f
                            val centerY = (event.getY(0) + event.getY(1)) / 2f
                            val deltaX = centerX - previousX
                            val deltaY = centerY - previousY

                            val viewWidth = binding.glSurfaceView.width
                            val moveFactor = 2.0f / viewWidth * renderer.scaleFactor
                            renderer.pan(deltaX * moveFactor, -deltaY * moveFactor)

                            previousX = centerX
                            previousY = centerY
                            binding.glSurfaceView.requestRender()
                            handled = true
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isPanning = false
                    panStartDistance = 0f
                }
            }

            // 只有在不进行缩放和平移时才处理单指旋转
            if (!isScaling && !isPanning) {
                handled = gestureDetector.onTouchEvent(event) || handled
            }

            handled
        }

        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.sliderAlpha.addOnChangeListener { _, value, _ ->
            renderer.setAlphaMultiplier(value)
            binding.glSurfaceView.requestRender()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.glSurfaceView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && modelHandle != 0L) {
            binding.glSurfaceView.queueEvent { renderer.releaseAllGpuResources() }
            DicomModule.release3DModel(modelHandle)
            modelHandle = 0L
            Log.d("Viewer3DActivity", "All 3D resources released.")
        }
    }
}
