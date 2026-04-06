package dev.guilherme.nmsmcp.snippets

import dev.guilherme.nmsmcp.config.AppConfig
import dev.guilherme.nmsmcp.model.IndexedSymbol
import dev.guilherme.nmsmcp.model.MappingNamespace
import dev.guilherme.nmsmcp.model.SnippetResult
import dev.guilherme.nmsmcp.model.SymbolKind
import me.kcra.takenaka.core.Version
import me.kcra.takenaka.core.versionAttributesOf
import net.fabricmc.mappingio.tree.MappingTree
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import net.fabricmc.tinyremapper.TinyUtils
import org.benf.cfr.reader.api.CfrDriver
import org.benf.cfr.reader.api.OutputSinkFactory
import org.benf.cfr.reader.api.SinkReturns
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class SnippetService(private val config: AppConfig) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun getSnippet(
        symbol: IndexedSymbol,
        version: Version,
        tree: MappingTree,
        linesBefore: Int,
        linesAfter: Int
    ): SnippetResult {
        require(config.snippetsEnabled) { "Snippets are disabled via NMS_MCP_ENABLE_SNIPPETS" }
        val classInternalName = when (symbol.kind) {
            SymbolKind.CLASS -> symbol.sourceInternalName
            else -> requireNotNull(symbol.ownerSourceInternalName)
        }
        val classBinaryName = symbol.canonicalBinaryName
            ?: symbol.aliases[MappingNamespace.MOJANG.wireName]?.ownerBinaryName
            ?: classInternalName.replace('/', '.')
        val remappedClassInternalName = classBinaryName.replace('.', '/')

        val versionDir = config.cacheDir.resolve("snippets").resolve(version.id).createDirectories()
        val serverJar = ensureServerJar(version, versionDir)
        val remappedDir = remapClass(tree, serverJar, remappedClassInternalName, versionDir)
        val classFile = remappedDir.resolve("$remappedClassInternalName.class")
        require(Files.exists(classFile)) {
            "Could not locate remapped class file for $classBinaryName"
        }

        val decompiled = decompile(classFile)
        val lines = decompiled.lines()
        val anchor = findAnchorLine(lines, symbol, classBinaryName.substringAfterLast('.'))
        val start = (anchor - linesBefore).coerceAtLeast(0)
        val endExclusive = (anchor + linesAfter + 1).coerceAtMost(lines.size)
        val excerpt = lines.subList(start, endExclusive).joinToString("\n")

        return SnippetResult(
            symbolId = symbol.symbolId,
            versionId = version.id,
            namespace = MappingNamespace.MOJANG.wireName,
            classBinaryName = classBinaryName,
            excerpt = excerpt,
            startLine = start + 1,
            endLine = endExclusive,
            sourceJar = serverJar.toAbsolutePath().toString()
        )
    }

    private fun ensureServerJar(version: Version, versionDir: Path): Path {
        val serverJar = versionDir.resolve("server-${version.id}.jar")
        if (serverJar.exists()) {
            return serverJar
        }

        val attributes = versionAttributesOf(version)
        val downloaded = versionDir.resolve("server-download.jar")
        download(attributes.downloads.server.url, downloaded)

        return if (isBundledServer(downloaded)) {
            extractBundledServer(version.id, downloaded, serverJar)
        } else {
            Files.copy(downloaded, serverJar)
            serverJar
        }
    }

    private fun remapClass(
        tree: MappingTree,
        serverJar: Path,
        remappedClassInternalName: String,
        versionDir: Path
    ): Path {
        val outputDir = versionDir.resolve("remapped").resolve(remappedClassInternalName.substringBeforeLast('/', "_"))
        if (outputDir.resolve("$remappedClassInternalName.class").exists()) {
            return outputDir
        }

        Files.createDirectories(outputDir)
        val remapper = TinyRemapper.newRemapper()
            .withMappings(TinyUtils.createMappingProvider(tree, tree.srcNamespace, MappingNamespace.MOJANG.wireName))
            .renameInvalidLocals(true)
            .rebuildSourceFilenames(true)
            .build()

        try {
            OutputConsumerPath.Builder(outputDir)
                .filter { className ->
                    className == remappedClassInternalName || className.startsWith("$remappedClassInternalName$")
                }
                .build()
                .use { outputConsumer ->
                    remapper.readInputs(serverJar)
                    remapper.apply(outputConsumer)
                }
        } finally {
            remapper.finish()
        }

        return outputDir
    }

    private fun decompile(classFile: Path): String {
        val sink = StringSinkFactory()
        val driver = CfrDriver.Builder()
            .withOptions(
                mapOf(
                    "hideutf" to "false",
                    "trackbytecodeloc" to "true"
                )
            )
            .withOutputSink(sink)
            .build()
        driver.analyse(listOf(classFile.toAbsolutePath().toString()))
        return sink.java.ifBlank {
            throw IOException("CFR did not produce Java output for $classFile")
        }
    }

    private fun findAnchorLine(lines: List<String>, symbol: IndexedSymbol, classSimpleName: String): Int {
        val candidates = buildList {
            if (symbol.sourceName == "<init>") {
                add(classSimpleName)
            } else {
                add(symbol.canonicalName)
                symbol.aliases.values.forEach { add(it.name) }
            }
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        val lineIndex = lines.indexOfFirst { line ->
            candidates.any { candidate ->
                val normalizedLine = line.lowercase()
                val normalizedCandidate = candidate.lowercase()
                when (symbol.kind) {
                    SymbolKind.FIELD -> Regex("""\b${Regex.escape(normalizedCandidate)}\b""").containsMatchIn(normalizedLine)
                    SymbolKind.METHOD -> normalizedLine.contains("$normalizedCandidate(")
                    SymbolKind.CLASS -> normalizedLine.contains("class $normalizedCandidate") ||
                        normalizedLine.contains("interface $normalizedCandidate") ||
                        normalizedLine.contains("enum $normalizedCandidate")
                }
            }
        }

        return if (lineIndex == -1) 0 else lineIndex
    }

    private fun isBundledServer(file: Path): Boolean {
        JarFile(file.toFile()).use { jar ->
            return jar.getEntry("net/minecraft/bundler/Main.class") != null
        }
    }

    private fun extractBundledServer(versionId: String, source: Path, target: Path): Path {
        JarFile(source.toFile()).use { jar ->
            val nested = jar.getJarEntry("META-INF/versions/$versionId/server-$versionId.jar")
                ?: error("Bundled server jar for $versionId does not contain the nested server archive")
            jar.getInputStream(nested).use { input ->
                Files.copy(input, target)
            }
        }
        return target
    }

    private fun download(url: String, target: Path) {
        Files.createDirectories(target.parent)
        val request = HttpRequest.newBuilder(URI.create(url))
            .GET()
            .timeout(Duration.ofMinutes(2))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(target))
        check(response.statusCode() in 200..299) {
            "Failed to download $url: HTTP ${response.statusCode()}"
        }
    }

    private class StringSinkFactory : OutputSinkFactory {
        val javaBuilder = StringBuilder()
        val java: String
            get() = javaBuilder.toString()

        override fun getSupportedSinks(
            sinkType: OutputSinkFactory.SinkType,
            collection: MutableCollection<OutputSinkFactory.SinkClass>
        ): MutableList<OutputSinkFactory.SinkClass> {
            return when (sinkType) {
                OutputSinkFactory.SinkType.JAVA -> mutableListOf(OutputSinkFactory.SinkClass.DECOMPILED)
                else -> mutableListOf(OutputSinkFactory.SinkClass.STRING)
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> getSink(
            sinkType: OutputSinkFactory.SinkType,
            sinkClass: OutputSinkFactory.SinkClass
        ): OutputSinkFactory.Sink<T> {
            return when (sinkType) {
                OutputSinkFactory.SinkType.JAVA -> OutputSinkFactory.Sink<T> { sinkable ->
                    javaBuilder.append(
                        when (sinkable) {
                            is SinkReturns.Decompiled -> sinkable.java
                            else -> sinkable.toString()
                        }
                    )
                }
                else -> OutputSinkFactory.Sink { }
            }
        }
    }
}
