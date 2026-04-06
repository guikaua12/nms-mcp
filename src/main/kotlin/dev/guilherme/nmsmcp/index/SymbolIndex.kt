package dev.guilherme.nmsmcp.index

import dev.guilherme.nmsmcp.config.AppConfig
import dev.guilherme.nmsmcp.model.IndexedSymbol
import dev.guilherme.nmsmcp.model.MappingNamespace
import dev.guilherme.nmsmcp.model.SymbolAlias
import dev.guilherme.nmsmcp.model.SymbolKind
import dev.guilherme.nmsmcp.model.VersionIndex
import me.kcra.takenaka.core.Version
import me.kcra.takenaka.core.mapping.resolve.impl.interfaces
import me.kcra.takenaka.core.mapping.resolve.impl.modifiers
import me.kcra.takenaka.core.mapping.resolve.impl.signature
import me.kcra.takenaka.core.mapping.resolve.impl.superClass
import net.fabricmc.mappingio.tree.MappingTree
import net.fabricmc.mappingio.tree.MappingTreeView
import java.time.Instant

class SymbolIndexBuilder(private val config: AppConfig) {
    fun build(version: Version, tree: MappingTree): VersionIndex {
        val supportedNamespaces = resolveNamespaceIds(tree)
        val symbols = linkedMapOf<String, IndexedSymbol>()

        for (klass in tree.classes) {
            val classAliases = buildClassAliases(klass, supportedNamespaces)
            if (!isServerSide(classAliases)) {
                continue
            }

            val classCanonical = chooseCanonicalAlias(classAliases)
            val classSymbolId = classSymbolId(version.id, klass.srcName)
            val classSymbol = IndexedSymbol(
                symbolId = classSymbolId,
                versionId = version.id,
                kind = SymbolKind.CLASS,
                sourceInternalName = klass.srcName,
                sourceName = klass.srcName.substringAfterLast('/'),
                sourceDescriptor = null,
                ownerSourceInternalName = null,
                canonicalName = classCanonical.name,
                canonicalQualifiedName = classCanonical.qualifiedName,
                canonicalBinaryName = classCanonical.binaryName,
                packageName = classCanonical.binaryName?.substringBeforeLast('.', ""),
                modifiers = klass.modifiers,
                signature = klass.signature,
                superClass = klass.superClass.toBinaryName(),
                interfaces = klass.interfaces.map { it.toBinaryName() },
                aliases = classAliases
            )
            symbols[classSymbolId] = classSymbol

            for (field in klass.fields) {
                val aliases = buildMemberAliases(field, classAliases, supportedNamespaces)
                val canonical = chooseCanonicalAlias(aliases)
                val id = memberSymbolId(version.id, SymbolKind.FIELD, klass.srcName, field.srcName, field.srcDesc)
                symbols[id] = IndexedSymbol(
                    symbolId = id,
                    versionId = version.id,
                    kind = SymbolKind.FIELD,
                    sourceInternalName = klass.srcName,
                    sourceName = field.srcName,
                    sourceDescriptor = field.srcDesc,
                    ownerSourceInternalName = klass.srcName,
                    canonicalName = canonical.name,
                    canonicalQualifiedName = canonical.qualifiedName,
                    canonicalBinaryName = classCanonical.binaryName,
                    packageName = classSymbol.packageName,
                    modifiers = field.modifiers,
                    signature = field.signature,
                    superClass = null,
                    interfaces = emptyList(),
                    aliases = aliases
                )
            }

            for (method in klass.methods) {
                val aliases = buildMemberAliases(method, classAliases, supportedNamespaces)
                val canonical = chooseCanonicalAlias(aliases)
                val id = memberSymbolId(version.id, SymbolKind.METHOD, klass.srcName, method.srcName, method.srcDesc)
                symbols[id] = IndexedSymbol(
                    symbolId = id,
                    versionId = version.id,
                    kind = SymbolKind.METHOD,
                    sourceInternalName = klass.srcName,
                    sourceName = method.srcName,
                    sourceDescriptor = method.srcDesc,
                    ownerSourceInternalName = klass.srcName,
                    canonicalName = canonical.name,
                    canonicalQualifiedName = canonical.qualifiedName,
                    canonicalBinaryName = classCanonical.binaryName,
                    packageName = classSymbol.packageName,
                    modifiers = method.modifiers,
                    signature = method.signature,
                    superClass = null,
                    interfaces = emptyList(),
                    aliases = aliases
                )
            }
        }

        return VersionIndex(
            versionId = version.id,
            releaseTime = version.releaseTime,
            indexedAt = Instant.now(),
            symbolsById = symbols
        )
    }

