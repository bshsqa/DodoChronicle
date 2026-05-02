package com.bshsqa.dodochronicle.ml

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder.TextEmbedderOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class TextEmbeddingEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var embedder: TextEmbedder? = null

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("universal_sentence_encoder.tflite")
                .build()
            val options = TextEmbedderOptions.builder()
                .setBaseOptions(baseOptions)
                .build()
            embedder = TextEmbedder.createFromOptions(context, options)
            Log.d("TextEmbeddingEngine", "TextEmbedder initialized successfully")
        } catch (e: Exception) {
            Log.e("TextEmbeddingEngine", "Failed to initialize TextEmbedder", e)
        }
    }

    suspend fun getEmbedding(text: String): FloatArray = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext floatArrayOf()
        
        try {
            val result = embedder?.embed(text)
            val embedding = result?.embeddingResult()?.embeddings()?.firstOrNull()
            val floatList = embedding?.floatEmbedding() // returns List<Float>
            floatList?.let { list ->
                FloatArray(list.size) { i -> list[i] }
            } ?: floatArrayOf()
        } catch (e: Exception) {
            Log.e("TextEmbeddingEngine", "Failed to get embedding", e)
            floatArrayOf()
        }
    }

    companion object {
        fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
            if (v1.isEmpty() || v2.isEmpty() || v1.size != v2.size) return 0f
            var dotProduct = 0f
            var norm1 = 0f
            var norm2 = 0f
            for (i in v1.indices) {
                dotProduct += v1[i] * v2[i]
                norm1 += v1[i] * v1[i]
                norm2 += v2[i] * v2[i]
            }
            if (norm1 == 0f || norm2 == 0f) return 0f
            return dotProduct / (sqrt(norm1) * sqrt(norm2))
        }
    }
}
