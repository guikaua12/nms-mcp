package tech.guilhermekaua.nmsmcp.service

import tech.guilhermekaua.nmsmcp.config.AppConfig
import tech.guilhermekaua.nmsmcp.index.SqliteSymbolIndex
import tech.guilhermekaua.nmsmcp.index.SymbolIndexBuilder
import tech.guilhermekaua.nmsmcp.mappings.MappingSource
import tech.guilhermekaua.nmsmcp.model.CompareResult
import tech.guilhermekaua.nmsmcp.model.IndexedSymbol
import tech.guilhermekaua.nmsmcp.model.MappingNamespace
import tech.guilhermekaua.nmsmcp.model.ResolveResult
import tech.guilhermekaua.nmsmcp.model.SearchHit
import tech.guilhermekaua.nmsmcp.model.SearchQuery
import tech.guilhermekaua.nmsmcp.model.SnippetResult
import tech.guilhermekaua.nmsmcp.model.SymbolAlias
import tech.guilhermekaua.nmsmcp.model.SymbolKind
import tech.guilhermekaua.nmsmcp.model.VersionIndex
import tech.guilhermekaua.nmsmcp.snippets.SnippetService
import me.kcra.takenaka.core.Version
import me.kcra.takenaka.core.VersionManifest
import me.kcra.takenaka.core.mapping.ancestry.ConstructorComputationMode
import me.kcra.takenaka.core.mapping.ancestry.impl.classAncestryTreeOf
import me.kcra.takenaka.core.mapping.ancestry.impl.fieldAncestryTreeOf
import me.kcra.takenaka.core.mapping.ancestry.impl.methodAncestryTreeOf
import net.fabricmc.mappingio.tree.MappingTree
import java.util.concurrent.ConcurrentHashMap

