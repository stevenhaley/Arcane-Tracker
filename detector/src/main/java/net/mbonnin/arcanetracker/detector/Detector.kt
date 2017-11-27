package net.mbonnin.arcanetracker.detector

import android.content.Context
import android.util.Log
import com.squareup.moshi.KotlinJsonAdapterFactory
import com.squareup.moshi.Moshi
import net.mbonnin.hsmodel.CardJson
import net.mbonnin.hsmodel.PlayerClass
import net.mbonnin.hsmodel.Type
import okio.Okio
import java.nio.ByteBuffer
import java.util.*
import kotlin.reflect.KFunction2

class ByteBufferImage(val w: Int,
                      val h: Int,
                      val buffer: ByteBuffer /* RGBA buffer */,
                      val stride: Int/* in bytes */)

class MatchResult {
    var bestIndex = 0
    var distance = 0.0
}

class ArenaResult(var cardId: String = "", var distance: Double = 0.0)

const val INDEX_UNKNOWN = -1
const val RANK_UNKNOWN = INDEX_UNKNOWN

const val FORMAT_UNKNOWN = INDEX_UNKNOWN
const val FORMAT_WILD = 0
const val FORMAT_STANDARD = 1

const val MODE_UNKNOWN = INDEX_UNKNOWN
private const val MODE_CASUAL_STANDARD = 0
private const val MODE_CASUAL_WILD = 1
private const val MODE_RANKED_STANDARD = 2
private const val MODE_RANKED_WILD = 3
const val MODE_CASUAL = 4
const val MODE_RANKED = 5

class Detector(var context: Context) {
    private val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

    private fun <T> decode(resourceName: String, type: java.lang.reflect.Type): T {
        val adapter = moshi.adapter<T>(type)
        val bufferedSource = Okio.buffer(Okio.source(CardJson::class.java.getResourceAsStream(resourceName)))
        return bufferedSource.use {
            adapter.fromJson(bufferedSource)
        }!! // <= not really sure if moshi can return null values since it usually throws exceptions
    }
    private val mappings = Array<List<Int>>(3, { listOf()})
    private val arena_rects by lazy {
        arrayOf(rectFactory!!.ARENA_MINIONS, rectFactory!!.ARENA_SPELLS, rectFactory!!.ARENA_WEAPONS)
    }

    var lastPlayerClass: String? = "?"
    val generatedData = decode<FormatModeRankData>("/format_mode_rank_data.json", FormatModeRankData::class.java)
    val arenaData = decode<ArenaData>("/arena_data.json", ArenaData::class.java)
    var rectFactory: RRectFactory? = null

    private fun ensureRectFactory(byteBufferImage: ByteBufferImage) {
        if (rectFactory == null) {
            rectFactory = RRectFactory(byteBufferImage.w, byteBufferImage.h, context)
        }
    }

    fun detectRank(byteBufferImage: ByteBufferImage): Int {
        ensureRectFactory(byteBufferImage)
        val matchResult = matchImage(byteBufferImage,
                rectFactory!!.RANK,
                Detector.Companion::extractHaar,
                Detector.Companion::euclidianDistance,
                generatedData.RANKS)

        if (matchResult.distance > 400) {
            matchResult.bestIndex = INDEX_UNKNOWN
        }

        //Log.d("Detector", "rank: " + matchResult.bestIndex + "(" + matchResult.distance +  ")")

        return matchResult.bestIndex;
    }

    fun detectFormat(byteBufferImage: ByteBufferImage): Int {
        ensureRectFactory(byteBufferImage)
        val matchResult = matchImage(byteBufferImage,
                rectFactory!!.FORMAT,
                Detector.Companion::extractHaar,
                Detector.Companion::euclidianDistance,
                generatedData.FORMATS)
        if (matchResult.distance > 400) {
            matchResult.bestIndex = INDEX_UNKNOWN
        }

        //Log.d("Detector", "format: " + matchResult.bestIndex + "(" + matchResult.distance +  ")")

        return matchResult.bestIndex;
    }

    fun detectMode(byteBufferImage: ByteBufferImage): Int {
        ensureRectFactory(byteBufferImage)
        val matchResult = matchImage(byteBufferImage,
                rectFactory!!.MODE,
                Detector.Companion::extractHaar,
                Detector.Companion::euclidianDistance,
                if (rectFactory!!.isTablet) generatedData.MODES_TABLET else generatedData.MODES)
        if (matchResult.distance > 400) {
            matchResult.bestIndex = INDEX_UNKNOWN
        }

        //Log.d("Detector", "mode: " + matchResult.bestIndex + "(" + matchResult.distance +  ")")

        when (matchResult.bestIndex) {
            MODE_CASUAL_STANDARD, MODE_CASUAL_WILD -> return MODE_CASUAL
            MODE_RANKED_STANDARD, MODE_RANKED_WILD -> return MODE_RANKED
            else -> return MODE_UNKNOWN
        }
    }

    fun detectArenaHaar(byteBufferImage: ByteBufferImage, hero: String?): Array<ArenaResult> {
        ensureRectFactory(byteBufferImage)
        return detectArena(byteBufferImage, Detector.Companion::extractHaar, Detector.Companion::euclidianDistance, arenaData.features, hero)
    }

