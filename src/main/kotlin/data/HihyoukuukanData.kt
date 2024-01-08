package data

import kotlinx.serialization.Serializable

/**
 * 批評空間で SQL 叩いた結果
 *
 * @param name 曲名
 * @param queryResult とってきたカラム
 */
@Serializable
data class HihyoukuukanData(
    val name: String,
    val queryResult: List<String>
)