package com.musika.player.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Artist(
    val name: String,
    val albumCount: Int,
    val trackCount: Int,
    val folderPaths: List<String> = emptyList()
)
