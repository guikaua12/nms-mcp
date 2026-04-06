package tech.guilhermekaua.nmsmcp.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import tech.guilhermekaua.nmsmcp.config.AppConfig
import tech.guilhermekaua.nmsmcp.model.MappingNamespace
import tech.guilhermekaua.nmsmcp.model.SymbolKind
import tech.guilhermekaua.nmsmcp.service.Renderers
import tech.guilhermekaua.nmsmcp.service.SymbolService
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

class NmsMcpServer(
    private val config: AppConfig,
    private val service: SymbolService,
    private val objectMapper: ObjectMapper
) {
    private val renderers = Renderers(objectMapper)

    fun start() {
        val jsonMapper = JacksonMcpJsonMapper(objectMapper)
        val transportProvider = StdioServerTransportProvider(jsonMapper)

        McpServer.sync(transportProvider)
            .serverInfo("nms-mappings-mcp", "0.2.0")
            .instructions(
                "Use this server to resolve grounded Minecraft server-side NMS symbols. " +
                    "Prefer resolve_symbol before assuming a class, field, or method exists."
            )
            .tools(searchTool(), resolveTool(), describeTool(), compareTool(), snippetTool())
            .resources(versionsResource())
            .resourceTemplates(classResourceTemplate(), memberResourceTemplate())
            .completions(classTemplateCompletion(), memberTemplateCompletion())
            .build()
    }

    private fun searchTool(): McpServerFeatures.SyncToolSpecification =
        McpServerFeatures.SyncToolSpecification.builder()
            .tool(
                McpSchema.Tool.builder()
                    .name("search_symbols")
                    .description("Searches grounded NMS classes, methods, and fields for a Minecraft version.")
                    .inputSchema(
                        schema(
                            required = listOf("query"),
                            properties = linkedMapOf(
                                "query" to stringSchema("Free-text symbol query."),
                                "version" to stringSchema("Minecraft version, defaults to the latest release."),
                                "kind" to enumSchema("Symbol kind.", listOf("class", "method", "field")),
                                "namespace" to enumSchema("Preferred mapping namespace.", MappingNamespace.supportedWireNames()),
                                "owner" to stringSchema("Optional owner binary name."),
                                "package_prefix" to stringSchema("Optional package prefix filter."),
                                "limit" to integerSchema("Max results to return. When omitted, all matches are returned.")
                            )
                        )
                    )
                    .build()
            )
            .callHandler { _, request ->
                val args = request.arguments()
                val query = args["query"]?.toString().orEmpty()
                val version = args["version"]?.toString()
                val kind = SymbolKind.fromWireName(args["kind"]?.toString())
                val namespace = MappingNamespace.fromWireName(args["namespace"]?.toString())
                val owner = args["owner"]?.toString()
                val packagePrefix = args["package_prefix"]?.toString()
                val limit = (args["limit"] as? Number)?.toInt()

                val hits = runBlocking {
                    service.search(
                        tech.guilhermekaua.nmsmcp.model.SearchQuery(
                            query = query,
                            version = version,
                            kind = kind,
                            namespace = namespace,
                            owner = owner,
                            packagePrefix = packagePrefix,
                            limit = limit
                        )
                    )
                }

                val actualVersion = hits.firstOrNull()?.symbol?.versionId
                    ?: service.listVersions().firstOrNull { it["isLatestRelease"] == true }?.get("version")?.toString()
                    ?: version.orEmpty()
                toolResult(
                    text = renderers.renderSearch(hits, version ?: actualVersion, actualVersion),
                    structuredContent = linkedMapOf(
                        "queriedVersion" to version,
                        "actualVersion" to actualVersion,
                        "results" to hits.map { hitToMap(it) }
                    )
                )
            }
            .build()

    private fun resolveTool(): McpServerFeatures.SyncToolSpecification =
        McpServerFeatures.SyncToolSpecification.builder()
            .tool(
                McpSchema.Tool.builder()
                    .name("resolve_symbol")
                    .description("Resolves an exact or near-exact class, field, or method name without hallucinating missing symbols.")
                    .inputSchema(
                        schema(
                            required = listOf("name"),
                            properties = linkedMapOf(
                                "name" to stringSchema("Exact or likely symbol name."),
                                "version" to stringSchema("Minecraft version."),
                                "kind" to enumSchema("Symbol kind.", listOf("class", "method", "field")),
                                "namespace" to enumSchema("Preferred mapping namespace.", MappingNamespace.supportedWireNames()),
                                "owner" to stringSchema("Optional owner binary name."),
                                "limit" to integerSchema(
                                    description = "Max fallback results to return when no exact match exists. Required to return fallback candidates and capped at $MAX_FALLBACK_RESULT_LIMIT.",
                                    minimum = MIN_RESULT_LIMIT,
                                    maximum = MAX_FALLBACK_RESULT_LIMIT
                                )
                            )
                        )
                    )
                    .build()
            )
            .callHandler { _, request ->
                val args = request.arguments()
                val fallbackLimit = try {
                    parseExplicitResultLimit(args["limit"], MAX_FALLBACK_RESULT_LIMIT)
                } catch (error: IllegalArgumentException) {
                    return@callHandler toolResult(
                        text = error.message ?: "Invalid limit.",
                        structuredContent = linkedMapOf(
                            "exact" to false,
                            "ambiguous" to false,
                            "results" to emptyList<Map<String, Any?>>()
                        ),
                        isError = true
                    )
                }
                val result = runBlocking {
                    service.resolve(
                        name = args["name"]?.toString().orEmpty(),
                        versionId = args["version"]?.toString(),
                        kind = SymbolKind.fromWireName(args["kind"]?.toString()),
                        namespace = MappingNamespace.fromWireName(args["namespace"]?.toString()),
                        owner = args["owner"]?.toString(),
                        limit = fallbackLimit,
                        allowFallback = fallbackLimit != null
                    )
                }

                if (!result.exact && fallbackLimit == null) {
                    val name = args["name"]?.toString().orEmpty()
                    return@callHandler toolResult(
                        text = "No exact match for '$name'. Provide `limit` ($MIN_RESULT_LIMIT-$MAX_FALLBACK_RESULT_LIMIT) to return fallback candidates.",
                        structuredContent = linkedMapOf(
                            "exact" to false,
                            "ambiguous" to false,
                            "limitRequiredForFallback" to true,
                            "results" to emptyList<Map<String, Any?>>()
                        ),
                        isError = true
                    )
                }

                toolResult(
                    text = if (result.hits.isEmpty()) {
                        "No symbol matched that lookup."
                    } else renderers.renderSearch(
                        result.hits,
                        args["version"]?.toString().orEmpty(),
                        result.hits.first().symbol.versionId
                    ),
                    structuredContent = linkedMapOf(
                        "exact" to result.exact,
                        "ambiguous" to result.ambiguous,
                        "results" to result.hits.map { hitToMap(it) }
                    )
                )
            }
            .build()

    private fun describeTool(): McpServerFeatures.SyncToolSpecification =
        McpServerFeatures.SyncToolSpecification.builder()
            .tool(
                McpSchema.Tool.builder()
                    .name("describe_symbol")
                    .description("Returns the full grounded record for a symbol_id.")
                    .inputSchema(
                        schema(
                            required = listOf("symbol_id"),
                            properties = linkedMapOf(
                                "symbol_id" to stringSchema("Canonical symbol identifier returned by this server.")
                            )
                        )
                    )
                    .build()
            )
            .callHandler { _, request ->
                val symbol = runBlocking { service.describe(request.arguments()["symbol_id"]!!.toString()) }
                toolResult(
                    text = renderers.renderDescribe(symbol),
                    structuredContent = symbolToMap(symbol)
                )
            }
            .build()

    private fun compareTool(): McpServerFeatures.SyncToolSpecification =
        McpServerFeatures.SyncToolSpecification.builder()
            .tool(
                McpSchema.Tool.builder()
                    .name("compare_symbol_versions")
                    .description("Compares one symbol across two Minecraft versions and highlights renames, moves, and removals.")
                    .inputSchema(
                        schema(
                            required = listOf("symbol_id_or_name", "from_version", "to_version"),
                            properties = linkedMapOf(
                                "symbol_id_or_name" to stringSchema("A server-returned symbol_id or a symbol name to resolve in from_version."),
                                "from_version" to stringSchema("Source Minecraft version."),
                                "to_version" to stringSchema("Target Minecraft version."),
                                "namespace" to enumSchema("Display namespace.", MappingNamespace.supportedWireNames())
                            )
                        )
                    )
                    .build()
            )
            .callHandler { _, request ->
                val args = request.arguments()
                val result = runBlocking {
                    service.compare(
                        symbolIdOrName = args["symbol_id_or_name"]!!.toString(),
                        fromVersionId = args["from_version"]!!.toString(),
                        toVersionId = args["to_version"]!!.toString(),
                        namespace = MappingNamespace.fromWireName(args["namespace"]?.toString())
                    )
                }
                toolResult(
                    text = renderers.renderCompare(result, args["from_version"]!!.toString(), args["to_version"]!!.toString()),
                    structuredContent = linkedMapOf(
                        "relationship" to result.relationship,
                        "from" to symbolToMap(result.fromSymbol),
                        "to" to result.toSymbol?.let(::symbolToMap),
                        "notes" to result.notes
                    )
                )
            }
            .build()

    private fun snippetTool(): McpServerFeatures.SyncToolSpecification =
        McpServerFeatures.SyncToolSpecification.builder()
            .tool(
                McpSchema.Tool.builder()
                    .name("get_symbol_snippet")
                    .description("Returns a short decompiled snippet for the symbol's containing class using Mojang names.")
                    .inputSchema(
                        schema(
                            required = listOf("symbol_id"),
                            properties = linkedMapOf(
                                "symbol_id" to stringSchema("Canonical symbol identifier."),
                                "lines_before" to integerSchema("Context lines before the anchor."),
                                "lines_after" to integerSchema("Context lines after the anchor.")
                            )
                        )
                    )
                    .build()
            )
            .callHandler { _, request ->
                val args = request.arguments()
                val snippet = runBlocking {
                    service.snippet(
                        symbolId = args["symbol_id"]!!.toString(),
                        linesBefore = (args["lines_before"] as? Number)?.toInt() ?: 12,
                        linesAfter = (args["lines_after"] as? Number)?.toInt() ?: 20
                    )
                }
                toolResult(
                    text = renderers.renderSnippet(snippet),
                    structuredContent = linkedMapOf(
                        "symbolId" to snippet.symbolId,
                        "version" to snippet.versionId,
                        "namespace" to snippet.namespace,
                        "classBinaryName" to snippet.classBinaryName,
                        "startLine" to snippet.startLine,
                        "endLine" to snippet.endLine,
                        "excerpt" to snippet.excerpt
                    )
                )
            }
            .build()

    private fun versionsResource(): McpServerFeatures.SyncResourceSpecification =
        McpServerFeatures.SyncResourceSpecification(
            McpSchema.Resource.builder()
                .uri("nms://versions")
                .name("NMS Versions")
                .description("Lists available release versions and whether they are already indexed.")
                .mimeType("text/markdown")
                .build()
        ) { _, _ ->
            val versions = service.listVersions()
            McpSchema.ReadResourceResult(
                listOf(
                    McpSchema.TextResourceContents(
                        "nms://versions",
                        "text/markdown",
                        renderers.resourceMarkdown(
                            title = "NMS Versions",
                            summary = "Release versions available through the Mojang manifest and local cache state.",
                            payload = versions
                        )
                    )
                )
            )
        }

    private fun classResourceTemplate(): McpServerFeatures.SyncResourceTemplateSpecification =
        McpServerFeatures.SyncResourceTemplateSpecification(
            McpSchema.ResourceTemplate.builder()
                .uriTemplate("nms://{version}/class/{binaryName}")
                .name("NMS Class")
                .description("Reads a grounded class description for a specific version and binary name.")
                .mimeType("text/markdown")
                .build()
        ) { _, request ->
            val (version, binaryName) = parseClassResource(request.uri())
            val resolved = runBlocking {
                service.resolve(
                    name = binaryName,
                    versionId = version,
                    kind = SymbolKind.CLASS,
                    namespace = null,
                    owner = null,
                    limit = null
                )
            }
            require(resolved.hits.isNotEmpty()) { "No class matched $binaryName in $version" }
            val symbol = resolved.hits.first().symbol
            val markdown = renderers.resourceMarkdown(
                title = binaryName,
                summary = "Grounded class record for `$binaryName` in `$version`.",
                payload = symbolToMap(symbol)
            )
            McpSchema.ReadResourceResult(
                listOf(McpSchema.TextResourceContents(request.uri(), "text/markdown", markdown))
            )
        }

    private fun memberResourceTemplate(): McpServerFeatures.SyncResourceTemplateSpecification =
        McpServerFeatures.SyncResourceTemplateSpecification(
            McpSchema.ResourceTemplate.builder()
                .uriTemplate("nms://{version}/member/{ownerBinaryName}/{kind}/{signatureKey}")
                .name("NMS Member")
                .description("Reads a grounded field or method record for a specific owner and signature.")
                .mimeType("text/markdown")
                .build()
        ) { _, request ->
            val (version, ownerBinaryName, kind, signatureKey) = parseMemberResource(request.uri())
            val decoded = String(Base64.getUrlDecoder().decode(signatureKey), StandardCharsets.UTF_8)
            val separatorIndex = decoded.indexOf('|')
            require(separatorIndex >= 0) { "Invalid signatureKey" }
            val name = decoded.substring(0, separatorIndex)
            val resolved = runBlocking {
                service.resolve(
                    name = name,
                    versionId = version,
                    kind = kind,
                    namespace = null,
                    owner = ownerBinaryName,
                    limit = null
                )
            }
            require(resolved.hits.isNotEmpty()) { "No member matched $ownerBinaryName#$name in $version" }
            val symbol = resolved.hits.first().symbol
            val markdown = renderers.resourceMarkdown(
                title = "$ownerBinaryName#$name",
                summary = "Grounded member record for `$ownerBinaryName#$name` in `$version`.",
                payload = symbolToMap(symbol)
            )
            McpSchema.ReadResourceResult(
                listOf(McpSchema.TextResourceContents(request.uri(), "text/markdown", markdown))
            )
        }

    private fun classTemplateCompletion(): McpServerFeatures.SyncCompletionSpecification =
        McpServerFeatures.SyncCompletionSpecification(
            McpSchema.ResourceReference("nms://{version}/class/{binaryName}")
        ) { _, request ->
            val argumentName = request.argument().name()
            val partial = request.argument().value().orEmpty()
            val suggestions = when (argumentName) {
                "version" -> service.listVersions()
                    .mapNotNull { it["version"]?.toString() }
                    .filter { it.startsWith(partial) }
                    .take(20)
                "binaryName" -> runBlocking {
                    service.search(
                        tech.guilhermekaua.nmsmcp.model.SearchQuery(
                            query = partial,
                            version = null,
                            kind = SymbolKind.CLASS,
                            namespace = null,
                            owner = null,
                            packagePrefix = null,
                            limit = 20
                        )
                    ).mapNotNull { it.symbol.canonicalBinaryName }.distinct()
                }
                else -> emptyList()
            }
            McpSchema.CompleteResult(
                McpSchema.CompleteResult.CompleteCompletion(suggestions, suggestions.size, false)
            )
        }

    private fun memberTemplateCompletion(): McpServerFeatures.SyncCompletionSpecification =
        McpServerFeatures.SyncCompletionSpecification(
            McpSchema.ResourceReference("nms://{version}/member/{ownerBinaryName}/{kind}/{signatureKey}")
        ) { _, request ->
            val argumentName = request.argument().name()
            val partial = request.argument().value().orEmpty()
            val suggestions = when (argumentName) {
                "version" -> service.listVersions()
                    .mapNotNull { it["version"]?.toString() }
                    .filter { it.startsWith(partial) }
                    .take(20)
                "kind" -> listOf("method", "field").filter { it.startsWith(partial) }
                "ownerBinaryName" -> runBlocking {
                    service.search(
                        tech.guilhermekaua.nmsmcp.model.SearchQuery(
                            query = partial,
                            version = null,
                            kind = SymbolKind.CLASS,
                            namespace = null,
                            owner = null,
                            packagePrefix = null,
                            limit = 20
                        )
                    ).mapNotNull { it.symbol.canonicalBinaryName }.distinct()
                }
                else -> emptyList()
            }
            McpSchema.CompleteResult(
                McpSchema.CompleteResult.CompleteCompletion(suggestions, suggestions.size, false)
            )
        }

    private fun hitToMap(hit: tech.guilhermekaua.nmsmcp.model.SearchHit): Map<String, Any?> =
        linkedMapOf(
            "score" to hit.score,
            "reason" to hit.reason,
            "matchedNamespace" to hit.matchedNamespace,
            "matchedValue" to hit.matchedValue,
            "symbol" to symbolToMap(hit.symbol)
        )

    private fun symbolToMap(symbol: tech.guilhermekaua.nmsmcp.model.IndexedSymbol): Map<String, Any?> =
        linkedMapOf(
            "symbolId" to symbol.symbolId,
            "versionId" to symbol.versionId,
            "kind" to symbol.kind.wireName,
            "canonicalName" to symbol.canonicalName,
            "canonicalQualifiedName" to symbol.canonicalQualifiedName,
            "canonicalBinaryName" to symbol.canonicalBinaryName,
            "packageName" to symbol.packageName,
            "sourceInternalName" to symbol.sourceInternalName,
            "sourceName" to symbol.sourceName,
            "sourceDescriptor" to symbol.sourceDescriptor,
            "ownerSourceInternalName" to symbol.ownerSourceInternalName,
            "modifiers" to symbol.modifiers,
            "signature" to symbol.signature,
            "superClass" to symbol.superClass,
            "interfaces" to symbol.interfaces,
            "availableNamespaces" to symbol.availableNamespaces,
            "aliases" to symbol.aliases.values.associate { alias ->
                alias.namespace to linkedMapOf(
                    "name" to alias.name,
                    "qualifiedName" to alias.qualifiedName,
                    "binaryName" to alias.binaryName,
                    "descriptor" to alias.descriptor,
                    "ownerBinaryName" to alias.ownerBinaryName
                )
            }
        )

    private fun toolResult(
        text: String,
        structuredContent: Any,
        isError: Boolean = false
    ): McpSchema.CallToolResult =
        McpSchema.CallToolResult.builder()
            .content(listOf(McpSchema.TextContent(text)))
            .structuredContent(structuredContent)
            .isError(isError)
            .build()

    private fun parseClassResource(uriString: String): Pair<String, String> {
        val uri = URI(uriString)
        val version = requireNotNull(uri.authority) { "Missing version in URI: $uriString" }
        val pathParts = uri.path.trim('/').split('/')
        require(pathParts.size == 2 && pathParts[0] == "class") { "Invalid class resource URI: $uriString" }
        return version to decode(pathParts[1])
    }

    private fun parseMemberResource(uriString: String): MemberResource {
        val uri = URI(uriString)
        val version = requireNotNull(uri.authority) { "Missing version in URI: $uriString" }
        val pathParts = uri.path.trim('/').split('/')
        require(pathParts.size == 4 && pathParts[0] == "member") { "Invalid member resource URI: $uriString" }
        return MemberResource(
            version = version,
            ownerBinaryName = decode(pathParts[1]),
            kind = requireNotNull(SymbolKind.fromWireName(pathParts[2])) {
                "Invalid member kind in URI: ${pathParts[2]}"
            },
            signatureKey = pathParts[3]
        )
    }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun schema(
        required: List<String>,
        properties: Map<String, Any>
    ): McpSchema.JsonSchema =
        McpSchema.JsonSchema(
            "object",
            properties,
            required,
            false,
            emptyMap(),
            emptyMap()
        )

    private fun stringSchema(description: String): Map<String, Any> =
        linkedMapOf("type" to "string", "description" to description)

    private fun integerSchema(
        description: String,
        default: Int? = null,
        minimum: Int? = null,
        maximum: Int? = null
    ): Map<String, Any> =
        linkedMapOf<String, Any>(
            "type" to "integer",
            "description" to description
        ).apply {
            default?.let { put("default", it) }
            minimum?.let { put("minimum", it) }
            maximum?.let { put("maximum", it) }
        }

    private fun enumSchema(description: String, values: List<String>): Map<String, Any> =
        linkedMapOf("type" to "string", "description" to description, "enum" to values)

    private data class MemberResource(
        val version: String,
        val ownerBinaryName: String,
        val kind: SymbolKind,
        val signatureKey: String
    )

    private companion object {
        const val MAX_FALLBACK_RESULT_LIMIT = 100
    }
}

private fun String.trim(char: Char): String = trim { it == char }

internal const val MIN_RESULT_LIMIT = 1

internal fun parseExplicitResultLimit(
    rawValue: Any?,
    maximum: Int
): Int? {
    if (rawValue == null) {
        return null
    }

    val parsed = when (rawValue) {
        is Number -> rawValue.toInt()
        is String -> rawValue.toIntOrNull()
        else -> null
    } ?: throw IllegalArgumentException("limit must be an integer between $MIN_RESULT_LIMIT and $maximum")

    return parsed.coerceIn(MIN_RESULT_LIMIT, maximum)
}
