# nms-mcp

`nms-mcp` is a stdio Model Context Protocol (MCP) server for grounded Minecraft server-side NMS lookups across versions. It builds versioned symbol indexes from Takenaka mapping data, persists them to SQLite, and can optionally return short decompiled snippets remapped to Mojang names.

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
2. The first request for a version resolves mapping data through Takenaka, optionally trying a prebuilt mapping bundle first.
3. The mapping tree is converted into a per-version symbol index with aliases for supported namespaces such as `mojang`, `spigot`, `intermediary`, `yarn`, and `quilt`.
4. Indexed symbols are stored in SQLite so later lookups do not need to rebuild the same version.
5. Snippet requests download the matching server jar, remap the target class to Mojang names with Tiny Remapper, and decompile it with CFR.

## Requirements

- JDK 21
- Network access on first use to fetch the Mojang version manifest, mapping inputs, and server jars for snippet generation

## Running

Run the server directly during development:

```bash
./gradlew run
```

On Windows:

```powershell
.\gradlew.bat run
```

To build a distributable archive:

```bash
./gradlew distZip
```

That produces `build/distributions/nms-mcp-<version>.zip`, which contains `bin/nms-mcp` and `bin/nms-mcp.bat` launchers suitable for MCP client configuration.

## MCP Client Setup

This project is a stdio MCP server. After building a distribution, point your client at the generated launcher.

Example on Windows:

```json
{
  "mcpServers": {
    "nms_mappings": {
      "command": "C:\\path\\to\\nms-mcp\\nms-mcp.bat"
    }
  }
}
```

Example on macOS/Linux:

```json
{
  "mcpServers": {
    "nms_mappings": {
      "command": "/path/to/nms-mcp/bin/nms-mcp"
    }
  }
}
```

## Configuration

The server is configured entirely through environment variables.

| Variable | Default | Description |
| --- | --- | --- |
| `NMS_MCP_CACHE_DIR` | `cache` | Cache root for the version manifest, SQLite index, snippet artifacts, and mapping workspace. |
| `NMS_MCP_BUNDLE_COORDINATE` | unset | Optional Maven coordinate for a prebuilt Takenaka bundle in the form `group:artifact:version`. |
| `NMS_MCP_DEFAULT_VERSION` | latest release | Default Minecraft version when a request omits `version`. |
| `NMS_MCP_PRIMARY_NAMESPACE` | `mojang` | Preferred canonical namespace. Supported values: `source`, `mojang`, `spigot`, `searge`, `intermediary`, `yarn`, `quilt`, `hashed`. |
| `NMS_MCP_ENABLE_SNIPPETS` | `true` | Enables `get_symbol_snippet`. Accepts `1`, `true`, or `yes` for enabled. |
| `NMS_MCP_MAX_SEARCH_RESULTS` | `10` | Default search/resolve result limit, clamped to `1..50`. |

## Cache Layout

Under the default `cache/` directory, the server creates data like:

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

Run tests with:

```bash
./gradlew test
```

Current tests cover:

- search ranking behavior
- runtime classpath compatibility for Takenaka and snippet support

## License and Credits

`nms-mcp` is licensed under the Apache License 2.0. Copyright 2026 Guilherme Kaua da Silva. Direct dependency attributions live in `THIRD_PARTY_NOTICES.md`, and the license texts they rely on are included in `THIRD_PARTY_LICENSES/`. The Gradle `distZip` archive includes these files.

Main acknowledgement:

- [Takenaka](https://github.com/zlataovce/takenaka) made this project possible. It does the heavy lifting for reconciling versioned Minecraft mappings, and `nms-mcp` is built around that capability.

Other direct dependencies currently credited there include:

- [Java MCP SDK](https://github.com/modelcontextprotocol/java-sdk) for MCP server, transport, and schema primitives.
- [Fabric mapping-io](https://github.com/FabricMC/mapping-io), [Fabric tiny-remapper](https://github.com/FabricMC/tiny-remapper), and [CFR](https://www.benf.org/other/cfr/) for mapping access, remapping, and snippet decompilation.
- [Jackson Kotlin module](https://github.com/FasterXML/jackson-module-kotlin), [SQLite JDBC](https://github.com/xerial/sqlite-jdbc), [SLF4J](https://www.slf4j.org/), [Kotlin](https://kotlinlang.org/), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines), and [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) for runtime, persistence, logging, and serialization support.

`tiny-remapper` is the only current direct dependency under LGPL-3.0. See `THIRD_PARTY_NOTICES.md` before changing the packaging format for releases.
