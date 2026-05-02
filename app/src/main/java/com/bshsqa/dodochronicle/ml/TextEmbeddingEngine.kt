package com.bshsqa.dodochronicle.ml

import ai.djl.huggingface.tokenizers.Encoding
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import com.bshsqa.dodochronicle.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

data class EmbeddingSelfTestReport(
    val isReady: Boolean,
    val isHealthy: Boolean,
    val modelAssetPath: String,
    val dimension: Int,
    val relatedKoreanSimilarity: Float,
    val unrelatedKoreanSimilarity: Float,
    val koreanEnglishSimilarity: Float
)

@Singleton
class TextEmbeddingEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val initMutex = Mutex()
    private val inferenceMutex = Mutex()
    @Volatile private var initialized = false
    @Volatile private var tokenizer: HuggingFaceTokenizer? = null
    @Volatile private var environment: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var modelInputNames: Set<String> = emptySet()
    @Volatile private var modelOutputNames: Set<String> = emptySet()
    @Volatile private var selfTestLogged = false
    @Volatile private var lastSelfTestReport: EmbeddingSelfTestReport? = null

    suspend fun getEmbedding(text: String): FloatArray = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext floatArrayOf()
        initializeIfNeeded()

        val currentTokenizer = tokenizer ?: return@withContext floatArrayOf()
        val currentEnvironment = environment ?: return@withContext floatArrayOf()
        val currentSession = session ?: return@withContext floatArrayOf()

        inferenceMutex.withLock {
            try {
                val encoding = currentTokenizer.encode(text)
                runSession(currentEnvironment, currentSession, encoding)
            } catch (e: Exception) {
                Log.e("TextEmbeddingEngine", "Failed to get embedding", e)
                floatArrayOf()
            }
        }
    }

    suspend fun logSelfTestIfNeeded() {
        if (!BuildConfig.DEBUG || selfTestLogged) return
        initMutex.withLock {
            if (selfTestLogged) return
            val report = runSelfTest()
            Log.w(
                "TextEmbeddingEngine",
                "Self-test: ready=${report.isReady}, healthy=${report.isHealthy}, " +
                    "asset=${report.modelAssetPath}, dim=${report.dimension}, " +
                    "ko_related=${report.relatedKoreanSimilarity}, " +
                    "ko_unrelated=${report.unrelatedKoreanSimilarity}, " +
                    "ko_en=${report.koreanEnglishSimilarity}"
            )
            selfTestLogged = true
        }
    }

    suspend fun isHealthyForSemanticSearch(): Boolean {
        val report = lastSelfTestReport ?: runSelfTest()
        return report.isHealthy
    }

    suspend fun runSelfTest(): EmbeddingSelfTestReport {
        val koreanHospital = "아기가 열이 나서 병원에 갔다"
        val koreanHospitalSimilar = "열이 있어서 아이와 소아과에 다녀왔다"
        val koreanWalk = "유모차를 타고 공원 산책을 했다"
        val englishHospital = "The child went to the hospital with a fever"

        val hospitalEmbedding = getEmbedding(koreanHospital)
        val hospitalSimilarEmbedding = getEmbedding(koreanHospitalSimilar)
        val walkEmbedding = getEmbedding(koreanWalk)
        val englishHospitalEmbedding = getEmbedding(englishHospital)

        val relatedSimilarity = cosineSimilarity(hospitalEmbedding, hospitalSimilarEmbedding)
        val unrelatedSimilarity = cosineSimilarity(hospitalEmbedding, walkEmbedding)
        val koreanEnglishSimilarity = cosineSimilarity(hospitalEmbedding, englishHospitalEmbedding)
        val dimension = hospitalEmbedding.size
        val isReady = initialized && hospitalEmbedding.isNotEmpty()
        val isHealthy = isReady &&
            dimension >= 128 &&
            relatedSimilarity > unrelatedSimilarity + 0.05f &&
            unrelatedSimilarity < 0.95f

        return EmbeddingSelfTestReport(
            isReady = isReady,
            isHealthy = isHealthy,
            modelAssetPath = MODEL_ASSET_PATH,
            dimension = dimension,
            relatedKoreanSimilarity = relatedSimilarity,
            unrelatedKoreanSimilarity = unrelatedSimilarity,
            koreanEnglishSimilarity = koreanEnglishSimilarity
        ).also { lastSelfTestReport = it }
    }

    private suspend fun initializeIfNeeded() {
        if (initialized) return

        initMutex.withLock {
            if (initialized) return

            try {
                val assetDir = ensureMiniLmAssetsAvailable()
                val currentTokenizer = HuggingFaceTokenizer.newInstance(assetDir.toPath())
                val currentEnvironment = OrtEnvironment.getEnvironment()
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                val currentSession = currentEnvironment.createSession(
                    File(assetDir, MODEL_FILE_NAME).absolutePath,
                    sessionOptions
                )

                tokenizer = currentTokenizer
                environment = currentEnvironment
                session = currentSession
                modelInputNames = currentSession.inputNames
                modelOutputNames = currentSession.outputNames
                initialized = true

                if (BuildConfig.DEBUG) {
                    logModelIntrospection(currentSession)
                }
                Log.d("TextEmbeddingEngine", "ONNX TextEmbeddingEngine initialized successfully: $MODEL_ASSET_PATH")
            } catch (e: Exception) {
                Log.e("TextEmbeddingEngine", "Failed to initialize ONNX TextEmbeddingEngine", e)
            }
        }
    }

    private fun runSession(
        environment: OrtEnvironment,
        session: OrtSession,
        encoding: Encoding
    ): FloatArray {
        val inputIds = encoding.ids
        val attentionMask = encoding.attentionMask
        val typeIds = encoding.typeIds
        val sequenceLength = inputIds.size.toLong()
        val shape = longArrayOf(1L, sequenceLength)

        val tensors = linkedMapOf<String, OnnxTensor>()
        try {
            if (INPUT_IDS in modelInputNames) {
                tensors[INPUT_IDS] = OnnxTensor.createTensor(
                    environment,
                    LongBuffer.wrap(inputIds),
                    shape
                )
            }
            if (ATTENTION_MASK in modelInputNames) {
                tensors[ATTENTION_MASK] = OnnxTensor.createTensor(
                    environment,
                    LongBuffer.wrap(attentionMask),
                    shape
                )
            }
            if (TOKEN_TYPE_IDS in modelInputNames) {
                tensors[TOKEN_TYPE_IDS] = OnnxTensor.createTensor(
                    environment,
                    LongBuffer.wrap(typeIds),
                    shape
                )
            }

            session.run(tensors).use { result ->
                val outputTensor = selectOutputTensor(result)
                return extractSentenceEmbedding(outputTensor, attentionMask)
            }
        } finally {
            tensors.values.forEach { it.close() }
        }
    }

    private fun selectOutputTensor(result: OrtSession.Result): OnnxTensor {
        val firstTensor = result.firstOrNull()?.value as? OnnxTensor
        requireNotNull(firstTensor) { "No tensor outputs available from ONNX session" }
        return firstTensor
    }

    private fun extractSentenceEmbedding(outputTensor: OnnxTensor, attentionMask: LongArray): FloatArray {
        val tensorInfo = outputTensor.info as TensorInfo
        val shape = tensorInfo.shape
        require(tensorInfo.type == OnnxJavaType.FLOAT) {
            "Unsupported ONNX output type: ${tensorInfo.type}"
        }

        val floatValues = outputTensor.floatBuffer.apply { rewind() }
        val values = FloatArray(floatValues.remaining())
        floatValues.get(values)

        return when (shape.size) {
            2 -> l2Normalize(values)
            3 -> {
                val batch = shape[0].toInt()
                val sequenceLength = shape[1].toInt()
                val hiddenSize = shape[2].toInt()
                require(batch == 1) { "Only batch size 1 is supported, got $batch" }
                meanPool(values, attentionMask, sequenceLength, hiddenSize)
            }
            else -> throw IllegalStateException("Unsupported ONNX output shape: ${shape.contentToString()}")
        }
    }

    private fun meanPool(
        values: FloatArray,
        attentionMask: LongArray,
        sequenceLength: Int,
        hiddenSize: Int
    ): FloatArray {
        val pooled = FloatArray(hiddenSize)
        var tokenCount = 0

        for (tokenIndex in 0 until minOf(sequenceLength, attentionMask.size)) {
            if (attentionMask[tokenIndex] == 0L) continue
            val offset = tokenIndex * hiddenSize
            for (hiddenIndex in 0 until hiddenSize) {
                pooled[hiddenIndex] += values[offset + hiddenIndex]
            }
            tokenCount++
        }

        if (tokenCount == 0) return floatArrayOf()
        for (i in pooled.indices) {
            pooled[i] /= tokenCount.toFloat()
        }
        return l2Normalize(pooled)
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var norm = 0f
        for (value in vector) {
            norm += value * value
        }
        if (norm == 0f) return vector
        val scale = 1f / sqrt(norm)
        return FloatArray(vector.size) { index -> vector[index] * scale }
    }

    private fun ensureMiniLmAssetsAvailable(): File {
        val assetManager = context.assets
        val targetDir = File(context.filesDir, MODEL_DIR).apply { mkdirs() }
        val assetFiles = assetManager.list(MODEL_DIR)?.toList().orEmpty()

        for (fileName in assetFiles) {
            val targetFile = File(targetDir, fileName)
            if (targetFile.exists() && targetFile.length() > 0L) continue
            assetManager.open("$MODEL_DIR/$fileName").use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return targetDir
    }

    private fun logModelIntrospection(session: OrtSession) {
        Log.d(
            "TextEmbeddingEngine",
            "Model inputs=$modelInputNames outputs=$modelOutputNames"
        )
        session.inputInfo.forEach { (name, nodeInfo) ->
            Log.d("TextEmbeddingEngine", "Input[$name] ${describeNode(nodeInfo)}")
        }
        session.outputInfo.forEach { (name, nodeInfo) ->
            Log.d("TextEmbeddingEngine", "Output[$name] ${describeNode(nodeInfo)}")
        }
    }

    private fun describeNode(nodeInfo: NodeInfo): String {
        val info = nodeInfo.info
        return if (info is TensorInfo) {
            "type=${info.type}, shape=${info.shape.contentToString()}"
        } else {
            info.toString()
        }
    }

    companion object {
        private const val MODEL_DIR = "minilm"
        private const val MODEL_FILE_NAME = "model_qint8_arm64.onnx"
        const val MODEL_ASSET_PATH = "$MODEL_DIR/$MODEL_FILE_NAME"
        const val MODEL_VERSION = 11L

        private const val INPUT_IDS = "input_ids"
        private const val ATTENTION_MASK = "attention_mask"
        private const val TOKEN_TYPE_IDS = "token_type_ids"

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
