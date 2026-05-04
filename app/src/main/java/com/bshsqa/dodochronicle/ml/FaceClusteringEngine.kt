package com.bshsqa.dodochronicle.ml

import javax.inject.Inject
import javax.inject.Singleton

data class FaceCluster(
    val id: Int,
    val embeddings: List<FloatArray>,
    val representativeUris: List<String>
) {
    val averageEmbedding: FloatArray
        get() {
            if (embeddings.isEmpty()) return floatArrayOf()
            val size = embeddings[0].size
            val avg = FloatArray(size)
            for (e in embeddings) for (i in e.indices) avg[i] += e[i]
            val n = embeddings.size.toFloat()
            return FloatArray(size) { avg[it] / n }
        }
}

data class PhotoEmbedding(val uri: String, val takenAt: Long, val embedding: FloatArray)

private const val CLUSTER_THRESHOLD = 0.68f

@Singleton
class FaceClusteringEngine @Inject constructor() {

    fun cluster(photos: List<PhotoEmbedding>): List<FaceCluster> {
        if (photos.isEmpty()) return emptyList()

        val assignments = IntArray(photos.size) { -1 }
        var nextClusterId = 0
        val clusterData = mutableMapOf<Int, MutableList<Int>>()

        for (i in photos.indices) {
            var bestCluster = -1
            var bestSim = CLUSTER_THRESHOLD

            for ((clusterId, members) in clusterData) {
                val centroid = centroidOf(members.map { photos[it].embedding })
                val sim = cosineSimilarity(centroid, photos[i].embedding)
                if (sim > bestSim) {
                    bestSim = sim
                    bestCluster = clusterId
                }
            }

            if (bestCluster == -1) {
                bestCluster = nextClusterId++
                clusterData[bestCluster] = mutableListOf()
            }
            assignments[i] = bestCluster
            clusterData[bestCluster]!!.add(i)
        }

        return clusterData.map { (id, members) ->
            FaceCluster(
                id = id,
                embeddings = members.map { photos[it].embedding },
                representativeUris = members.take(9).map { photos[it].uri }
            )
        }.sortedByDescending { it.embeddings.size }
    }

    private fun centroidOf(embeddings: List<FloatArray>): FloatArray {
        if (embeddings.isEmpty()) return floatArrayOf()
        val size = embeddings[0].size
        val sum = FloatArray(size)
        for (e in embeddings) for (i in e.indices) sum[i] += e[i]
        val n = embeddings.size.toFloat()
        return FloatArray(size) { sum[it] / n }
    }
}
