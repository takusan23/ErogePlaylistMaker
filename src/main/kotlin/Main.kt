import data.HihyoukuukanData
import data.TrackData
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.text.SimpleDateFormat

private val okHttpClient = OkHttpClient()

@OptIn(ExperimentalSerializationApi::class)
private val json = Json {
    explicitNulls = false
    encodeDefaults = true
}

/** 曲とパスが書かれた JSON */
private val musicListJsonFile = File("trackdata_list.json")
private val musicListJsonFileFromAndroid = File("android_trackdata_list.json")

/** 批評空間からとってきた結果 */
private val hihyoukuukanJsonFile = File("hihyoukuukan.json")

/** プレイリストフォルダ */
private val playlistFolder = File("playlist_folder").apply { mkdir() }

fun main(args: Array<String>) {
    generatePlaylistFromHihyoukuukanJson()
    // requestErogameScapeSql()
    // generateTrackDataListJson()
}

private fun generatePlaylistFromHihyoukuukanJson() {
    // 批評空間からとってきた結果と、曲一覧のJSONをパースする
    val hihyoukuukanDataList = json.decodeFromString<List<HihyoukuukanData>>(hihyoukuukanJsonFile.readText())
    val musicList = json.decodeFromString<List<TrackData>>(musicListJsonFile.readText())
    val musicListFromAndroid = json.decodeFromString<List<TrackData>>(musicListJsonFileFromAndroid.readText())

    // TrackData 修正するなら！
    // android 用に（ year 入れてないので PC のからパスだけ直す
    val fixedList = musicList.mapNotNull { trackData ->
        val androidPath = musicListFromAndroid.firstOrNull { android -> android.name == trackData.name } ?: return@mapNotNull null
        trackData.copy(filePath = androidPath.filePath)
    }

    // 年代別！
    // 21 番目が sellday
    val selldayList = hihyoukuukanDataList.groupBy { it.queryResult[21].split("-").first() }
    selldayList.forEach { (year, dataList) ->
        // ファイルパスを探す
        // 曲一覧 JSON にある
        val filePathList = dataList
            .distinctBy { hihyoukuukan -> hihyoukuukan.name }
            .mapNotNull { hihyoukuukan ->
                // 曲名と、あればリリース年を比較する。リリース年 null なら比較しない
                fixedList.firstOrNull { trackData ->
                    val isEquelsName = trackData.name == hihyoukuukan.name
                    val isEqualsYearOrTrue = if (trackData.year != null) trackData.year.toYearStringOrNull() == hihyoukuukan.queryResult[21].split("-").first() else true
                    isEquelsName && isEqualsYearOrTrue
                }?.filePath
            }
        // .m3u を作る
        val m3uText = "#EXTM3U8\n${filePathList.joinToString(separator = "\n")}"
        // 吐き出す
        playlistFolder.resolve("${year}年発売エロゲソング.m3u8").writeText(m3uText)
    }

    /*
        // ブランド別
        val brandList = hihyoukuukanDataList.groupBy { it.queryResult[96] }
        brandList.forEach { (brandName, dataList) ->
            // ファイルパスを探す
            // 曲一覧 JSON にある
            val filePathList = dataList
                .distinctBy { hihyoukuukan -> hihyoukuukan.name }
                .map { hihyoukuukan ->
                    musicList.firstOrNull { it.name == hihyoukuukan.name }?.filePath
                }
                .filterNotNull()
            // 10曲以上あれば（なんとなく
            if (filePathList.size >= 10) {
                // .m3u を作る
                val m3uText = "#EXTM3U8\n${filePathList.joinToString(separator = "\n")}"
                // 吐き出す
                playlistFolder.resolve("${brandName}の曲一覧.m3u8").writeText(m3uText)
            }
        }
    */

    /*
        // OP ED
        val musicCategory = hihyoukuukanDataList.groupBy { it.queryResult[15] }
        musicCategory.forEach { (category, dataList) ->
            // ファイルパスを探す
            // 曲一覧 JSON にある
            val filePathList = dataList
                .distinctBy { hihyoukuukan -> hihyoukuukan.name }
                .map { hihyoukuukan ->
                    musicList.firstOrNull { it.name == hihyoukuukan.name }?.filePath
                }
                .filterNotNull()
            // 10曲以上あれば（なんとなく
            if (filePathList.size >= 10) {
                // .m3u8 を作る
                val m3u8Text = "#EXTM3U8\n${filePathList.joinToString(separator = "\n")}"
                // 吐き出す
                playlistFolder.resolve("${category}曲一覧.m3u8").writeText(m3u8Text)
            }
        }
    */
}

