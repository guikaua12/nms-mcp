# nms-mcp

`nms-mcp` is a stdio Model Context Protocol (MCP) server for grounded Minecraft server-side NMS lookups across versions. It builds versioned symbol indexes from [Takenaka](https://github.com/zlataovce/takenaka) mapping data, persists them to SQLite, and can optionally return short decompiled snippets remapped to Mojang names.

The goal is simple: let an agent resolve real `net.minecraft.*` classes, fields, and methods without guessing. Only server-side symbols are indexed; `net.minecraft.client.*` is filtered out.

This project would not be possible in its current form without [Takenaka](https://github.com/zlataovce/takenaka). `nms-mcp` depends on Takenaka's mapping reconciliation work, and the core indexing flow here is built on top of that upstream library.

## What It Exposes

### Tools

- `search_symbols`: free-text search across classes, methods, and fields for a specific version.
- `resolve_symbol`: exact or near-exact lookup for a class, field, or method name.
- `describe_symbol`: full grounded record for a previously returned `symbol_id`.
- `compare_symbol_versions`: aligns one symbol across two Minecraft versions and reports renames, moves, and removals.
- `get_symbol_snippet`: decompiles a short excerpt for the symbol's containing class in Mojang names.

### Resources

- `nms://versions`: release versions available from Mojang plus local indexed state.
- `nms://{version}/class/{binaryName}`: grounded class record for a specific binary name.
- `nms://{version}/member/{ownerBinaryName}/{kind}/{signatureKey}`: grounded method or field record.

`signatureKey` is the base64url-encoded form of `name|descriptor`.

## How It Works

1. On startup, the server loads the Mojang version manifest into the local cache.
2. The first request for a version resolves mapping data through [Takenaka](https://github.com/zlataovce/takenaka), optionally trying a prebuilt mapping bundle first.
3. The mapping tree is converted into a per-version symbol index with aliases for supported namespaces such as `mojang`, `spigot`, `intermediary`, `yarn`, and `quilt`.
4. Indexed symbols are stored in SQLite so later lookups do not need to rebuild the same version.
5. Snippet requests download the matching server jar, remap the target class to Mojang names with Tiny Remapper, and decompile it with CFR.

## Requirements

- Java 21
- Network access on first use to fetch the Mojang version manifest, mapping inputs, and server jars for snippet generation

## Install

Public installs are built around GitHub Releases rather than local source builds.

1. Download a release artifact from the repository's GitHub Releases page.
2. Pick one of the supported launch paths:
   - Extracted application distribution:
     - `nms-mcp-<version>.zip`
     - `nms-mcp-<version>.tar.gz`
   - Shaded executable jar:
     - `nms-mcp-<version>-all.jar`
3. If you downloaded a distribution archive, extract it to a stable location and keep the extracted `bin/` and `lib/` directories together.
4. Paste the MCP client config for your platform.
5. Restart the MCP client so it can launch the server.

`SHA256SUMS.txt` is published alongside each release. The thin `nms-mcp-<version>.jar` remains a normal build output for internal use and is not the supported `java -jar` entrypoint.

You normally do not start `nms-mcp` manually. The MCP client launches it for you when needed.

## MCP Client Setup

### Windows Launcher

Point the client at the extracted batch launcher, not the extracted folder root.

```json
{
  "mcpServers": {
    "nms_mappings": {
      "command": "C:\\path\\to\\nms-mcp-<version>\\bin\\nms-mcp.bat"
    }
  }
}
```

### Linux Launcher

```json
{
  "mcpServers": {
    "nms_mappings": {
      "command": "/path/to/nms-mcp-<version>/bin/nms-mcp"
    }
  }
}
```

### macOS Launcher

```json
{
  "mcpServers": {
    "nms_mappings": {
      "command": "/Applications/nms-mcp-<version>/bin/nms-mcp"
    }
  }
}
```

### Cross-Platform `java -jar`

```json
{
  "mcpServers": {
    "nms_mappings": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/nms-mcp-<version>-all.jar"
      ]
    }
  }
}
```

## Configuration

The server is configured entirely through environment variables.

| Variable | Default | Description |
| --- | --- | --- |
| `NMS_MCP_CACHE_DIR` | OS-native cache directory | Cache root for the version manifest, SQLite index, snippet artifacts, and mapping workspace. This override has the highest precedence. |
| `NMS_MCP_BUNDLE_COORDINATE` | unset | Optional Maven coordinate for a prebuilt Takenaka bundle in the form `group:artifact:version`. |
| `NMS_MCP_DEFAULT_VERSION` | latest release | Default Minecraft version when a request omits `version`. |
| `NMS_MCP_PRIMARY_NAMESPACE` | `mojang` | Preferred canonical namespace. Supported values: `source`, `mojang`, `spigot`, `searge`, `intermediary`, `yarn`, `quilt`, `hashed`. |
| `NMS_MCP_ENABLE_SNIPPETS` | `true` | Enables `get_symbol_snippet`. Accepts `1`, `true`, or `yes` for enabled. |

## Default Cache Location

If `NMS_MCP_CACHE_DIR` is unset, `nms-mcp` defaults to the platform cache location below:

- Windows: `${LOCALAPPDATA}\nms-mcp\cache`
- macOS: `${HOME}/Library/Caches/nms-mcp`
- Linux: `${XDG_CACHE_HOME:-$HOME/.cache}/nms-mcp`
- Fallback: relative `cache` when the required OS-specific location cannot be resolved

## Cache Layout

Under the configured cache root, the server creates data like:

- `version_manifest_v2.json`: Mojang version manifest cache.
- `index.sqlite`: persisted symbol index for versions already built.
- `takenaka/`: Takenaka workspace and resolved mapping inputs.
- `bundles/`: downloaded bundle jars when `NMS_MCP_BUNDLE_COORDINATE` is set.
- `snippets/`: server jars, remapped classes, and decompilation inputs for snippet generation.

## Example Tool Calls

The examples below show the tool argument object you pass to the MCP server and the response object you get back from that tool. They are trimmed for brevity, but the field names and value shapes come from a live `nms-mappings` server.

### `resolve_symbol`

Resolve a known Mojang-mapped class name in a specific version:

```json
{
  "name": "MinecraftServer",
  "version": "1.21.5",
  "kind": "class",
  "namespace": "mojang",
  "limit": 5
}
```

Representative response:

```json
{
  "exact": true,
  "ambiguous": false,
  "results": [
    {
      "score": 1000,
      "reason": "exact alias match",
      "matchedNamespace": "mojang",
      "matchedValue": "net.minecraft.server.MinecraftServer",
      "symbol": {
        "symbolId": "1.21.5|class|net/minecraft/server/MinecraftServer",
        "versionId": "1.21.5",
        "kind": "class",
        "canonicalName": "MinecraftServer",
        "canonicalQualifiedName": "net.minecraft.server.MinecraftServer",
        "sourceInternalName": "net/minecraft/server/MinecraftServer",
        "availableNamespaces": [
          "hashed",
          "intermediary",
          "mojang",
          "quilt",
          "searge",
          "source",
          "spigot",
          "yarn"
        ],
        "aliases": {
          "mojang": {
            "qualifiedName": "net.minecraft.server.MinecraftServer"
          },
          "intermediary": {
            "qualifiedName": "net.minecraft.server.MinecraftServer"
          }
        }
      }
    }
  ]
}
```

### `search_symbols`

Search by meaning instead of exact name. This is useful when you know the owner and intent but not the obfuscated source name:

```json
{
  "query": "ServerPlayer connection",
  "version": "1.21.5",
  "kind": "field",
  "namespace": "mojang",
  "limit": 5
}
```

Representative response:

```json
{
  "queriedVersion": "1.21.5",
  "actualVersion": "1.21.5",
  "results": [
    {
      "score": 140,
      "reason": "matched all query terms",
      "matchedNamespace": "mojang",
      "matchedValue": "net.minecraft.server.level.ServerPlayer#connection",
      "symbol": {
        "symbolId": "1.21.5|field|asc|f|Late;",
        "kind": "field",
        "canonicalName": "connection",
        "canonicalQualifiedName": "net.minecraft.server.level.ServerPlayer#connection",
        "sourceName": "f",
        "sourceDescriptor": "Late;",
        "aliases": {
          "mojang": {
            "name": "connection",
            "ownerBinaryName": "net.minecraft.server.level.ServerPlayer",
            "descriptor": "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"
          },
          "yarn": {
            "name": "networkHandler",
            "ownerBinaryName": "net.minecraft.server.network.ServerPlayerEntity"
          }
        }
      }
    }
  ]
}
```

### `describe_symbol`

Once a search or resolve step returns a `symbolId`, you can fetch the full grounded record directly:

```json
{
  "symbol_id": "1.21.5|field|asc|f|Late;"
}
```

Representative response:

```json
{
  "symbolId": "1.21.5|field|asc|f|Late;",
  "versionId": "1.21.5",
  "kind": "field",
  "canonicalName": "connection",
  "canonicalQualifiedName": "net.minecraft.server.level.ServerPlayer#connection",
  "canonicalBinaryName": "net.minecraft.server.level.ServerPlayer",
  "packageName": "net.minecraft.server.level",
  "sourceInternalName": "asc",
  "sourceName": "f",
  "sourceDescriptor": "Late;",
  "availableNamespaces": [
    "hashed",
    "intermediary",
    "mojang",
    "quilt",
    "searge",
    "source",
    "yarn"
  ],
  "aliases": {
    "mojang": {
      "name": "connection",
      "qualifiedName": "net.minecraft.server.level.ServerPlayer#connection",
      "descriptor": "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"
    },
    "intermediary": {
      "name": "field_13987",
      "qualifiedName": "net.minecraft.class_3222#field_13987"
    }
  }
}
```

### `compare_symbol_versions`

Use the symbol id from one version and ask the server to line it up against another version:

```json
{
  "from_version": "1.20.4",
  "to_version": "1.21.5",
  "namespace": "mojang",
  "symbol_id_or_name": "1.20.4|field|ane|c|Laoc;"
}
```

Representative response:

```json
{
  "relationship": "equivalent",
  "from": {
    "symbolId": "1.20.4|field|ane|c|Laoc;",
    "canonicalQualifiedName": "net.minecraft.server.level.ServerPlayer#connection",
    "sourceName": "c",
    "sourceDescriptor": "Laoc;"
  },
  "to": {
    "symbolId": "1.21.5|field|asc|f|Late;",
    "canonicalQualifiedName": "net.minecraft.server.level.ServerPlayer#connection",
    "sourceName": "f",
    "sourceDescriptor": "Late;"
  },
  "notes": []
}
```

This example shows the common case where the Mojang-facing symbol stayed equivalent, but the source owner/name/descriptor changed across versions.

### `get_symbol_snippet`

Snippet requests return a short decompiled excerpt in Mojang names for the containing class:

```json
{
  "symbol_id": "1.21.5|field|asc|f|Late;",
  "lines_before": 2,
  "lines_after": 4
}
```

Representative response:

```json
{
  "symbolId": "1.21.5|field|asc|f|Late;",
  "version": "1.21.5",
  "namespace": "mojang",
  "classBinaryName": "net.minecraft.server.level.ServerPlayer",
  "startLine": 432,
  "endLine": 438,
  "excerpt": "    private static final boolean DEFAULT_SEEN_CREDITS = false;\n    private static final boolean DEFAULT_SPAWN_EXTRA_PARTICLES_ON_FALL = false;\n    public ServerGamePacketListenerImpl connection;\n    public final MinecraftServer server;\n    public final ServerPlayerGameMode gameMode;\n    private final PlayerAdvancements advancements;\n    private final ServerStatsCounter stats;"
}
```

## Example Resources

The server also exposes read-only resources and templates. Resource reads return Markdown that wraps the underlying JSON payload.

Read the available versions and local cache state:

```text
nms://versions
```

Representative response body:

````markdown
# NMS Versions

```json
[
  {
    "version": "26.1.1",
    "releaseTime": "2026-04-01T09:06:36Z",
    "isLatestRelease": true,
    "indexed": false
  },
  {
    "version": "1.21.5",
    "releaseTime": "2025-03-25T12:14:58Z",
    "isLatestRelease": false,
    "indexed": true
  }
]
```
````

Read a grounded class record by version and binary name:

```text
nms://1.21.5/class/net.minecraft.server.MinecraftServer
```

Representative response body:

````markdown
# net.minecraft.server.MinecraftServer

```json
{
  "symbolId": "1.21.5|class|net/minecraft/server/MinecraftServer",
  "kind": "class",
  "canonicalQualifiedName": "net.minecraft.server.MinecraftServer",
  "sourceInternalName": "net/minecraft/server/MinecraftServer"
}
```
````

Read a grounded member record by owner, kind, and signature key:

```text
nms://1.21.5/member/net.minecraft.server.level.ServerPlayer/field/Y29ubmVjdGlvbnxMbmV0L21pbmVjcmFmdC9zZXJ2ZXIvbmV0d29yay9TZXJ2ZXJHYW1lUGFja2V0TGlzdGVuZXJJbXBsOw
```

That `signatureKey` is the base64url-encoded form of:

```text
connection|Lnet/minecraft/server/network/ServerGamePacketListenerImpl;
```

Representative response body:

````markdown
# net.minecraft.server.level.ServerPlayer#connection

```json
{
  "symbolId": "1.21.5|field|asc|f|Late;",
  "kind": "field",
  "canonicalQualifiedName": "net.minecraft.server.level.ServerPlayer#connection",
  "sourceName": "f",
  "sourceDescriptor": "Late;"
}
```
````

## Development

For local development, you can still run the server directly:

```bash
./gradlew run
```

On Windows:

```powershell
.\gradlew.bat run
```

Build and verify the public release artifacts with:

```bash
./gradlew test shadowJar distZip distTar verifyReleaseArtifacts
```

Generate release checksums locally with:

```bash
./gradlew generateReleaseChecksums
```

Tag releases as `v<project.version>` to trigger the GitHub Release workflow. For example, version `0.1.0` is released from tag `v0.1.0`.

Current tests cover:

- cache directory resolution across supported operating systems
- search ranking behavior
- runtime classpath compatibility for Takenaka and snippet support

## License and Credits

`nms-mcp` is licensed under the Apache License 2.0. Copyright 2026 Guilherme Kaua da Silva. Direct dependency attributions live in `THIRD_PARTY_NOTICES.md`, and the license texts they rely on are included in `THIRD_PARTY_LICENSES/`. The Gradle distribution archives include these files.

Main acknowledgement:

- [Takenaka](https://github.com/zlataovce/takenaka) made this project possible. It does the heavy lifting for reconciling versioned Minecraft mappings, and `nms-mcp` is built around that capability.

Other direct dependencies currently credited there include:

- [Java MCP SDK](https://github.com/modelcontextprotocol/java-sdk) for MCP server, transport, and schema primitives.
- [Fabric mapping-io](https://github.com/FabricMC/mapping-io), [Fabric tiny-remapper](https://github.com/FabricMC/tiny-remapper), and [CFR](https://www.benf.org/other/cfr/) for mapping access, remapping, and snippet decompilation.
- [Jackson Kotlin module](https://github.com/FasterXML/jackson-module-kotlin), [SQLite JDBC](https://github.com/xerial/sqlite-jdbc), [SLF4J](https://www.slf4j.org/), [Kotlin](https://kotlinlang.org/), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines), and [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) for runtime, persistence, logging, and serialization support.

`tiny-remapper` is the only current direct dependency under LGPL-3.0. See `THIRD_PARTY_NOTICES.md` before changing the packaging format for releases.
