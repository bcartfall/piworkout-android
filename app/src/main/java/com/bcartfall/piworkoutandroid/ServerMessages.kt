package com.bcartfall.piworkoutandroid

import kotlinx.serialization.*

@Serializable
data class NamespaceMessage(
    val namespace: String,
)

@Serializable
data class InitMessage(
    val data: InitData,
)

@Serializable
data class InitData(
    val settings: Map<String, String>,
    val connected: Boolean,
    val videos: ArrayList<Video>,
    val player: PlayerData,
)

@Serializable
data class VideosMessage(
    val videos: ArrayList<Video>,
)

@Serializable
data class PlayerMessage(
    val player: PlayerData,
)

@Serializable
data class PlayerData(
    val time: Float,
    val videoId: Int,
    val status: Int,
    val client:String,
    val action:String,
)

@Serializable
data class PingMessage(
    val namespace: String,
    val uuid: String,
)