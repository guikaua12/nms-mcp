package tech.guilhermekaua.nmsmcp.model

import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Instant

enum class MappingNamespace(val wireName: String) {
    SOURCE("source"),
    MOJANG("mojang"),
    SPIGOT("spigot"),
    SEARGE("searge"),
    INTERMEDIARY("intermediary"),
    YARN("yarn"),
    QUILT("quilt"),
    HASHED("hashed");

    companion object {
        fun fromWireName(value: String?): MappingNamespace? {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wireName == normalized }
        }

        fun supportedWireNames(): List<String> = entries.map { it.wireName }
    }
}

enum class SymbolKind(val wireName: String) {
    CLASS("class"),
    METHOD("method"),
    FIELD("field");

    companion object {
        fun fromWireName(value: String?): SymbolKind? {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wireName == normalized }
        }
    }
}

data class SymbolAlias(
    val namespace: String,
    val name: String,
    val qualifiedName: String?,
    val binaryName: String?,
    val descriptor: String?,
    val ownerBinaryName: String?
)

data class IndexedSymbol(
    val symbolId: String,
    val versionId: String,
    val kind: SymbolKind,
    val sourceInternalName: String,
    val sourceName: String,
    val sourceDescriptor: String?,
    val ownerSourceInternalName: String?,
    val canonicalName: String,
    val canonicalQualifiedName: String?,
    val canonicalBinaryName: String?,
    val packageName: String?,
    val modifiers: Int,
    val signature: String?,
    val superClass: String?,
    val interfaces: List<String>,
    val aliases: Map<String, SymbolAlias>
) {
    @get:JsonIgnore
    val availableNamespaces: List<String>
        get() = aliases.keys.sorted()
}

data class VersionIndex(
    val versionId: String,
    val releaseTime: Instant,
    val indexedAt: Instant,
    val symbolsById: Map<String, IndexedSymbol>
)

data class SearchHit(
    val score: Int,
    val reason: String,
    val matchedNamespace: String,
    val matchedValue: String,
    val symbol: IndexedSymbol
)

data class SearchQuery(
    val query: String,
    val version: String?,
    val kind: SymbolKind?,
    val namespace: MappingNamespace?,
    val owner: String?,
    val packagePrefix: String?,
    val limit: Int?
)

data class ResolveResult(
    val exact: Boolean,
    val ambiguous: Boolean,
    val hits: List<SearchHit>
)

data class CompareResult(
    val fromSymbol: IndexedSymbol,
    val toSymbol: IndexedSymbol?,
    val relationship: String,
    val notes: List<String>
)

data class SnippetResult(
    val symbolId: String,
    val versionId: String,
    val namespace: String,
    val classBinaryName: String,
    val excerpt: String,
    val startLine: Int,
    val endLine: Int,
    val sourceJar: String
)
