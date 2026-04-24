package com.bshsqa.dodochronicle.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import com.google.mlkit.vision.face.Face
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.classifier.ImageClassifier
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

private const val MODEL_FILE = "mobile_face_net.tflite"
private const val INPUT_SIZE = 112
private const val EMBEDDING_SIZE = 128

fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    var dot = 0f; var normA = 0f; var normB = 0f
    for (i in a.indices) { dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i] }
    return if (normA == 0f || normB == 0f) 0f else dot / (Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())).toFloat()
}

@Singleton
class FaceEmbedder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var interpreter: org.tensorflow.lite.Interpreter? = null

    init {
        try {
            val model = loadModelFile()
            interpreter = org.tensorflow.lite.Interpreter(model)
        } catch (e: Exception) {
            // Model file not present — embedding will return null
        }
    }

    fun embed(bitmap: Bitmap, face: Face): FloatArray? {
        val interp = interpreter ?: return null
        val crop = cropFace(bitmap, face) ?: return null
        val input = preprocessBitmap(crop)
        val output = Array(1) { FloatArray(EMBEDDING_SIZE) }
        interp.run(input, output)
        return normalize(output[0])
    }

    private fun cropFace(bitmap: Bitmap, face: Face): Bitmap? {
        val bounds = face.boundingBox
        val left = bounds.left.coerceAtLeast(0)
        val top = bounds.top.coerceAtLeast(0)
        val right = bounds.right.coerceAtMost(bitmap.width)
        val bottom = bounds.bottom.coerceAtMost(bitmap.height)
        if (right <= left || bottom <= top) return null
        return Bitmap.createScaledBitmap(
            Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top),
            INPUT_SIZE, INPUT_SIZE, true
        )
    }

    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        buf.order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (pixel in pixels) {
            buf.putFloat(((pixel shr 16 and 0xFF) - 127.5f) / 127.5f)
            buf.putFloat(((pixel shr 8 and 0xFF) - 127.5f) / 127.5f)
            buf.putFloat(((pixel and 0xFF) - 127.5f) / 127.5f)
        }
        return buf
    }

    private fun normalize(v: FloatArray): FloatArray {
        val norm = Math.sqrt(v.sumOf { it.toDouble() * it }).toFloat().coerceAtLeast(1e-10f)
        return FloatArray(v.size) { v[it] / norm }
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fd = context.assets.openFd(MODEL_FILE)
        return FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    fun isAvailable() = interpreter != null
}
