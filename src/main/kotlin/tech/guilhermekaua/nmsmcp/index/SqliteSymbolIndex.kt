package tech.guilhermekaua.nmsmcp.index

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import tech.guilhermekaua.nmsmcp.model.IndexedSymbol
import tech.guilhermekaua.nmsmcp.model.VersionIndex
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlin.io.path.createDirectories

class SqliteSymbolIndex(
    private val databasePath: Path,
    private val objectMapper: ObjectMapper
) {
    init {
        databasePath.parent.createDirectories()
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS indexed_versions (
                        version_id TEXT PRIMARY KEY,
                        release_time TEXT NOT NULL,
                        indexed_at TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS symbols (
                        symbol_id TEXT PRIMARY KEY,
                        version_id TEXT NOT NULL,
                        payload_json TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_symbols_version ON symbols(version_id)")
            }
        }
    }

    fun indexedVersionIds(): Set<String> {
        connection().use { connection ->
            connection.prepareStatement("SELECT version_id FROM indexed_versions").use { statement ->
                statement.executeQuery().use { resultSet ->
                    val versions = linkedSetOf<String>()
                    while (resultSet.next()) {
                        versions += resultSet.getString("version_id")
                    }
                    return versions
                }
            }
        }
    }

    fun loadVersion(versionId: String): tech.guilhermekaua.nmsmcp.model.VersionIndex? {
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT release_time, indexed_at
                FROM indexed_versions
                WHERE version_id = ?
                """.trimIndent()
            ).use { versionStatement ->
                versionStatement.setString(1, versionId)
                versionStatement.executeQuery().use { versionResult ->
                    if (!versionResult.next()) {
                        return null
                    }

                    val releaseTime = Instant.parse(versionResult.getString("release_time"))
                    val indexedAt = Instant.parse(versionResult.getString("indexed_at"))
                    val symbols = linkedMapOf<String, IndexedSymbol>()

                    connection.prepareStatement(
                        """
                        SELECT symbol_id, payload_json
                        FROM symbols
                        WHERE version_id = ?
                        """.trimIndent()
                    ).use { symbolStatement ->
                        symbolStatement.setString(1, versionId)
                        symbolStatement.executeQuery().use { symbolResult ->
                            while (symbolResult.next()) {
                                val symbol = objectMapper.readValue<IndexedSymbol>(symbolResult.getString("payload_json"))
                                symbols[symbol.symbolId] = symbol
                            }
                        }
                    }

                    return VersionIndex(
                        versionId = versionId,
                        releaseTime = releaseTime,
                        indexedAt = indexedAt,
                        symbolsById = symbols
                    )
                }
            }
        }
    }

    fun storeVersion(versionIndex: VersionIndex) {
        connection().use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM symbols WHERE version_id = ?").use { statement ->
                    statement.setString(1, versionIndex.versionId)
                    statement.executeUpdate()
                }

                connection.prepareStatement(
                    """
                    INSERT INTO indexed_versions(version_id, release_time, indexed_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT(version_id) DO UPDATE SET
                        release_time = excluded.release_time,
                        indexed_at = excluded.indexed_at
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, versionIndex.versionId)
                    statement.setString(2, versionIndex.releaseTime.toString())
                    statement.setString(3, versionIndex.indexedAt.toString())
                    statement.executeUpdate()
                }

                connection.prepareStatement(
                    """
                    INSERT INTO symbols(symbol_id, version_id, payload_json)
                    VALUES (?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    for (symbol in versionIndex.symbolsById.values) {
                        statement.setString(1, symbol.symbolId)
                        statement.setString(2, versionIndex.versionId)
                        statement.setString(3, objectMapper.writeValueAsString(symbol))
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }

                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun connection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}")
}
