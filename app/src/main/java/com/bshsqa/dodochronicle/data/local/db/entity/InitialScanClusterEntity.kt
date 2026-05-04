package com.bshsqa.dodochronicle.data.local.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "initial_scan_clusters",
    primaryKeys = ["sessionId", "clusterId"],
    indices = [Index("sessionId")]
)
data class InitialScanClusterEntity(
    val sessionId: String,
    val clusterId: Int,
    val embeddingSumJson: String,
    val count: Int,
    val representativeUrisJson: String,
    val updatedAt: Long
)
