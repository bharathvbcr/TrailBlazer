package com.example.trailblazer.ui.trips

import androidx.compose.ui.graphics.Path
import com.trailblazer.core.geo.TileUtils.GeometryType
import com.trailblazer.core.geo.TileUtils.VectorTile

/** How a vector feature is drawn. Features of one kind share one [Path] per tile. */
internal enum class VectorKind(val strokeWidth: Float) {
    Other(1.0f),
    Water(2.5f),
    Road(1.8f),
    ContourMinor(1.2f),
    ContourMajor(2.2f),
}

internal class KindPath(val kind: VectorKind, val path: Path)

/**
 * A vector tile ready to draw: lines of the same style merged into one path in tile-normalised coordinates (0..1 across
 * the tile), and point features as x,y pairs. Built once when the tile is loaded, off the main thread; drawing a frame
 * is then a transform and a handful of path draws instead of rebuilding every line of every visible tile.
 */
internal class PreparedVectorTile(
    val paths: List<KindPath>,
    /** Point features as normalised x,y pairs. */
    val points: FloatArray,
    /** Rough heap cost, for the cache budget. */
    val bytes: Long,
)

private fun kindOf(layerName: String, feature: com.trailblazer.core.geo.TileUtils.VectorFeature): VectorKind {
    val isWater = layerName.contains("water", ignoreCase = true)
    val isRoad = layerName.contains("road", ignoreCase = true) || layerName.contains("trail", ignoreCase = true)
    return when {
        feature.isContour -> {
            val ele = feature.elevationM
            if (ele != null && ele.toInt() % 100 == 0) VectorKind.ContourMajor else VectorKind.ContourMinor
        }
        isWater -> VectorKind.Water
        isRoad -> VectorKind.Road
        else -> VectorKind.Other
    }
}

internal fun prepareVectorTile(tile: VectorTile): PreparedVectorTile {
    val byKind = HashMap<VectorKind, Path>()
    val pointXY = ArrayList<Float>()
    var totalPoints = 0L
    for (layer in tile.layers) {
        for (feature in layer.features) {
            if (feature.type == GeometryType.Point) {
                for (part in feature.geometry) {
                    for (p in part) {
                        pointXY.add(p.x)
                        pointXY.add(p.y)
                        totalPoints++
                    }
                }
                continue
            }
            val kind = kindOf(layer.name, feature)
            for (line in feature.geometry) {
                if (line.size < 2) continue
                val path = byKind.getOrPut(kind) { Path() }
                path.moveTo(line[0].x, line[0].y)
                for (i in 1 until line.size) path.lineTo(line[i].x, line[i].y)
                totalPoints += line.size
            }
        }
    }
    val ordered = VectorKind.entries.mapNotNull { k -> byKind[k]?.let { KindPath(k, it) } }
    return PreparedVectorTile(ordered, pointXY.toFloatArray(), totalPoints * 16L + pointXY.size * 4L + 1024L)
}
