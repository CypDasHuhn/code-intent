rootProject.name = "code-intent"

val moduleIncludes = mutableListOf(
    ":contracts",
    ":core",
    ":default-rulepacks",
    ":index",
    ":knowledge",
    ":knowledge-persistence",
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
include(moduleIncludes)

project(":contracts").projectDir = file("contracts")
project(":core").projectDir = file("analysis/core")
project(":default-rulepacks").projectDir = file("analysis/default-rulepacks")
project(":index").projectDir = file("sources/index")
project(":knowledge").projectDir = file("analysis/knowledge")
project(":knowledge-persistence").projectDir = file("analysis/knowledge-persistence")
project(":languages").projectDir = file("sources/languages")
project(":syntax-treesitter").projectDir = file("sources/syntax-treesitter")
project(":lsp").projectDir = file("sources/lsp")
project(":intent-pipeline").projectDir = file("analysis/intent-pipeline")
project(":runtime").projectDir = file("orchestration/runtime")
project(":ai").projectDir = file("orchestration/ai")
project(":ipc").projectDir = file("orchestration/ipc")
project(":cli").projectDir = file("apps/cli")
project(":server").projectDir = file("apps/server")
project(":mcp").projectDir = file("apps/mcp")
project(":test-fixtures").projectDir = file("tests/fixtures")
project(":integration-tests").projectDir = file("tests/integration")
