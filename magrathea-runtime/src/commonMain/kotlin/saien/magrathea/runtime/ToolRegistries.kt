package saien.magrathea.runtime

import saien.magrathea.core.ToolExecutor
import saien.magrathea.core.ToolRegistry

/**
 * Composes static or dynamic Tool registries while rejecting ambiguous names.
 *
 * The provider is evaluated for every lookup so hosts can attach and detach optional Tool sources
 * such as MCP servers without rebuilding the Agent runner.
 */
class CompositeToolRegistry(
    private val registries: () -> Collection<ToolRegistry>,
) : ToolRegistry {
    override fun definitions() = registries().flatMap(ToolRegistry::definitions).also { definitions ->
        require(definitions.map { it.name }.distinct().size == definitions.size) {
            "Composite Tool registry contains duplicate Tool names"
        }
    }

    override fun find(name: String): ToolExecutor? {
        var match: ToolExecutor? = null
        registries().forEach { registry ->
            registry.find(name)?.let { candidate ->
                check(match == null) {
                    "Composite Tool registry contains duplicate Tool name '$name'"
                }
                match = candidate
            }
        }
        return match
    }
}
