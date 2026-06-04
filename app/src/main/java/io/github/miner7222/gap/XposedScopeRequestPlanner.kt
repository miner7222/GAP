package io.github.miner7222.gap

internal object XposedScopeRequestPlanner {
    val REQUIRED_SCOPES: List<String> = listOf("system", "com.zui.game.service")

    fun missingScopes(currentScopes: Collection<String>): List<String> {
        val currentScopeSet = currentScopes.toSet()
        return REQUIRED_SCOPES.filterNot { currentScopeSet.contains(it) }
    }
}
