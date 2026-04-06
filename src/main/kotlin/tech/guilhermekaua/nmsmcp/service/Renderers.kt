package tech.guilhermekaua.nmsmcp.service

import com.fasterxml.jackson.databind.ObjectMapper
import tech.guilhermekaua.nmsmcp.model.CompareResult
import tech.guilhermekaua.nmsmcp.model.IndexedSymbol
import tech.guilhermekaua.nmsmcp.model.SearchHit
import tech.guilhermekaua.nmsmcp.model.SnippetResult
import tech.guilhermekaua.nmsmcp.model.SymbolKind

class Renderers(private val objectMapper: ObjectMapper) {
    fun renderSearch(hits: List<SearchHit>, queriedVersion: String, actualVersion: String): String {
        val lines = mutableListOf<String>()
        lines += "Queried version: `$queriedVersion`"
        lines += "Resolved version: `$actualVersion`"
        lines += ""

        if (hits.isEmpty()) {
            lines += "No matching symbols were found."
            return lines.joinToString("\n")
        }

        lines += "Matches:"
        hits.forEachIndexed { index, hit ->
            lines += "${index + 1}. `${hit.symbol.symbolId}`"
            lines += "   `${hit.symbol.kind.wireName}` `${hit.symbol.canonicalQualifiedName ?: hit.symbol.canonicalName}` via `${hit.matchedNamespace}` because ${hit.reason}"
        }
        return lines.joinToString("\n")
    }

    fun renderDescribe(symbol: IndexedSymbol): String {
        val lines = mutableListOf<String>()
        lines += "# ${symbol.canonicalQualifiedName ?: symbol.canonicalName}"
        lines += ""
        lines += "- `symbol_id`: `${symbol.symbolId}`"
        lines += "- `version`: `${symbol.versionId}`"
        lines += "- `kind`: `${symbol.kind.wireName}`"
        lines += "- `canonical_name`: `${symbol.canonicalName}`"
        symbol.canonicalQualifiedName?.let { lines += "- `canonical_qualified_name`: `$it`" }
        symbol.canonicalBinaryName?.let { lines += "- `canonical_binary_name`: `$it`" }
        symbol.packageName?.let { lines += "- `package`: `$it`" }
        if (symbol.kind == SymbolKind.CLASS) {
            symbol.superClass?.let { lines += "- `super_class`: `$it`" }
            if (symbol.interfaces.isNotEmpty()) {
                lines += "- `interfaces`: ${symbol.interfaces.joinToString { "`$it`" }}"
            }
        } else {
            symbol.sourceDescriptor?.let { lines += "- `source_descriptor`: `$it`" }
        }
        lines += "- `available_namespaces`: ${symbol.availableNamespaces.joinToString { "`$it`" }}"
        lines += ""
        lines += "## Aliases"
        symbol.aliases.values.sortedBy { it.namespace }.forEach { alias ->
            val details = buildList {
                add("name=`${alias.name}`")
                alias.qualifiedName?.let { add("qualified=`$it`") }
                alias.binaryName?.let { add("binary=`$it`") }
                alias.ownerBinaryName?.let { add("owner=`$it`") }
                alias.descriptor?.let { add("descriptor=`$it`") }
            }.joinToString(", ")
            lines += "- `${alias.namespace}`: $details"
        }
        return lines.joinToString("\n")
    }

    fun renderCompare(result: CompareResult, fromVersion: String, toVersion: String): String {
        val lines = mutableListOf<String>()
        lines += "Comparing `${result.fromSymbol.symbolId}`"
        lines += "- from: `$fromVersion`"
        lines += "- to: `$toVersion`"
        lines += "- relationship: `${result.relationship}`"
        result.toSymbol?.let {
            lines += "- target_symbol_id: `${it.symbolId}`"
        } ?: run {
            lines += "- target_symbol_id: `(missing)`"
        }
        if (result.notes.isNotEmpty()) {
            lines += ""
            lines += "Notes:"
            result.notes.forEach { lines += "- $it" }
        }
        return lines.joinToString("\n")
    }

    fun renderSnippet(snippet: SnippetResult): String {
        return buildString {
            appendLine("Snippet for `${snippet.symbolId}`")
            appendLine("- version: `${snippet.versionId}`")
            appendLine("- namespace: `${snippet.namespace}`")
            appendLine("- class: `${snippet.classBinaryName}`")
            appendLine("- lines: `${snippet.startLine}-${snippet.endLine}`")
            appendLine()
            appendLine("```java")
            appendLine(snippet.excerpt)
            appendLine("```")
        }.trimEnd()
    }

    fun renderVersions(versions: List<Map<String, Any?>>): String {
        val lines = mutableListOf<String>()
        lines += "# Available Versions"
        versions.forEach { version ->
            lines += "- `${version["version"]}` indexed=${version["indexed"]} latest=${version["isLatestRelease"]}"
        }
        return lines.joinToString("\n")
    }

    fun resourceMarkdown(title: String, summary: String, payload: Any): String {
        val json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload)
        return buildString {
            appendLine("# $title")
            appendLine()
            appendLine(summary)
            appendLine()
            appendLine("```json")
            appendLine(json)
            appendLine("```")
        }.trimEnd()
    }
}
