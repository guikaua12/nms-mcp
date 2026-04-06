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
| `NMS_MCP_MAX_SEARCH_RESULTS` | `10` | Default search/resolve result limit, clamped to `1..50`. |

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

## Example Lookups

Resolve a known class:

```json
{
  "name": "MinecraftServer",
  "version": "1.21.5",
  "kind": "class",
  "namespace": "mojang"
}
```

That resolves to the grounded symbol id:

```text
1.21.5|class|net/minecraft/server/MinecraftServer
```

Search for a field by owner and meaning:

```json
{
  "query": "ServerPlayer connection",
  "version": "1.21.5",
  "kind": "field",
  "namespace": "mojang"
}
```

Describe or decompile a previously returned symbol:

```json
{
  "symbol_id": "1.21.5|class|net/minecraft/server/MinecraftServer"
}
```

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