    private fun resolveNamespaceIds(tree: MappingTreeView): Map<String, Int> {
        val map = linkedMapOf(MappingNamespace.SOURCE.wireName to MappingTreeView.SRC_NAMESPACE_ID)
        for (namespace in MappingNamespace.entries.filter { it != MappingNamespace.SOURCE }) {
            val id = tree.getNamespaceId(namespace.wireName)
            if (id != MappingTreeView.NULL_NAMESPACE_ID) {
                map[namespace.wireName] = id
            }
        }
        return map
    }

    private fun buildClassAliases(
        klass: MappingTreeView.ClassMappingView,
        namespaceIds: Map<String, Int>
    ): Map<String, SymbolAlias> {
        val aliases = linkedMapOf<String, SymbolAlias>()
        aliases[MappingNamespace.SOURCE.wireName] = SymbolAlias(
            namespace = MappingNamespace.SOURCE.wireName,
            name = klass.srcName.substringAfterLast('/'),
            qualifiedName = klass.srcName.toBinaryName(),
            binaryName = klass.srcName.toBinaryName(),
            descriptor = null,
            ownerBinaryName = null
        )

        for ((namespace, id) in namespaceIds) {
            if (id == MappingTreeView.SRC_NAMESPACE_ID) {
                continue
            }
            val internalName = klass.getDstName(id) ?: continue
            val binaryName = internalName.toBinaryName()
            aliases[namespace] = SymbolAlias(
                namespace = namespace,
                name = binaryName.substringAfterLast('.'),
                qualifiedName = binaryName,
                binaryName = binaryName,
                descriptor = null,
                ownerBinaryName = null
            )
        }

        return aliases
    }

    private fun buildMemberAliases(
        member: MappingTreeView.MemberMappingView,
        ownerAliases: Map<String, SymbolAlias>,
        namespaceIds: Map<String, Int>
    ): Map<String, SymbolAlias> {
        val aliases = linkedMapOf<String, SymbolAlias>()
        aliases[MappingNamespace.SOURCE.wireName] = SymbolAlias(
            namespace = MappingNamespace.SOURCE.wireName,
            name = member.srcName,
            qualifiedName = ownerAliases[MappingNamespace.SOURCE.wireName]?.binaryName?.let { "$it#${member.srcName}" },
            binaryName = null,
            descriptor = member.srcDesc,
            ownerBinaryName = ownerAliases[MappingNamespace.SOURCE.wireName]?.binaryName
        )

        for ((namespace, id) in namespaceIds) {
            if (id == MappingTreeView.SRC_NAMESPACE_ID) {
                continue
            }

            val mappedName = member.getDstName(id) ?: continue
            val ownerBinaryName = ownerAliases[namespace]?.binaryName ?: ownerAliases[config.primaryNamespace.wireName]?.binaryName
            aliases[namespace] = SymbolAlias(
                namespace = namespace,
                name = mappedName,
                qualifiedName = ownerBinaryName?.let { "$it#$mappedName" },
                binaryName = null,
                descriptor = member.getDstDesc(id),
                ownerBinaryName = ownerBinaryName
            )
        }

        return aliases
    }

    private fun chooseCanonicalAlias(aliases: Map<String, SymbolAlias>): SymbolAlias {
        return aliases[config.primaryNamespace.wireName]
            ?: aliases[MappingNamespace.MOJANG.wireName]
            ?: aliases[MappingNamespace.SPIGOT.wireName]
            ?: aliases[MappingNamespace.INTERMEDIARY.wireName]
            ?: aliases.values.first()
    }

    private fun isServerSide(aliases: Map<String, SymbolAlias>): Boolean {
        return aliases.values.asSequence()
            .mapNotNull(SymbolAlias::binaryName)
            .any { binaryName ->
                binaryName.startsWith("net.minecraft.") && !binaryName.startsWith("net.minecraft.client.")
            }
    }

    companion object {
        fun classSymbolId(versionId: String, sourceInternalName: String): String =
            "$versionId|class|$sourceInternalName"

        fun memberSymbolId(
            versionId: String,
            kind: SymbolKind,
            ownerSourceInternalName: String,
            sourceName: String,
            sourceDescriptor: String?
        ): String = "$versionId|${kind.wireName}|$ownerSourceInternalName|$sourceName|${sourceDescriptor.orEmpty()}"
    }
}

fun String.toBinaryName(): String = replace('/', '.')
