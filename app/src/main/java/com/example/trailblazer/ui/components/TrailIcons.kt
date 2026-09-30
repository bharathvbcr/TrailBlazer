package com.example.trailblazer.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's line-icon set, drawn on a 24-unit grid with round 1.8-unit strokes. Kept in-app so no
 * icon library is needed; `Icon(tint = …)` recolours them like any Material icon.
 */
object TrailIcons {
    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

    private fun icon(name: String, stroke: String, fill: String? = null): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            if (fill != null) addPath(pathData = addPathNodes(fill), fill = SolidColor(Color.Black))
            addPath(
                pathData = addPathNodes(stroke),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    val Compass = icon("compass", circle(12f, 12f, 9f), "M12,5L14.5,12L12,12Z M12,19L9.5,12L12,12Z")
    val Sun = icon("sun", circle(12f, 12f, 4f) + "M12,2V4M12,20V22M2,12H4M20,12H22M4.9,4.9L6.3,6.3M17.7,17.7L19.1,19.1M4.9,19.1L6.3,17.7M17.7,6.3L19.1,4.9")
    val Moon = icon("moon", "M20,14.5A8,8 0 1,1 9.5,4A6.5,6.5 0 0,0 20,14.5Z")
    val Route = icon("route", circle(6f, 19f, 2f) + circle(18f, 5f, 2f) + "M8,19H15.5A3.5,3.5 0 0,0 15.5,12H8.5A3.5,3.5 0 0,1 8.5,5H16")
    val Tools = icon("tools", "M4,4H10V10H4Z M14,4H20V10H14Z M4,14H10V20H4Z M14,14H20V20H14Z")
    val Settings = icon("settings", circle(12f, 12f, 3f) + circle(12f, 12f, 7f) + "M12,2V5M12,19V22M2,12H5M19,12H22M4.9,4.9L7,7M17,17L19.1,19.1M4.9,19.1L7,17M17,7L19.1,4.9")
    val Pin = icon("pin", "M12,21C12,21 5,14 5,9A7,7 0 0,1 19,9C19,14 12,21 12,21Z" + circle(12f, 9f, 2.5f))
    val MyLocation = icon("my_location", circle(12f, 12f, 7f) + "M12,2V5M12,19V22M2,12H5M19,12H22", circle(12f, 12f, 2.5f))
    val Navigate = icon("navigate", "M12,3L19,20L12,16L5,20Z")
    val Camera = icon("camera", "M4,7H20V20H4Z M9,7L10.5,4.5H13.5L15,7" + circle(12f, 13.5f, 3.5f))
    val Share = icon("share", circle(18f, 5f, 2.5f) + circle(6f, 12f, 2.5f) + circle(18f, 19f, 2.5f) + "M8.2,10.8L15.8,6.2M8.2,13.2L15.8,17.8")
    val OpenExternal = icon("open_external", "M14,4H20V10M20,4L11,13M18,14V19A1,1 0 0,1 17,20H5A1,1 0 0,1 4,19V7A1,1 0 0,1 5,6H10")
    val Delete = icon("delete", "M5,7H19M9,7V4H15V7M7,7L8,20H16L17,7M10,11V16M14,11V16")
    val Edit = icon("edit", "M4,20V16L15,5L19,9L8,20Z M13,7L17,11")
    val Play = icon("play", "M8,5L19,12L8,19Z", "M8,5L19,12L8,19Z")
    val Pause = icon("pause", "M9,5V19M15,5V19")
    val Stop = icon("stop", "M6,6H18V18H6Z", "M6,6H18V18H6Z")
    val Record = icon("record", circle(12f, 12f, 7f), circle(12f, 12f, 7f))
    val Import = icon("import", "M12,4V15M7,10L12,15L17,10M5,17V20H19V17")
    val Export = icon("export", "M12,15V4M7,9L12,4L17,9M5,17V20H19V17")
    val Close = icon("close", "M6,6L18,18M18,6L6,18")
    val Back = icon("back", "M19,12H5M11,6L5,12L11,18")
    val Torch = icon("torch", "M8,3H16V7L14,10V21H10V10L8,7Z M12,13V15")
    val Whistle = icon("whistle", "M4,10V14H7L12,18V6L7,10Z M15.5,9A4,4 0 0,1 15.5,15 M18,6.5A7.5,7.5 0 0,1 18,17.5")
    val Level = icon("level", "M2,9H22V15H2Z M8,9V15M16,9V15" + circle(12f, 12f, 1.5f))
    val Mountain = icon("mountain", "M3,19L9,9L13,15L16,11L21,19Z")
    val Sensors = icon("sensors", circle(12f, 12f, 2f) + "M7.8,7.8A6,6 0 0,0 7.8,16.2M16.2,7.8A6,6 0 0,1 16.2,16.2M4.9,4.9A10,10 0 0,0 4.9,19.1M19.1,4.9A10,10 0 0,1 19.1,19.1")
    val Mic = icon("mic", "M9,6A3,3 0 0,1 15,6V11A3,3 0 0,1 9,11Z M5,11A7,7 0 0,0 19,11M12,18V21")
    val Refresh = icon("refresh", "M20,12A8,8 0 1,1 17.66,6.34M20,4V8H16")
    val Info = icon("info", circle(12f, 12f, 9f) + "M12,11V17M12,7.5V7.6")
    val Warning = icon("warning", "M12,3L22,20H2Z M12,10V14M12,17V17.1")
    val Check = icon("check", "M5,12L10,17L19,7")
    val Add = icon("add", "M12,5V19M5,12H19")
    val Up = icon("up", "M12,19V5M6,11L12,5L18,11")
    val Down = icon("down", "M12,5V19M6,13L12,19L18,13")
    val Copy = icon("copy", "M8,8H20V20H8Z M4,16V4H16")
    val Search = icon("search", circle(10.5f, 10.5f, 6.5f) + "M15.5,15.5L21,21")
    val Flag = icon("flag", "M6,21V4M6,4H17L15,8L17,12H6")
    val Speed = icon("speed", "M4,16A8,8 0 1,1 20,16M12,16L16,10")
    val Doc = icon("doc", "M7,3H14L19,8V21H7Z M14,3V8H19M10,13H16M10,17H16")
    val Chevron = icon("chevron", "M9,6L15,12L9,18")
    val More = icon("more", circle(5f, 12f, 1.2f) + circle(12f, 12f, 1.2f) + circle(19f, 12f, 1.2f))
    val Star = icon("star", "M12,3L14.6,9.2L21,9.7L16.1,13.9L17.6,20.3L12,16.9L6.4,20.3L7.9,13.9L3,9.7L9.4,9.2Z")
    val Telescope = icon("telescope", "M3,13L17,6L19,10L5,17Z M11,14L8,21M12,14L15,21M17,6L20,4.5L22,8.5L19,10")
    val Waves = icon("waves", "M3,8C5,6 7,6 9,8S13,10 15,8S19,6 21,8M3,13C5,11 7,11 9,13S13,15 15,13S19,11 21,13M3,18C5,16 7,16 9,18S13,20 15,18S19,16 21,18")
}
