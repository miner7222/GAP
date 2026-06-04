package io.github.miner7222.gap

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

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

internal object ReflectCompat {
    fun findClass(className: String, classLoader: ClassLoader?): Class<*> {
        return Class.forName(className, false, classLoader)
    }

    fun getObjectField(instance: Any?, fieldName: String): Any? {
        requireNotNull(instance) { "instance is null" }
        val field = findField(instance.javaClass, fieldName)
        return field.get(instance)
    }

    fun setObjectField(instance: Any?, fieldName: String, value: Any?) {
        requireNotNull(instance) { "instance is null" }
        val field = findField(instance.javaClass, fieldName)
        field.set(instance, value)
    }

    fun getStaticObjectField(clazz: Class<*>, fieldName: String): Any? {
        val field = findField(clazz, fieldName)
        return field.get(null)
    }

    fun callMethod(instance: Any?, methodName: String, vararg args: Any?): Any? {
        requireNotNull(instance) { "instance is null" }
        val method = findMethod(instance.javaClass, methodName, args)
        return method.invoke(instance, *args)
    }

    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? {
        val method = findMethod(clazz, methodName, args)
        return method.invoke(null, *args)
    }

    private fun findField(startClass: Class<*>, fieldName: String): java.lang.reflect.Field {
        var current: Class<*>? = startClass
        while (current != null) {
            runCatching {
                return current.getDeclaredField(fieldName).apply { isAccessible = true }
            }
            current = current.superclass
        }
        throw NoSuchFieldException("${startClass.name}#$fieldName")
    }

    private fun findMethod(startClass: Class<*>, methodName: String, args: Array<out Any?>): Method {
        var current: Class<*>? = startClass
        while (current != null) {
            current.declaredMethods.firstOrNull { method ->
                method.name == methodName && method.parameterTypes.matchesArgs(args)
            }?.let { method ->
                method.isAccessible = true
                return method
            }
            current = current.superclass
        }
        throw NoSuchMethodException("${startClass.name}#$methodName/${args.size}")
    }

    private fun Array<Class<*>>.matchesArgs(args: Array<out Any?>): Boolean {
        if (size != args.size) return false
        return indices.all { index ->
            val arg = args[index]
            arg == null || wrapPrimitive(this[index]).isInstance(arg)
        }
    }

    private fun wrapPrimitive(type: Class<*>): Class<*> {
        if (!type.isPrimitive) return type
        return when (type) {
            java.lang.Boolean.TYPE -> Boolean::class.javaObjectType
            java.lang.Byte.TYPE -> Byte::class.javaObjectType
            java.lang.Character.TYPE -> Char::class.javaObjectType
            java.lang.Short.TYPE -> Short::class.javaObjectType
            java.lang.Integer.TYPE -> Int::class.javaObjectType
            java.lang.Long.TYPE -> Long::class.javaObjectType
            java.lang.Float.TYPE -> Float::class.javaObjectType
            java.lang.Double.TYPE -> Double::class.javaObjectType
            java.lang.Void.TYPE -> Void::class.java
            else -> type
        }
    }
}
