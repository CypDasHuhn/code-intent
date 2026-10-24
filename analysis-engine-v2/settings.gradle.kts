rootProject.name = "code-intent-v2"

include(
    ":contracts",
    ":core",
    ":packs",
    ":index",
    ":knowledge",
    ":languages",
    ":syntax-treesitter",
    ":lsp",
    ":intent-pipeline",
    ":runtime",
    ":ai",
    ":ipc",
    ":cli",
    ":server",
    ":mcp",
    ":test-fixtures",
    ":integration-tests"
)

project(":contracts").projectDir = file("contracts")
project(":core").projectDir = file("core")
project(":packs").projectDir = file("packs")
project(":index").projectDir = file("index")
project(":knowledge").projectDir = file("knowledge")
project(":languages").projectDir = file("languages")
project(":syntax-treesitter").projectDir = file("syntax-treesitter")
project(":lsp").projectDir = file("lsp")
project(":intent-pipeline").projectDir = file("intent-pipeline")
project(":runtime").projectDir = file("runtime")
project(":ai").projectDir = file("ai")
project(":ipc").projectDir = file("ipc")
project(":cli").projectDir = file("apps/cli")
project(":server").projectDir = file("apps/server")
project(":mcp").projectDir = file("apps/mcp")
project(":test-fixtures").projectDir = file("tests/fixtures")
project(":integration-tests").projectDir = file("tests/integration")