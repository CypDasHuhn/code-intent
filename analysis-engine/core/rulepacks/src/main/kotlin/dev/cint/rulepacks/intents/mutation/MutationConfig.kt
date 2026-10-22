package dev.cint.rulepacks.intents.mutation

import dev.cint.api.ConfigField
import dev.cint.api.ConfigSchema
import dev.cint.api.LanguageConfig

internal val DURABLE_COMPOUND_MUTATION_CALLS =
    setOf(
        "findOneAndUpdate",
        "findOneAndDelete",
        "findOneAndReplace",
        "findOneAndIncrement",
        "findOneAndDecrement",
        "findOneAndModify",
        "findAndModify",
    )

internal val LOCAL_ATOMIC_MUTATION_CALLS =
    setOf(
        "getAndUpdate",
        "getAndIncrement",
        "getAndDecrement",
    )

private val DEFAULT_PERSISTENCE_CALLS =
    setOf(
        "save",
        "saveAll",
        "saveAndFlush",
        "persist",
        "merge",
        "flush",
        "commit",
        "update",
        "updateAll",
        "updateOne",
        "updateMany",
        "updateById",
        "upsert",
        "put",
        "putObject",
        "putItem",
        "upload",
        "uploadObject",
        "copyObject",
        "writeObject",
        "insert",
        "insertOne",
        "insertMany",
        "insertAll",
        "delete",
        "deleteAll",
        "deleteById",
        "deleteOne",
        "deleteMany",
        "deleteAllById",
        "remove",
        "removeById",
        "publish",
        "publishEvent",
        "publishAll",
        "emit",
        "send",
        "sendAsync",
        "sendMessage",
        "sendMessageAsync",
        "convertAndSend",
        "sendAndReceive",
        "write",
        "writeText",
        "writeBytes",
        "writeString",
        "writeFile",
        "writeFileSync",
        "appendFile",
        "appendFileSync",
        "appendText",
        "appendBytes",
        "createNewFile",
        "deleteIfExists",
        "deleteRecursively",
        "unlink",
        "unlinkSync",
        "SaveChanges",
        "SaveChangesAsync",
        "WriteAllText",
        "WriteAllTextAsync",
        "WriteAllBytes",
        "WriteAllBytesAsync",
        "WriteAllLines",
        "WriteAllLinesAsync",
        "AppendAllText",
        "AppendAllTextAsync",
        "AppendAllLines",
        "AppendAllLinesAsync",
        "Delete",
        "Move",
        "Copy",
        "CreateDirectory",
    ) + DURABLE_COMPOUND_MUTATION_CALLS

private val DEFAULT_PERSISTENCE_PATHS =
    setOf(
        "Files.write",
        "Files.writeString",
        "Files.delete",
        "Files.deleteIfExists",
        "Files.copy",
        "Files.move",
        "java.nio.file.Files.write",
        "java.nio.file.Files.writeString",
        "java.nio.file.Files.delete",
        "java.nio.file.Files.deleteIfExists",
        "java.nio.file.Files.copy",
        "java.nio.file.Files.move",
        "fs.writeFile",
        "fs.writeFileSync",
        "fs.appendFile",
        "fs.appendFileSync",
        "fs.unlink",
        "fs.unlinkSync",
        "fs.rm",
        "fs.rmSync",
        "fs.promises.writeFile",
        "fs.promises.appendFile",
        "fs.promises.unlink",
        "fs.promises.rm",
        "File.WriteAllText",
        "File.WriteAllTextAsync",
        "File.WriteAllBytes",
        "File.WriteAllBytesAsync",
        "File.WriteAllLines",
        "File.WriteAllLinesAsync",
        "File.AppendAllText",
        "File.AppendAllTextAsync",
        "File.AppendAllLines",
        "File.AppendAllLinesAsync",
        "File.Delete",
        "File.Move",
        "File.Copy",
        "File.Create",
    )

private val DEFAULT_PERSISTENCE_QUALIFIERS =
    setOf(
        "repository",
        "repo",
        "dao",
        "entityManager",
        "entityManagerFactory",
        "sessionFactory",
        "database",
        "dataSource",
        "jdbcTemplate",
        "mongoTemplate",
        "mongo",
        "firestore",
        "dynamoDb",
        "bucket",
        "s3",
        "publisher",
        "producer",
        "eventBus",
        "kafka",
        "rabbit",
        "sqs",
        "sns",
        "file",
        "path",
        "paths",
        "files",
        "fs",
        "directory",
        "dbContext",
        "connection",
        "transaction",
    )

private val DEFAULT_PERSISTENCE_TYPES =
    setOf(
        "Repository",
        "CrudRepository",
        "JpaRepository",
        "MongoRepository",
        "EntityManager",
        "org.hibernate.Session",
        "Database",
        "File",
        "FileInfo",
        "Path",
        "Files",
        "Directory",
        "Publisher",
        "Producer",
        "FileSystem",
        "Bucket",
        "javax.jms.Queue",
        "jakarta.jms.Queue",
        "DbContext",
        "DbSet",
        "Connection",
        "Transaction",
    )