class SymbolService(
    private val config: AppConfig,
    private val manifest: VersionManifest,
    private val mappingSource: MappingSource,
    private val sqliteIndex: SqliteSymbolIndex,
    private val snippetService: SnippetService
) {
    private val indexBuilder = SymbolIndexBuilder(config)
    private val versionCache = ConcurrentHashMap<String, VersionIndex>()
    private val treeCache = ConcurrentHashMap<String, MappingTree>()

    suspend fun search(query: SearchQuery): List<SearchHit> {
        val version = resolveVersion(query.version)
        val versionIndex = ensureIndexed(version)
        val normalizedQuery = query.query.trim()
        require(normalizedQuery.isNotEmpty()) { "query must not be blank" }
        val normalizedOwner = query.owner?.trim()?.lowercase()
        val normalizedPackagePrefix = query.packagePrefix?.trim()?.lowercase()
        val tokens = normalizedQuery.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val hits = mutableListOf<SearchHit>()

        for (symbol in versionIndex.symbolsById.values) {
            if (query.kind != null && symbol.kind != query.kind) {
                continue
            }
            if (normalizedPackagePrefix != null && !(symbol.packageName?.lowercase()?.startsWith(normalizedPackagePrefix) ?: false)) {
                continue
            }

            for ((namespace, alias) in symbol.aliases) {
                if (query.namespace != null && namespace != query.namespace.wireName) {
                    continue
                }
                if (normalizedOwner != null) {
                    val ownerBinaryName = alias.ownerBinaryName?.lowercase()
                    val canonicalOwnerBinaryName = symbol.canonicalBinaryName?.lowercase()
                    val ownerMatches = ownerBinaryName == normalizedOwner ||
                        canonicalOwnerBinaryName == normalizedOwner ||
                        ownerBinaryName?.contains(normalizedOwner) == true ||
                        canonicalOwnerBinaryName?.contains(normalizedOwner) == true
                    if (!ownerMatches) {
                        continue
                    }
                }

                val score = computeScore(
                    symbol = symbol,
                    alias = alias,
                    namespace = namespace,
                    normalizedQuery = normalizedQuery.lowercase(),
                    tokens = tokens,
                    requestedNamespace = query.namespace
                ) ?: continue

                hits += SearchHit(
                    score = score.first,
                    reason = score.second,
                    matchedNamespace = namespace,
                    matchedValue = alias.qualifiedName ?: alias.binaryName ?: alias.name,
                    symbol = symbol
                )
            }
        }

        return hits
            .groupBy { it.symbol.symbolId }
            .values
            .map { group -> group.maxByOrNull(SearchHit::score)!! }
            .sortedWith(compareByDescending<SearchHit> { it.score }.thenBy { it.symbol.canonicalQualifiedName ?: it.symbol.canonicalName })
            .let { dedupedHits ->
                query.limit
                    ?.coerceAtLeast(1)
                    ?.let(dedupedHits::take)
                    ?: dedupedHits
            }
    }

    suspend fun resolve(
        name: String,
        versionId: String?,
        kind: SymbolKind?,
        namespace: MappingNamespace?,
        owner: String?,
        limit: Int?,
        allowFallback: Boolean = true
    ): ResolveResult {
        val version = resolveVersion(versionId)
        val versionIndex = ensureIndexed(version)
        val normalized = name.trim().lowercase()
        val exact = mutableListOf<SearchHit>()

        for (symbol in versionIndex.symbolsById.values) {
            if (kind != null && symbol.kind != kind) {
                continue
            }
            for ((aliasNamespace, alias) in symbol.aliases) {
                if (namespace != null && aliasNamespace != namespace.wireName) {
                    continue
                }
                if (owner != null) {
                    val ownerMatches = alias.ownerBinaryName?.equals(owner, ignoreCase = true) == true ||
                        symbol.canonicalBinaryName?.equals(owner, ignoreCase = true) == true
                    if (!ownerMatches) {
                        continue
                    }
                }

                val exactMatch = listOfNotNull(
                    alias.name,
                    alias.qualifiedName,
                    alias.binaryName
                ).any { it.equals(name, ignoreCase = true) }

                if (exactMatch) {
                    exact += SearchHit(
                        score = 1000,
                        reason = "exact alias match",
                        matchedNamespace = aliasNamespace,
                        matchedValue = alias.qualifiedName ?: alias.binaryName ?: alias.name,
                        symbol = symbol
                    )
                }
            }
        }

        val exactDeduped = exact.groupBy { it.symbol.symbolId }.values.map { it.first() }
        if (exactDeduped.isNotEmpty()) {
            return ResolveResult(
                exact = true,
                ambiguous = exactDeduped.size > 1,
                hits = exactDeduped.sortedBy { it.symbol.canonicalQualifiedName ?: it.symbol.canonicalName }
            )
        }

        if (!allowFallback) {
            return ResolveResult(
                exact = false,
                ambiguous = false,
                hits = emptyList()
            )
        }

        return ResolveResult(
            exact = false,
            ambiguous = false,
            hits = search(
                SearchQuery(
                    query = name,
                    version = version.id,
                    kind = kind,
                    namespace = namespace,
                    owner = owner,
                    packagePrefix = null,
                    limit = limit
                )
            )
        )
    }

    suspend fun describe(symbolId: String): IndexedSymbol {
        val canonicalSymbolId = requireCanonicalSymbolId(symbolId)
        val versionId = canonicalSymbolId.substringBefore('|')
        val version = resolveVersion(versionId)
        val versionIndex = ensureIndexed(version)
        return requireNotNull(versionIndex.symbolsById[canonicalSymbolId]) {
            "Unknown symbol_id '$canonicalSymbolId'"
        }
    }

    suspend fun compare(
        symbolIdOrName: String,
        fromVersionId: String,
        toVersionId: String,
        namespace: MappingNamespace?
    ): CompareResult {
        val fromVersion = resolveVersion(fromVersionId)
        val toVersion = resolveVersion(toVersionId)
        val toIndex = ensureIndexed(toVersion)
        val fromTree = ensureTreeLoaded(fromVersion)
        val toTree = ensureTreeLoaded(toVersion)
        val fromSymbol = if (symbolIdOrName.contains('|')) {
            describe(symbolIdOrName)
        } else {
            val resolved = resolve(symbolIdOrName, fromVersion.id, null, namespace, null, null)
            require(resolved.hits.isNotEmpty()) { "Could not resolve '$symbolIdOrName' in $fromVersionId" }
            require(!resolved.ambiguous) { "Lookup for '$symbolIdOrName' in $fromVersionId is ambiguous" }
            resolved.hits.first().symbol
        }

        require(fromSymbol.versionId == fromVersion.id) {
            "symbol_id '$symbolIdOrName' belongs to ${fromSymbol.versionId}, not $fromVersionId"
        }

        val allowedNamespaces = comparisonNamespaces(fromSymbol, fromTree, toTree)

        val classTree = classAncestryTreeOf<MappingTree, MappingTree.ClassMapping>(
            mappings = linkedMapOf(fromVersion to fromTree, toVersion to toTree),
            allowedNamespaces = allowedNamespaces
        )

        val fromClassInternalName = if (fromSymbol.kind == SymbolKind.CLASS) {
            fromSymbol.sourceInternalName
        } else {
            requireNotNull(fromSymbol.ownerSourceInternalName)
        }

        val classNode = classTree.firstOrNull { node ->
            node[fromVersion]?.srcName == fromClassInternalName
        } ?: error("Could not build ancestry for ${fromSymbol.symbolId}")

        val toSymbol = when (fromSymbol.kind) {
            SymbolKind.CLASS -> {
                val targetClass = classNode[toVersion] ?: return CompareResult(fromSymbol, null, "missing", listOf("Class is not present in $toVersionId"))
                toIndex.symbolsById[SymbolIndexBuilder.classSymbolId(toVersion.id, targetClass.srcName)]
            }

            SymbolKind.FIELD -> {
                val fieldTree = fieldAncestryTreeOf<MappingTree, MappingTree.ClassMapping, MappingTree.FieldMapping>(classNode)
                val fieldNode = fieldTree.firstOrNull { node ->
                    node[fromVersion]?.srcName == fromSymbol.sourceName &&
                        node[fromVersion]?.srcDesc == fromSymbol.sourceDescriptor
                } ?: return CompareResult(fromSymbol, null, "unknown", listOf("Could not align the field across versions"))
                val targetField = fieldNode[toVersion] ?: return CompareResult(fromSymbol, null, "missing", listOf("Field is not present in $toVersionId"))
                toIndex.symbolsById[
                    SymbolIndexBuilder.memberSymbolId(
                        toVersion.id,
                        SymbolKind.FIELD,
                        targetField.owner.srcName,
                        targetField.srcName,
                        targetField.srcDesc
                    )
                ]
            }

            SymbolKind.METHOD -> {
                val methodTree = methodAncestryTreeOf<MappingTree, MappingTree.ClassMapping, MappingTree.MethodMapping>(
                    classNode,
                    ConstructorComputationMode.INCLUDE
                )
                val methodNode = methodTree.firstOrNull { node ->
                    node[fromVersion]?.srcName == fromSymbol.sourceName &&
                        node[fromVersion]?.srcDesc == fromSymbol.sourceDescriptor
                } ?: return CompareResult(fromSymbol, null, "unknown", listOf("Could not align the method across versions"))
                val targetMethod = methodNode[toVersion] ?: return CompareResult(fromSymbol, null, "missing", listOf("Method is not present in $toVersionId"))
                toIndex.symbolsById[
                    SymbolIndexBuilder.memberSymbolId(
                        toVersion.id,
                        SymbolKind.METHOD,
                        targetMethod.owner.srcName,
                        targetMethod.srcName,
                        targetMethod.srcDesc
                    )
                ]
            }
        }

        val targetSymbol = toSymbol ?: return CompareResult(
            fromSymbol,
            null,
            "missing",
            listOf("No indexed target symbol was found in $toVersionId")
        )

        val displayNamespace = namespace?.wireName ?: config.primaryNamespace.wireName
        val fromAlias = fromSymbol.aliases[displayNamespace]
        val toAlias = targetSymbol.aliases[displayNamespace]
        val notes = mutableListOf<String>()

        if (fromAlias?.name != toAlias?.name) {
            notes += "Name changed from ${fromAlias?.name ?: fromSymbol.canonicalName} to ${toAlias?.name ?: targetSymbol.canonicalName}"
        }
        if (fromAlias?.ownerBinaryName != toAlias?.ownerBinaryName && fromSymbol.kind != SymbolKind.CLASS) {
            notes += "Owner changed from ${fromAlias?.ownerBinaryName} to ${toAlias?.ownerBinaryName}"
        }
        if (fromAlias?.descriptor != toAlias?.descriptor && fromSymbol.kind != SymbolKind.CLASS) {
            notes += "Descriptor changed from ${fromAlias?.descriptor ?: fromSymbol.sourceDescriptor} to ${toAlias?.descriptor ?: targetSymbol.sourceDescriptor}"
        }

        val relationship = when {
            notes.isEmpty() -> "equivalent"
            notes.any { it.startsWith("Owner changed") } -> "moved"
            notes.any { it.startsWith("Name changed") } -> "renamed"
            else -> "changed"
        }

        return CompareResult(fromSymbol, targetSymbol, relationship, notes)
    }

    suspend fun snippet(symbolId: String, linesBefore: Int, linesAfter: Int): SnippetResult {
        val symbol = describe(symbolId)
        val version = resolveVersion(symbol.versionId)
        val tree = ensureTreeLoaded(version)
        return snippetService.getSnippet(symbol, version, tree, linesBefore, linesAfter)
    }

    fun listVersions(): List<Map<String, Any?>> {
        val indexed = sqliteIndex.indexedVersionIds()
        return manifest.versions
            .filter { it.type.name == "RELEASE" }
            .sortedByDescending { it.releaseTime }
            .map { version ->
                linkedMapOf<String, Any?>(
                    "version" to version.id,
                    "releaseTime" to version.releaseTime.toString(),
                    "isLatestRelease" to (version.id == manifest.latest.release),
                    "indexed" to indexed.contains(version.id)
                )
            }
    }

    private suspend fun ensureIndexed(version: Version): VersionIndex {
        versionCache[version.id]?.let { return it }

        sqliteIndex.loadVersion(version.id)?.let { cached ->
            versionCache[version.id] = cached
            return cached
        }

        val loaded = mappingSource.load(listOf(version.id))
        val tree = requireNotNull(loaded[version]) {
            "No mapping tree could be loaded for ${version.id}"
        }
        treeCache[version.id] = tree
        val built = indexBuilder.build(version, tree)
        sqliteIndex.storeVersion(built)
        versionCache[version.id] = built
        return built
    }

    private suspend fun ensureTreeLoaded(version: Version): MappingTree {
        treeCache[version.id]?.let { return it }
        val loaded = mappingSource.load(listOf(version.id))
        val tree = requireNotNull(loaded[version]) {
            "No mapping tree could be loaded for ${version.id}"
        }
        treeCache[version.id] = tree
        return tree
    }

    private fun resolveVersion(versionId: String?): Version {
        val requested = versionId?.takeIf { it.isNotBlank() }
            ?: config.defaultVersion
            ?: manifest.latest.release
        return requireNotNull(manifest[requested]) {
            "Unknown Minecraft version '$requested'"
        }
    }

    private fun requireCanonicalSymbolId(symbolId: String): String {
        val normalized = symbolId.trim()
        require(normalized.isNotEmpty()) { "symbol_id must not be blank" }
        require(normalized.count { it == '|' } >= 2) {
            "symbol_id must be a canonical server-returned identifier such as '<version>|class|...'"
        }
        return normalized
    }

    private fun comparisonNamespaces(fromSymbol: IndexedSymbol, fromTree: MappingTree, toTree: MappingTree): List<String> {
        val sharedDestinationNamespaces = fromTree.dstNamespaces.intersect(toTree.dstNamespaces.toSet())
        val preferredNamespaces = fromSymbol.aliases.keys.asSequence()
            .filter { it != MappingNamespace.SOURCE.wireName }
            .filter(sharedDestinationNamespaces::contains)
            .toList()

        if (preferredNamespaces.isNotEmpty()) {
            return preferredNamespaces
        }

        val fallbackNamespaces = buildList {
            if (config.primaryNamespace != MappingNamespace.SOURCE && sharedDestinationNamespaces.contains(config.primaryNamespace.wireName)) {
                add(config.primaryNamespace.wireName)
            }
            addAll(sharedDestinationNamespaces)
        }.distinct()

        require(fallbackNamespaces.isNotEmpty()) {
            "Could not find a comparable destination namespace shared by ${fromTree.srcNamespace} mappings in ${fromSymbol.versionId}"
        }
        return fallbackNamespaces
    }

    private fun computeScore(
        symbol: IndexedSymbol,
        alias: SymbolAlias,
        namespace: String,
        normalizedQuery: String,
        tokens: List<String>,
        requestedNamespace: MappingNamespace?
    ): Pair<Int, String>? {
        val qualified = alias.qualifiedName?.lowercase()
        val binary = alias.binaryName?.lowercase()
        val name = alias.name.lowercase()
        val searchable = buildString {
            append(name)
            append(' ')
            if (qualified != null) append(qualified).append(' ')
            if (binary != null) append(binary).append(' ')
            append(symbol.canonicalQualifiedName?.lowercase().orEmpty())
        }

        if (tokens.any { it !in searchable }) {
            return null
        }

        var score = 0
        var reason = "matched all query terms"

        when {
            qualified == normalizedQuery -> {
                score += 250
                reason = "exact qualified match"
            }
            binary == normalizedQuery -> {
                score += 240
                reason = "exact class match"
            }
            name == normalizedQuery -> {
                score += 230
                reason = "exact simple-name match"
            }
            qualified?.startsWith(normalizedQuery) == true -> {
                score += 180
                reason = "qualified-name prefix match"
            }
            binary?.startsWith(normalizedQuery) == true -> {
                score += 170
                reason = "binary-name prefix match"
            }
            name.startsWith(normalizedQuery) -> {
                score += 160
                reason = "simple-name prefix match"
            }
            else -> {
                score += 100
            }
        }

        score += if (namespace == config.primaryNamespace.wireName) 15 else 0
        score += if (requestedNamespace?.wireName == namespace) 25 else 0
        score += if (symbol.kind == SymbolKind.CLASS) 8 else 0

        return score to reason
    }
}
