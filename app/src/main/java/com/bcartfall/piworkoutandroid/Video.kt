package com.bcartfall.piworkoutandroid

import kotlinx.serialization.*

@Serializable
data class Video(
    val id: Int = 0,
    val order: Int = 0,
    val videoId: String = "",
    val source: String = "",
    val url: String = "",
    val filename: String = "",
    val filesize: Long = 0,
    val title: String = "",
    val description: String = "",
    val duration: Int = 0,
    val position: Float = 0f,
    val width: Int = 0,
    val height: Int = 0,
    val tbr: Int = 0,
    val fps: Int = 0,
    val vcodec: String = "",
    val status: Int = 0,
)