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

project(":api").projectDir = file("modules/core/api")
project(":rulepacks").projectDir = file("modules/core/rulepacks")
project(":core").projectDir = file("modules/core/core")
project(":host").projectDir = file("modules/core/host")
project(":treesitter").projectDir = file("modules/languages/treesitter")
project(":lsp").projectDir = file("modules/languages/lsp")
project(":frontend").projectDir = file("modules/languages/frontend")
project(":cli").projectDir = file("modules/hosts/cli")
project(":server").projectDir = file("modules/hosts/server")
project(":rulepacks-tests").projectDir = file("modules/core/rulepacks-tests")
project(":test-fixtures").projectDir = file("modules/tests/test-fixtures")
project(":integration-tests").projectDir = file("modules/tests/integration-tests")