private val DEFAULT_PERSISTENCE_PREFIXES =
    setOf(
        "save",
        "persist",
        "store",
        "publish",
        "emit",
        "send",
        "write",
        "delete",
        "remove",
        "update",
        "insert",
        "upsert",
        "flush",
        "commit",
        "dispatch",
        "enqueue",
        "produce",
        "append",
        "put",
        "upload",
        "copyObject",
        "writeObject",
        "sync",
    )

private val DEFAULT_MUTABLE_OPERATIONS =
    setOf(
        "add",
        "addAll",
        "addFirst",
        "addLast",
        "put",
        "putAll",
        "putIfAbsent",
        "remove",
        "removeAll",
        "removeIf",
        "removeAt",
        "clear",
        "insert",
        "insertAt",
        "set",
        "append",
        "push",
        "pop",
        "shift",
        "unshift",
        "splice",
        "sort",
        "reverse",
        "shuffle",
        "retainAll",
        "compute",
        "computeIfAbsent",
        "computeIfPresent",
        "merge",
        "replace",
        "replaceAll",
        "fill",
        "swap",
        "update",
        "delete",
        "Add",
        "AddRange",
        "Remove",
        "RemoveAt",
        "RemoveRange",
        "RemoveAll",
        "Clear",
        "Insert",
        "InsertRange",
        "Push",
        "Pop",
        "Enqueue",
        "Dequeue",
        "TryAdd",
        "TryRemove",
        "Sort",
        "Reverse",
    )

private val DEFAULT_MUTABLE_TYPES =
    setOf(
        "MutableList",
        "MutableMap",
        "MutableSet",
        "ArrayList",
        "LinkedList",
        "HashMap",
        "HashSet",
        "LinkedHashMap",
        "LinkedHashSet",
        "ArrayDeque",
        "List",
        "Map",
        "Set",
        "Collection",
        "Queue",
        "Deque",
        "Stack",
        "Dictionary",
    )

private val DEFAULT_MUTABLE_RECEIVER_NAMES =
    setOf(
        "list",
        "lists",
        "items",
        "values",
        "map",
        "maps",
        "dict",
        "dictionary",
        "set",
        "sets",
        "stack",
        "deque",
        "collection",
        "collections",
        "array",
        "arrays",
        "buffer",
        "cache",
        "results",
        "rows",
        "nodes",
        "children",
        "elements",
        "records",
        "entries",
        "data",
        "state",
    )

private val DEFAULT_ASSIGNMENT_PATTERNS = setOf("assignment", "increment", "decrement")

data class MutationLanguageConfig(
    val persistenceCalls: Set<String> = DEFAULT_PERSISTENCE_CALLS,
    val persistencePaths: Set<String> = DEFAULT_PERSISTENCE_PATHS,
    val persistenceQualifiers: Set<String> = DEFAULT_PERSISTENCE_QUALIFIERS,
    val persistenceTypes: Set<String> = DEFAULT_PERSISTENCE_TYPES,
    val persistencePrefixes: Set<String> = DEFAULT_PERSISTENCE_PREFIXES,
    val mutableOperations: Set<String> = DEFAULT_MUTABLE_OPERATIONS,
    val mutableTypes: Set<String> = DEFAULT_MUTABLE_TYPES,
    val mutableReceiverNames: Set<String> = DEFAULT_MUTABLE_RECEIVER_NAMES,
    val assignmentPatterns: Set<String> = DEFAULT_ASSIGNMENT_PATTERNS,
    val ignoreLoopCounters: Boolean = true,
    val ignoreBookkeeping: Boolean = true,
    val ignorePureCopies: Boolean = true,
) {
    fun withOverrides(
        values: Map<String, Any?>,
        language: String,
    ): MutationLanguageConfig = SCHEMA.decode(this, values, "$MUTATION_PACK_ID.${language.lowercase()}")

    companion object {
        internal val SCHEMA =
            ConfigSchema.of(
                ConfigField.strings<MutationLanguageConfig>(
                    "persistenceCalls",
                    { it.persistenceCalls },
                    { config, value -> config.copy(persistenceCalls = value) },
                ),
                ConfigField.strings<MutationLanguageConfig>(
                    "persistencePaths",
                    { it.persistencePaths },
                    { config, value -> config.copy(persistencePaths = value) },
                ),
                ConfigField.strings<MutationLanguageConfig>(
                    "persistenceQualifiers",
                    { it.persistenceQualifiers },
                    { config, value -> config.copy(persistenceQualifiers = value) },
                ),
                ConfigField.strings<MutationLanguageConfig>(
                    "persistenceTypes",
                    { it.persistenceTypes },
                    { config, value -> config.copy(persistenceTypes = value) },
                ),
                ConfigField.strings<MutationLanguageConfig>(
                    "persistencePrefixes",
                    { it.persistencePrefixes },
                    { config, value -> config.copy(persistencePrefixes = value) },
                ),
                ConfigField.strings<MutationLanguageConfig>(
                    "mutableOperations",
                    { it.mutableOperations },
                    { config, value -> config.copy(mutableOperations = value) },
                ),
                ConfigField.strings<MutationLanguageConfig>(
                    "mutableTypes",
                    { it.mutableTypes },
                    { config, value -> config.copy(mutableTypes = value) },
                ),
                ConfigField.strings<MutationLanguageConfig>(
                    "mutableReceiverNames",
                    { it.mutableReceiverNames },
                    { config, value -> config.copy(mutableReceiverNames = value) },
                ),
                ConfigField.strings<MutationLanguageConfig>(
                    "assignmentPatterns",
                    { it.assignmentPatterns },
                    { config, value -> config.copy(assignmentPatterns = value) },
                ),
                ConfigField.boolean<MutationLanguageConfig>(
                    "ignoreLoopCounters",
                    { it.ignoreLoopCounters },
                    { config, value -> config.copy(ignoreLoopCounters = value) },
                ),
                ConfigField.boolean<MutationLanguageConfig>(
                    "ignoreBookkeeping",
                    { it.ignoreBookkeeping },
                    { config, value -> config.copy(ignoreBookkeeping = value) },
                ),
                ConfigField.boolean<MutationLanguageConfig>(
                    "ignorePureCopies",
                    { it.ignorePureCopies },
                    { config, value -> config.copy(ignorePureCopies = value) },
                ),
            )

        fun defaults(): Map<String, MutationLanguageConfig> = LANGUAGE_DEFAULTS
    }
}

