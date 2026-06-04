package io.github.miner7222.gap

import org.junit.Assert.assertEquals
import org.junit.Test

class XposedScopeRequestPlannerTest {

    @Test
    fun requestsSystemAndGameHelperWhenScopeIsEmpty() {
        assertEquals(
            listOf("system", "com.zui.game.service"),
            XposedScopeRequestPlanner.missingScopes(emptyList()),
        )
    }

    @Test
    fun requestsOnlyScopesMissingFromCurrentServiceScope() {
        assertEquals(
            listOf("system"),
            XposedScopeRequestPlanner.missingScopes(listOf("com.zui.game.service")),
        )
    }

    @Test
    fun doesNotRequestWhenAllRequiredScopesAreAlreadyPresent() {
        assertEquals(
            emptyList<String>(),
            XposedScopeRequestPlanner.missingScopes(listOf("system", "com.zui.game.service")),
        )
    }
}
