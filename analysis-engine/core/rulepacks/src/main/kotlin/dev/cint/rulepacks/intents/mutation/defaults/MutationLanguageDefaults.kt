package dev.cint.rulepacks.intents.mutation.defaults

import dev.cint.rulepacks.intents.mutation.MutationLanguageConfig

internal val LANGUAGE_DEFAULTS: Map<String, MutationLanguageConfig> =
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