data class MutationConfig(
    val languages: Map<String, MutationLanguageConfig> = MutationLanguageConfig.defaults(),
) {
    fun forLanguage(language: String): MutationLanguageConfig =
        LanguageConfig(languages).forLanguage(language, "kotlin") ?: MutationLanguageConfig()

    fun withOverrides(values: Map<String, Any?>): MutationConfig = SCHEMA.decode(this, values, MUTATION_PACK_ID)

    companion object {
        internal val SCHEMA =
            LanguageConfig.schema<MutationLanguageConfig, MutationConfig>(
                languageSchema = MutationLanguageConfig.SCHEMA,
                get = { it.languages },
                set = { config, languages -> config.copy(languages = languages) },
            )

        fun defaults(): Map<String, MutationLanguageConfig> = MutationLanguageConfig.defaults()

        fun from(values: Map<String, Any?>): MutationConfig = MutationConfig().withOverrides(values)
    }
}

private val LANGUAGE_DEFAULTS: Map<String, MutationLanguageConfig> =
    linkedMapOf(
        "kotlin" to MutationLanguageConfig(),
        "javascript" to
            MutationLanguageConfig(
                persistenceCalls =
                    DEFAULT_PERSISTENCE_CALLS +
                        setOf(
                            "updateOne",
                            "updateMany",
                            "deleteOne",
                            "deleteMany",
                            "insertOne",
                            "insertMany",
                            "findOneAndUpdate",
                            "findOneAndDelete",
                            "replaceOne",
                            "writeFile",
                            "writeFileSync",
                            "appendFile",
                            "appendFileSync",
                            "unlink",
                            "unlinkSync",
                            "rm",
                            "rmSync",
                            "mkdir",
                            "mkdirSync",
                        ),
            ),
        "typescript" to
            MutationLanguageConfig(
                persistenceCalls =
                    DEFAULT_PERSISTENCE_CALLS +
                        setOf(
                            "updateOne",
                            "updateMany",
                            "deleteOne",
                            "deleteMany",
                            "insertOne",
                            "insertMany",
                            "findOneAndUpdate",
                            "findOneAndDelete",
                            "replaceOne",
                            "writeFile",
                            "writeFileSync",
                            "appendFile",
                            "appendFileSync",
                            "unlink",
                            "unlinkSync",
                            "rm",
                            "rmSync",
                            "mkdir",
                            "mkdirSync",
                        ),
            ),
        "csharp" to
            MutationLanguageConfig(
                persistenceCalls =
                    DEFAULT_PERSISTENCE_CALLS +
                        setOf(
                            "Add",
                            "AddAsync",
                            "AddRange",
                            "Attach",
                            "Save",
                            "SaveAsync",
                            "Update",
                            "UpdateAsync",
                            "Remove",
                            "RemoveAsync",
                            "RemoveRange",
                            "DeleteAsync",
                            "Publish",
                            "PublishAsync",
                            "Send",
                            "SendAsync",
                            "Insert",
                            "InsertAsync",
                            "Upsert",
                            "UpsertAsync",
                            "Write",
                            "ExecuteSqlRaw",
                            "ExecuteSqlRawAsync",
                            "ExecuteUpdate",
                            "ExecuteDelete",
                        ),
                persistencePaths =
                    DEFAULT_PERSISTENCE_PATHS +
                        setOf(
                            "File.AppendAllLines",
                            "File.CreateDirectory",
                            "Directory.CreateDirectory",
                            "Directory.Delete",
                        ),
            ),
    )
