package com.example.trailblazer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The offline map promises "zero network telemetry". This guards it at the source: none of the code that imports,
 * decodes or draws a map archive (nor the shared decoders it calls) may reference a network API, a URL, or a library
 * that fetches things, and the build must not depend on an HTTP/image-loading library at all (weather uses the plain
 * JDK, which is outside the map path and checked separately below).
 */
class MapPathIsOfflineTest {
    // Gradle runs unit tests with the module directory (app/) as the working directory.
    private val appRoot = File(".").canonicalFile
    private val repoRoot = appRoot.parentFile

    private val mapSources = listOf(
        "app/src/main/java/com/example/trailblazer/data/OfflineMapManager.kt",
        "app/src/main/java/com/example/trailblazer/ui/trips/OfflineMapCanvas.kt",
        "app/src/main/java/com/example/trailblazer/ui/trips/TileLoader.kt",
        "core/src/commonMain/kotlin/com/trailblazer/core/geo/TileUtils.kt",
        "core/src/commonMain/kotlin/com/trailblazer/core/geo/PmtilesArchive.kt",
        "core/src/androidMain/kotlin/com/trailblazer/core/io/PlatformIo.kt",
        "core/src/jvmMain/kotlin/com/trailblazer/core/io/PlatformIo.kt",
    )

    private val forbidden = listOf(
        "java.net", "javax.net", "HttpURLConnection", "HttpsURLConnection", "URL(", "URI(", "okhttp", "ktor", "coil", "retrofit",
        "glide", "volley", "android.webkit", "WebView", "DownloadManager", "Socket", "InetAddress", "http://", "https://",
        "ConnectivityManager", "NetworkRequest", "TrafficStats", "openConnection",
    )

    private fun stripComments(src: String): String =
        src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ").lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun theMapCodeNamesNoNetworkApi() {
        for (rel in mapSources) {
            val f = File(repoRoot, rel)
            assertTrue("$rel must exist (is the test running from app/?)", f.isFile && f.length() > 100)
            val code = stripComments(f.readText())
            for (token in forbidden) {
                assertTrue("$rel mentions \"$token\"", !code.contains(token, ignoreCase = token.length > 3 && token[0].isLowerCase()))
            }
        }
    }

    @Test
    fun theMapCodeImportsOnlyAllowedPackages() {
        val allowedPrefixes = listOf(
            "android.", "androidx.", "com.example.trailblazer.", "com.trailblazer.core.", "kotlin.", "kotlinx.coroutines.", "java.io.",
            "java.util.", "java.security.",
        )
        for (rel in mapSources) {
            val imports = File(repoRoot, rel).readLines().filter { it.startsWith("import ") }.map { it.removePrefix("import ").trim() }
            val unexpected = imports.filter { imp -> allowedPrefixes.none { imp.startsWith(it) } }
            assertEquals("$rel imports", emptyList<String>(), unexpected)
        }
    }

    @Test
    fun theBuildCarriesNoHttpOrImageLoadingLibrary() {
        val build = listOf("gradle/libs.versions.toml", "app/build.gradle.kts", "core/build.gradle.kts")
            .map { File(repoRoot, it) }
        for (f in build) assertTrue("${f.path} must exist", f.isFile)
        val text = build.joinToString("\n") { it.readText() }.lowercase()
        for (lib in listOf("okhttp", "ktor", "coil", "retrofit", "glide", "volley", "picasso", "fresco", "maplibre", "osmdroid", "mapbox")) {
            assertTrue("a $lib dependency would put a network loader on the map path", !text.contains(lib))
        }
    }

    @Test
    fun noTileUrlTemplateExistsAnywhereInTheAppSources() {
        val roots = listOf(File(repoRoot, "app/src/main"), File(repoRoot, "core/src"))
        val hits = roots.flatMap { r -> r.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.toList() }
            .filter { f -> Regex("""https?://[^"\s]*\{[zxy]\}""").containsMatchIn(f.readText()) }
            .map { it.path }
        assertEquals(emptyList<String>(), hits)
    }
}
