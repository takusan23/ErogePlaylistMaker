package data

import kotlinx.serialization.Serializable

/**
 * ローカルにある音楽ファイルから取り出す
 *
 * @param name 曲名
 * @param filePath ファイルパス、.m3u8 作成時に必要
 * @param year リリース年
 */
@Serializable
data class TrackData(
    val name: String,
    val filePath: String,
    val year: Long? = null
)