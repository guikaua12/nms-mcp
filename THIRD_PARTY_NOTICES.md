# Third-Party Notices

Copyright 2026 Guilherme Kaua da Silva.

The original `nms-mcp` source code in this repository is licensed under the Apache License, Version 2.0. Third-party components used by this project remain under their own licenses.

This file covers the direct Gradle dependencies declared in `build.gradle.kts`. It is intended to travel with both the source tree and the Gradle `application` distribution.

Project names and trademarks are used only to identify upstream software. This project is not affiliated with or endorsed by the upstream maintainers listed below.

## Direct Dependencies

| Component | Coordinates | Used For | Upstream | License | Local License Text |
| --- | --- | --- | --- | --- | --- |
| Kotlin | `org.jetbrains.kotlin:kotlin-stdlib:2.1.21`; `org.jetbrains.kotlin:kotlin-test:2.1.21` | Kotlin runtime plus test assertions and JUnit 5 integration | https://kotlinlang.org/ | Apache License 2.0 | `THIRD_PARTY_LICENSES/Apache-2.0.txt` |
| Java MCP SDK | `io.modelcontextprotocol.sdk:mcp:0.14.0` | MCP server, stdio transport, JSON mapping, and schema types | https://github.com/modelcontextprotocol/java-sdk | MIT License | `THIRD_PARTY_LICENSES/Java-MCP-SDK-MIT.txt` |
| Jackson Kotlin Module | `com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3` | JSON mapping for persisted index data and MCP payload rendering | https://github.com/FasterXML/jackson-module-kotlin | Apache License 2.0 | `THIRD_PARTY_LICENSES/Apache-2.0.txt` |
| SQLite JDBC | `org.xerial:sqlite-jdbc:3.49.1.0` | Embedded SQLite driver for the symbol index database | https://github.com/xerial/sqlite-jdbc | Apache License 2.0 | `THIRD_PARTY_LICENSES/Apache-2.0.txt` |
| SLF4J Simple | `org.slf4j:slf4j-simple:2.0.16` | Simple logging provider on the runtime classpath | https://www.slf4j.org/ | MIT License | `THIRD_PARTY_LICENSES/SLF4J-MIT.txt` |
| kotlinx.coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1` | Blocking and asynchronous execution support | https://github.com/Kotlin/kotlinx.coroutines | Apache License 2.0 | `THIRD_PARTY_LICENSES/Apache-2.0.txt` |
| kotlinx.serialization JSON | `org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1` | Declared direct dependency for Kotlin JSON serialization support | https://github.com/Kotlin/kotlinx.serialization | Apache License 2.0 | `THIRD_PARTY_LICENSES/Apache-2.0.txt` |
| Takenaka | `me.kcra.takenaka:core:1.2.1-SNAPSHOT`; `me.kcra.takenaka:generator-common:1.2.1-SNAPSHOT` | Resolving, reconciling, and bundling versioned Minecraft mapping data | https://github.com/zlataovce/takenaka | Apache License 2.0 | `THIRD_PARTY_LICENSES/Apache-2.0.txt` |
| mapping-io | `net.fabricmc:mapping-io:0.4.2` | Reading and working with mapping trees | https://github.com/FabricMC/mapping-io | Apache License 2.0 | `THIRD_PARTY_LICENSES/Apache-2.0.txt` |
| tiny-remapper | `net.fabricmc:tiny-remapper:0.13.1` | Remapping server classes to Mojang names before decompilation | https://github.com/FabricMC/tiny-remapper | GNU Lesser General Public License v3.0 | `THIRD_PARTY_LICENSES/LGPL-3.0.txt` and `THIRD_PARTY_LICENSES/GPL-3.0.txt` |
| CFR | `org.benf:cfr:0.152` | Java bytecode decompilation for snippet generation | https://www.benf.org/other/cfr/ | MIT License | `THIRD_PARTY_LICENSES/CFR-MIT.txt` |

## Packaging Notes

- The Gradle `application` distribution ships the project jar and dependency jars as separate files under `lib/`.
- `tiny-remapper` is used and distributed unmodified as a separate library jar. If packaging changes to a shaded or fat jar, native image, or another combined format, re-check LGPL obligations before publishing that build.
- If the direct dependency list changes, update this file and `THIRD_PARTY_LICENSES/` before the next release.
