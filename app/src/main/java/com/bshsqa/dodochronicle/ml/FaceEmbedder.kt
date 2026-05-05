package com.bshsqa.dodochronicle.ml

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.face.Face
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

private const val MODEL_FILE = "mobile_face_net.tflite"
private const val INPUT_SIZE = 112
private const val EMBEDDING_SIZE = 192

fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    var dot = 0f; var normA = 0f; var normB = 0f
    for (i in a.indices) { dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i] }
    val denom = Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())
    return if (denom == 0.0) 0f else (dot / denom).toFloat()
}

@Singleton
class FaceEmbedder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var interpreter: Interpreter? = null

    init {
        try {
            interpreter = Interpreter(loadModelFile())
        } catch (e: Exception) {
            // mobile_face_net.tflite not present — embed() returns null
        }
    }

    @Synchronized
    fun embed(bitmap: Bitmap, face: Face): FloatArray? {
        val interp = interpreter ?: return null
        val crop = cropFace(bitmap, face) ?: return null
        return try {
            val input = preprocessBitmap(crop)
            val output = Array(1) { FloatArray(EMBEDDING_SIZE) }
            interp.run(input, output)
            normalize(output[0])
        } finally {
            crop.recycle()
        }
    }

    private fun cropFace(bitmap: Bitmap, face: Face): Bitmap? {
        val b = face.boundingBox
        val left = b.left.coerceAtLeast(0)
        val top = b.top.coerceAtLeast(0)
        val right = b.right.coerceAtMost(bitmap.width)
        val bottom = b.bottom.coerceAtMost(bitmap.height)
        if (right <= left || bottom <= top) return null
        return Bitmap.createScaledBitmap(
            Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top),
            INPUT_SIZE, INPUT_SIZE, true
        )
    }

    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
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
        return FileInputStream(fd.fileDescriptor).use { fis ->
            fis.channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    fun isAvailable() = interpreter != null
}
