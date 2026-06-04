package io.github.miner7222.gap

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

internal class HookScope(
    private val module: XposedModule,
    val appClassLoader: ClassLoader,
) {
    fun beforeMethod(
        className: String,
        methodName: String,
        vararg parameterTypes: Class<*>,
        block: HookCall.() -> Unit,
    ) {
        hookMethod(
            className = className,
            methodName = methodName,
            parameterTypes = parameterTypes,
        ) { chain -> HookChainRunner.runBefore(chain, block) }
    }

    fun beforeMethod(
        className: String,
        methodName: String,
        parameterCount: Int,
        block: HookCall.() -> Unit,
    ) {
        hookMethod(
            className = className,
            methodName = methodName,
            parameterCount = parameterCount,
        ) { chain -> HookChainRunner.runBefore(chain, block) }
    }

    fun afterMethod(
        className: String,
        methodName: String,
        vararg parameterTypes: Class<*>,
        block: HookCall.() -> Unit,
    ) {
        hookMethod(
            className = className,
            methodName = methodName,
            parameterTypes = parameterTypes,
        ) { chain -> HookChainRunner.runAfter(chain, block) }
    }

    fun afterMethod(
        className: String,
        methodName: String,
        parameterCount: Int,
        block: HookCall.() -> Unit,
    ) {
        hookMethod(
            className = className,
            methodName = methodName,
            parameterCount = parameterCount,
        ) { chain -> HookChainRunner.runAfter(chain, block) }
    }

    fun replaceMethod(
        className: String,
        methodName: String,
        vararg parameterTypes: Class<*>,
        block: HookCall.() -> Any?,
    ) {
        hookMethod(
            className = className,
            methodName = methodName,
            parameterTypes = parameterTypes,
        ) { chain -> HookChainRunner.runReplace(chain, block) }
    }

    fun replaceMethod(
        className: String,
        methodName: String,
        parameterCount: Int,
        block: HookCall.() -> Any?,
    ) {
        hookMethod(
            className = className,
            methodName = methodName,
            parameterCount = parameterCount,
        ) { chain -> HookChainRunner.runReplace(chain, block) }
    }

    fun replaceMethodWithTrue(
        className: String,
        methodName: String,
        vararg parameterTypes: Class<*>,
    ) {
        replaceMethod(className, methodName, *parameterTypes) { true }
    }

    private fun hookMethod(
        className: String,
        methodName: String,
        parameterTypes: Array<out Class<*>>? = null,
        parameterCount: Int? = null,
        block: (XposedInterface.Chain) -> Any?,
    ) {
        val executable = resolveExecutable(className, methodName, parameterTypes, parameterCount)
        module.hook(executable)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept(block)
    }

    private fun resolveExecutable(
        className: String,
        methodName: String,
        parameterTypes: Array<out Class<*>>?,
        parameterCount: Int?,
    ): Executable {
        val targetClass = Class.forName(className, false, appClassLoader)
        val executable = if (methodName == CONSTRUCTOR_NAME) {
            targetClass.declaredConstructors.firstOrNull {
                matchesParameters(it.parameterTypes, parameterTypes, parameterCount)
            }
        } else {
            targetClass.declaredMethods.firstOrNull {
                it.name == methodName && matchesParameters(it.parameterTypes, parameterTypes, parameterCount)
            }
        } ?: throw NoSuchMethodException(
            "${targetClass.name}#$methodName/${describeParameters(parameterTypes, parameterCount)}",
        )
        executable.isAccessible = true
        return executable
    }

    private fun matchesParameters(
        actualTypes: Array<Class<*>>,
        parameterTypes: Array<out Class<*>>?,
        parameterCount: Int?,
    ): Boolean {
        parameterTypes?.let { expectedTypes ->
            return actualTypes.size == expectedTypes.size && actualTypes.zip(expectedTypes).all { (actual, expected) ->
                actual == expected
            }
        }
        return parameterCount?.let { actualTypes.size == it } ?: actualTypes.isEmpty()
    }

    private fun describeParameters(parameterTypes: Array<out Class<*>>?, parameterCount: Int?): String {
        return parameterTypes?.joinToString(prefix = "(", postfix = ")") { it.name }
            ?: parameterCount?.toString()
            ?: "any"
    }

    private companion object {
        private const val CONSTRUCTOR_NAME = "<init>"
    }
}

internal object HookChainRunner {
    fun runBefore(chain: XposedInterface.Chain, block: HookCall.() -> Unit): Any? {
        val call = HookCall(chain)
        call.block()
        return chain.proceed(call.args)
    }

    fun runAfter(chain: XposedInterface.Chain, block: HookCall.() -> Unit): Any? {
        val call = HookCall(chain, chain.proceed())
        call.block()
        return call.result
    }

    fun runReplace(chain: XposedInterface.Chain, block: HookCall.() -> Any?): Any? {
        return HookCall(chain).block()
    }
}

internal class HookCall(
    private val chain: XposedInterface.Chain,
    initialResult: Any? = null,
) {
    val args: Array<Any?> = chain.args.toTypedArray()
    val instanceOrNull: Any? = chain.thisObject
    var result: Any? = initialResult

    @Suppress("UNCHECKED_CAST")
    fun <T> instance(): T = instanceOrNull as T

    fun callOriginal(): Any? = chain.proceed(args)
}
