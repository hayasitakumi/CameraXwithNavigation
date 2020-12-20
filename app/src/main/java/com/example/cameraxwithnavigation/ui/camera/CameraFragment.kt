package com.example.cameraxwithnavigation.ui.camera

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.cameraxwithnavigation.R
import kotlinx.android.synthetic.main.fragment_camera.*
import org.opencv.android.BaseLoaderCallback
import org.opencv.android.LoaderCallbackInterface
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class CameraFragment : Fragment() {

    private lateinit var cameraExecutor: ExecutorService

    lateinit var viewFinder: PreviewView

    private var matPrevious: Mat? = null


    init {
        System.loadLibrary("native-lib")
    }

    private var mLoaderCallback: BaseLoaderCallback? = null


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_camera, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mLoaderCallback = object : BaseLoaderCallback(requireContext()) {
            override fun onManagerConnected(status: Int) {
                when (status) {
                    LoaderCallbackInterface.SUCCESS -> startCamera()
                    else -> super.onManagerConnected(status)
                }
            }
        }
        viewFinder = view.findViewById(R.id.view_finder)
        cameraExecutor = Executors.newSingleThreadExecutor()
//        startCamera()
    }

    override fun onResume() {
        super.onResume()
        // 非同期でライブラリの読み込み/初期化を行う
        if (!OpenCVLoader.initDebug()) {
            //Log.d("onResume", "Internal OpenCV library not found. Using OpenCV Manager for initialization")
            OpenCVLoader.initAsync(
                OpenCVLoader.OPENCV_VERSION_3_0_0,
                requireContext(),
                mLoaderCallback
            )
        } else {
            //Log.d("onResume", "OpenCV library found inside package. Using it!")
            mLoaderCallback!!.onManagerConnected(LoaderCallbackInterface.SUCCESS)
        }
    }


    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.createSurfaceProvider())
                }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            val imageAnalysis = ImageAnalysis.Builder().build()
//            imageAnalysis.setAnalyzer(cameraExecutor, CBBImageAnalyzer())
            var Ret: IntArray
            imageAnalysis.setAnalyzer(cameraExecutor, { imageProxy ->
                /* Create cv::mat(RGB888) from image(NV21) */
                val matOrg: Mat = getMatFromImage(imageProxy)

                /* Fix image rotation (it looks image in PreviewView is automatically fixed by CameraX???) */
                val mat: Mat = fixMatRotation(matOrg)

                Log.i("develop_imageproxy", "[analyze] width = " + imageProxy.width + ", height = " + imageProxy.height + "Rotation = " + viewFinder.getDisplay().getRotation());
                Log.i("develop_imageproxy", "[analyze] mat width = " + matOrg.cols() + ", mat height = " + matOrg.rows());



                Ret = IntArray(10)
                val addr = mat.nativeObjAddr
                Log.d("recog_test", "mat=$mat , addr=$addr")

                /** native-lib.cpp読み込み*/
                recog(addr, Ret)

                Log.d("develop_ret", "Code=${Ret[1]}, Angle=${Ret[2]}")

                /* Convert cv::mat to bitmap for drawing */
                val bitmap: Bitmap =
                    Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(mat, bitmap)

                /* Display the result onto ImageView */
                requireActivity().runOnUiThread { image_view.setImageBitmap(bitmap) }

                imageProxy.close()
            })


            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }


    private fun getMatFromImage(image: ImageProxy): Mat {
        /* https://stackoverflow.com/questions/30510928/convert-android-camera2-api-yuv-420-888-to-rgb */
        val yBuffer: ByteBuffer = image.planes[0].buffer
        val uBuffer: ByteBuffer = image.planes[1].buffer
        val vBuffer: ByteBuffer = image.planes[2].buffer
        val ySize: Int = yBuffer.remaining()
        val uSize: Int = uBuffer.remaining()
        val vSize: Int = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuv = Mat(image.height + image.height / 2, image.width, CvType.CV_8UC1)
        yuv.put(0, 0, nv21)
        val mat = Mat()
        Imgproc.cvtColor(yuv, mat, Imgproc.COLOR_YUV2RGB_NV21, 3)
        return mat
    }

    private fun fixMatRotation(matOrg: Mat): Mat {
        var mat: Mat
        when (viewFinder.display.rotation) {
            Surface.ROTATION_0 -> {
                mat = Mat(matOrg.cols(), matOrg.rows(), matOrg.type())
                Core.transpose(matOrg, mat)
                Core.flip(mat, mat, 1)
            }
            Surface.ROTATION_90 -> mat = matOrg
            Surface.ROTATION_270 -> {
                mat = matOrg
                Core.flip(mat, mat, -1)
            }
            else -> {
                mat = Mat(matOrg.cols(), matOrg.rows(), matOrg.type())
                Core.transpose(matOrg, mat)
                Core.flip(mat, mat, 1)
            }
        }
        return mat
    }

    override fun onStop() {
        super.onStop()
        cameraExecutor.shutdown()

    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraX_Test"
    }

    //ここでnative-libの外身を定義する
    private external fun recog(imageAddr: Long, sample: IntArray?)
}