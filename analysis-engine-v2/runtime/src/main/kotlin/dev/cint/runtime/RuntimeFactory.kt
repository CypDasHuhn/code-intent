package dev.cint.runtime

import dev.cint.contracts.util.ContractResult

object RuntimeFactory {
    fun create(
        config: RuntimeConfig = RuntimeConfig(),
        components: RuntimeComponents = RuntimeComponents(),
    ): ContractResult<DefaultRuntime> = DefaultRuntime.create(config, components)
}
