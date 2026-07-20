package com.nacon01.kunekune

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

class RouteStore(context: Context) {
    private val externalFilesDir = context.applicationContext.getExternalFilesDir(null)

    private val routeFile: File
        get() = externalFilesDir?.resolve(FILE_NAME)
            ?: throw IOException("外部ファイル保存領域を利用できません")

    fun save(route: RecordedRoute) {
        val file = routeFile
        file.parentFile?.mkdirs()
        val temporaryFile = File(file.parentFile, "$FILE_NAME.tmp")
        temporaryFile.writeText(route.toJson().toString(), StandardCharsets.UTF_8)
        if (file.exists() && !file.delete()) {
            temporaryFile.delete()
            throw IOException("既存の経路ファイルを上書きできません")
        }
        if (!temporaryFile.renameTo(file)) {
            temporaryFile.delete()
            throw IOException("経路ファイルを保存できません")
        }
    }

    fun load(): RecordedRoute? {
        val file = routeFile
        if (!file.exists()) return null
        return try {
            JSONObject(file.readText(StandardCharsets.UTF_8)).toRoute()
        } catch (exception: Exception) {
            throw IOException("経路ファイルを読み込めません", exception)
        }
    }

    fun delete() {
        val file = routeFile
        if (file.exists() && !file.delete()) {
            throw IOException("経路ファイルを削除できません")
        }
    }

    private fun RecordedRoute.toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("metadata", JSONObject().apply {
            put("recordedAtEpochMillis", recordedAtEpochMillis)
            put("pointCount", points.size)
            put("totalDistanceMeters", totalDistanceMeters.toDouble())
        })
        put("points", JSONArray().apply {
            points.forEach { point ->
                put(JSONObject().apply {
                    put("x", point.x.toDouble())
                    put("y", point.y.toDouble())
                    put("z", point.z.toDouble())
                    put("elapsedMillis", point.elapsedMillis)
                })
            }
        })
    }

    private fun JSONObject.toRoute(): RecordedRoute {
        val metadata = getJSONObject("metadata")
        val jsonPoints = getJSONArray("points")
        val points = buildList(jsonPoints.length()) {
            for (index in 0 until jsonPoints.length()) {
                val point = jsonPoints.getJSONObject(index)
                add(
                    RoutePoint(
                        x = point.getDouble("x").toFloat(),
                        y = point.getDouble("y").toFloat(),
                        z = point.getDouble("z").toFloat(),
                        elapsedMillis = point.getLong("elapsedMillis")
                    )
                )
            }
        }
        return RecordedRoute(
            recordedAtEpochMillis = metadata.getLong("recordedAtEpochMillis"),
            points = points,
            totalDistanceMeters = metadata.getDouble("totalDistanceMeters").toFloat()
        )
    }

    companion object {
        private const val FILE_NAME = "route.json"
    }
}
