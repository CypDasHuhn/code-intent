rootProject.name = "code-intent"

include(
    ":api",
    ":rulepacks",
    ":core",
    ":host",
    ":treesitter",
    ":lsp",
    ":frontend",
    ":cli",
    ":server",
    ":rulepacks-tests",
    ":test-fixtures",
    ":integration-tests",
)

project(":api").projectDir = file("core/api")
project(":rulepacks").projectDir = file("core/rulepacks")
project(":core").projectDir = file("core/core")
project(":host").projectDir = file("core/host")
project(":treesitter").projectDir = file("languages/treesitter")
project(":lsp").projectDir = file("languages/lsp")
project(":frontend").projectDir = file("languages/frontend")
project(":cli").projectDir = file("hosts/cli")
project(":server").projectDir = file("hosts/server")
project(":rulepacks-tests").projectDir = file("tests/rulepacks-tests")
project(":test-fixtures").projectDir = file("tests/test-fixtures")
project(":integration-tests").projectDir = file("tests/integration-tests")