    private fun getMapping(playerClass: String?, type: String): List<Int> {
        return CardJson.allCards().filter { it.scores != null }.mapIndexed { index, card ->
            if (playerClass != null
                    && !PlayerClass.NEUTRAL.equals(card.playerClass)
                    && playerClass != card.playerClass
                    && type != card.type) {
                // do not consider cards that are the wrong hero
                null
            } else {
                index
            }
        }.filterNotNull()
    }

    private fun <T> detectArena(byteBufferImage: ByteBufferImage, extractFeatures: KFunction2<ByteBufferImage, RRect, T>, computeDistance: KFunction2<T, T, Double>, candidates: List<T>, playerClass: String?): Array<ArenaResult> {
        val arenaResults = Array<ArenaResult>(3, {ArenaResult("", Double.MAX_VALUE)})

        if (playerClass != lastPlayerClass) {
            mappings[0] = getMapping(playerClass, Type.MINION)
            mappings[1] = getMapping(playerClass, Type.SPELL)
            mappings[2] = getMapping(playerClass, Type.WEAPON)

            lastPlayerClass = playerClass
        }

        for (type in 0 until mappings.size) {
            for (i in 0 until arenaResults.size) {
                val matchResult = matchImage(byteBufferImage,
                        arena_rects[type][i],
                        extractFeatures,
                        computeDistance,
                        candidates,
                        mappings[type])

                if (matchResult.distance < arenaResults[i].distance) {
                    arenaResults[i].distance = matchResult.distance
                    arenaResults[i].cardId = arenaData.ids[matchResult.bestIndex]
                }
            }
        }

        Log.d("Detector", arenaResults.map{ "[" + it.cardId + "(" + it.distance + ")]"}.joinToString { "," })
        return arenaResults
    }

    companion object {
        fun hammingDistance(a: Long, b: Long): Double {
            var dist = 0.0

            val c = a xor b
            for (i in 0 until 64) {
                if (c and 1L.shl(i) != 0L) {
                    dist++
                }
            }
            return dist
        }

        fun euclidianDistance(a: DoubleArray, b: DoubleArray): Double {
            var dist = 0.0
            for (i in 0 until a.size) {
                dist += (a[i] - b[i]) * (a[i] - b[i])
            }
            return dist
        }

        fun manhattanDistance(a: DoubleArray, b: DoubleArray): Double {
            var dist = 0.0
            for (i in 0 until a.size) {
                dist += Math.abs(a[i] - b[i])
            }
            return dist
        }

        fun extractHaar(byteBufferImage: ByteBufferImage, rrect: RRect): DoubleArray {
            return FeatureExtractor.INSTANCE.getFeatures(byteBufferImage.buffer, byteBufferImage.stride, rrect)
        }

        fun <T> matchImage(byteBufferImage: ByteBufferImage, rrect: RRect, extractFeatures: KFunction2<ByteBufferImage, RRect, T>, computeDistance: KFunction2<T, T, Double>, candidates: List<T>, mapping: List<Int>? = null): MatchResult {

            val vector = extractFeatures(byteBufferImage, rrect)

            val matchResult = MatchResult()

            matchResult.bestIndex = INDEX_UNKNOWN
            matchResult.distance = Double.MAX_VALUE

            if (mapping != null) {
                for (mapIndex in mapping) {
                    val dist = computeDistance(vector, candidates[mapIndex])
                    //Log.d("Detector", String.format("%d: %f", rank, dist))
                    if (dist < matchResult.distance) {
                        matchResult.distance = dist
                        matchResult.bestIndex = mapIndex
                    }
                }
            } else {
                candidates.forEachIndexed { index, candidate ->
                    val dist = computeDistance(vector, candidate)
                    //Log.d("Detector", String.format("%d: %f", rank, dist))
                    if (dist < matchResult.distance) {
                        matchResult.distance = dist
                        matchResult.bestIndex = index
                    }
                }
            }
            return matchResult
        }

        fun haarToString(features: DoubleArray): String {
            val sb= StringBuilder()

            sb.append("=[")
            sb.append(features.map {String.format("%3.2f", it)}.joinToString(" "))
            sb.append("]")
            return sb.toString()
        }

        fun formatString(format: Int): String {
            return when(format) {
                FORMAT_WILD -> "WILD"
                FORMAT_STANDARD -> "STANDARD"
                else -> "UNKNOWN"
            }
        }

        fun formatMode(mode: Int): String {
            return when(mode) {
                MODE_CASUAL -> "MODE_CASUAL"
                MODE_RANKED -> "MODE_RANKED"
                else -> "UNKNOWN"
            }
        }

        val NAME_TO_CARD_ID by lazy {
            val map = TreeMap<String, ArrayList<String>>()

            CardJson.allCards().filter { it.name != null }.forEach({
                val cardName = it.name!!
                        .toUpperCase()
                        .replace(" ", "_")
                        .replace(Regex("[^A-Z_]"), "")

                map.getOrPut(cardName, { ArrayList() }).add(it.id)
            })

            val nameToCardID = TreeMap<String, String>()

            for (entry in map) {
                entry.value.sort()
                for ((i, id) in entry.value.withIndex()) {
                    var name = entry.key
                    if (i > 0) {
                        name += i
                    }
                    nameToCardID.put(name, id)
                }
            }

            nameToCardID
        }
    }
}