/** 批評空間に SQL でリクエストする */
private fun requestErogameScapeSql() {

    // 曲名を入れる
    // PostgreSQL でエスケープ必須なものはエスケープしてね
    val musicList = """
        
    Pleasure garden
    奇跡メロディ
    
    """.trimIndent()
        .lines()
        .filter { it.isNotEmpty() }
        .joinToString(separator = ",") { name -> """'$name'""" }

    val sql = """
        SELECT ml.*,
            gm.*,
            gl.*,
            bl.*
        FROM musiclist ml
            INNER JOIN game_music gm ON ml.id = gm.music
            INNER JOIN gamelist gl ON gm.game = gl.id
            INNER JOIN brandlist bl ON gl.brandname = bl.id
        WHERE name in ( $musicList );
    """.trimIndent()

    // 批評空間に SQL を流す
    val formData = FormBody.Builder().apply {
        add("sql", sql)
    }.build()
    val request = Request.Builder().apply {
        url("https://erogamescape.dyndns.org/~ap2/ero/toukei_kaiseki/sql_for_erogamer_form.php")
        addHeader("User-Agent", "@takusan_23@diary.negitoro.dev")
        post(formData)
    }.build()

    val response = okHttpClient.newCall(request).execute()
    val html = response.body?.string()

    // 失敗時は例外
    if (!response.isSuccessful) {
        throw RuntimeException("リクエストに失敗しました")
    }

    // スクレイピングする
    val document = Jsoup.parse(html)
    val column = document
        .getElementsByTag("tr")
        .drop(1)
        .map { tr -> tr.getElementsByTag("td").map { it.text() } }

    // 前回のを足す
    val currentJson = hihyoukuukanJsonFile
        .takeIf { it.exists() }
        ?.readText()
        ?.takeIf { it.isNotEmpty() }
        ?.let { json.decodeFromString<List<HihyoukuukanData>>(it) } ?: emptyList()

    // 書き込む
    val jsonArray = currentJson + column.map { item -> HihyoukuukanData(item[1], item) }
    hihyoukuukanJsonFile.writeText(json.encodeToString(jsonArray))
}

/** trackdata_list.json をつくる */
private fun generateTrackDataListJson() {
    // タイトルに入っていれば消す
    // カラオケ版を消すため
    // タイトルに instrumental とか入っていれば
    val deleteFilterKeywordList = listOf(
        "instrumental",
        "off vocal",
        "karaoke",
        "inst"
    )

    // 例外にするフォルダ名
    val ignoreFolderNameList = listOf(
        "iTunesに自動的に追加"
    )

    // Music Center の tracks.db ファイルのパス
    val tracksDb = File("""C:\\Users\\takusan23\\Desktop\\Dev\\Kotlin\\ErogePlaylistMaker\\tracks.db""")

    // タイトルだけのが欲しい
    val titleList = File("""title_list.txt""")
    // 一応消しておく
    titleList.delete()
    musicListJsonFile.delete()

    // 正規表現で余計なかっこを消す
    val bracketRegex = """(\[.+?\]|\(.+?\))""".toRegex()
    val prefixNumberRegex = """^\d{2}""".toRegex()

    // data.TrackData の配列する
    val trackDataList = arrayListOf<TrackData>()

    // 1行ごとに JSON パーサーにかける
    tracksDb
        .readText()
        .lines()
        .filter { it.isNotEmpty() }
        .map { json.decodeFromString<JsonElement>(it) }
        // もし重複があれば消す
        // TODO 同じタイトルがあればやめる
        .distinctBy { jsonElement ->
            jsonElement.jsonObject["title"]?.jsonPrimitive?.content!!
        }
        // カラオケ版とかを消す
        .filter { jsonElement ->
            val title = jsonElement.jsonObject["title"]?.jsonPrimitive?.content!!
            deleteFilterKeywordList.none { keyword -> title.contains(keyword, ignoreCase = true) }
        }
        // 例外にするフォルダ名
        .filter { jsonElement ->
            val filePath = jsonElement.jsonObject["file"]?.jsonObject?.get("uri")?.jsonPrimitive?.content!!
            ignoreFolderNameList.none { filePath.contains(it, ignoreCase = true) }
        }
        // テキストに吐き出す
        // ここでも色々やる
        .forEach { jsonElement ->
            val title = jsonElement
                .jsonObject["title"]
                ?.jsonPrimitive
                ?.content!!
                // 正規表現
                // カッコが含まれている場合は消す
                .replace(bracketRegex, "")
                // 先頭に2桁の数字があれば消す
                .replace(prefixNumberRegex, "")
                // PostgreSQL で ' をエスケープする
                .replace("'", "''")
                // 余計な空白があれば消す
                .trim()
                .trimEnd()

            val filePath = jsonElement
                .jsonObject["file"]
                ?.jsonObject
                ?.get("uri")
                ?.jsonPrimitive
                ?.content!!

            // リリース年
            val sellDayYear = jsonElement
                .jsonObject["releaseDate"]
                ?.jsonObject
                ?.get("\$\$date")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.toLongOrNull()

            // JSON
            trackDataList += TrackData(title, filePath, sellDayYear)

            // タイトルだけ
            titleList.appendText(title)
            titleList.appendText("\r\n")
        }

    musicListJsonFile.writeText(json.encodeToString(trackDataList))
}

private val simpleDateFormat = SimpleDateFormat("yyyy")
private fun Long.toYearStringOrNull(): String? = simpleDateFormat.format(this)