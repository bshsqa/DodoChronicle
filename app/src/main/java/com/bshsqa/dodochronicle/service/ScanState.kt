package com.bshsqa.dodochronicle.service

import com.bshsqa.dodochronicle.ml.FaceCluster
import com.bshsqa.dodochronicle.ml.PhotoEmbedding

sealed class ScanState {
    object Idle : ScanState()
    data class Running(
        val processed: Int,
        val total: Int,
        val elapsedSeconds: Long = 0L,
        val sessionId: String = ""
    ) : ScanState()
    data class Done(
        val clusters: List<FaceCluster>,
        val embeddings: List<PhotoEmbedding>,
        val elapsedSeconds: Long = 0L,
        val sessionId: String = ""
    ) : ScanState()
    data class Failed(val message: String) : ScanState()
    object Cancelled : ScanState()
}
