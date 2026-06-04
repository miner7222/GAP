package io.github.miner7222.gap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HookTargetIntrospectionTest {

    @Test
    fun findsMethodsByParameterCount() {
        assertTrue(
            HookTargetIntrospection.hasExecutableWithParamCount(
                className = Target::class.java.name,
                executableName = "method",
                paramCount = 1,
                classLoader = Target::class.java.classLoader,
            ),
        )
    }

    @Test
    fun findsConstructorsByParameterCount() {
        assertTrue(
            HookTargetIntrospection.hasExecutableWithParamCount(
                className = Target::class.java.name,
                executableName = "<init>",
                paramCount = 1,
                classLoader = Target::class.java.classLoader,
            ),
        )
    }

    @Test
    fun rejectsMissingExecutableShape() {
        assertFalse(
            HookTargetIntrospection.hasExecutableWithParamCount(
                className = Target::class.java.name,
                executableName = "method",
                paramCount = 2,
                classLoader = Target::class.java.classLoader,
            ),
        )
    }

    private class Target(private val value: String) {
        @Suppress("unused")
        fun method(argument: String): String = "$value:$argument"
    }
}
