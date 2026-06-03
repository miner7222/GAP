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
    fun findClass(name: String): ClassHookTarget {
        return ClassHookTarget(module, Class.forName(name, false, appClassLoader))
    }
}

internal class ClassHookTarget(
    private val module: XposedModule,
    private val targetClass: Class<*>,
) {
    fun hook(block: ClassHookBuilder.() -> Unit) {
        ClassHookBuilder(module, targetClass).apply(block)
    }
}

internal class ClassHookBuilder(
    private val module: XposedModule,
    private val targetClass: Class<*>,
) {
    fun injectMember(block: MemberHookBuilder.() -> Unit) {
        MemberHookBuilder(module, targetClass).apply(block)
    }
}

internal class MemberHookBuilder(
    private val module: XposedModule,
    private val targetClass: Class<*>,
) {
    private var methodSpec: MethodSpec? = null

    fun method(block: MethodSpec.() -> Unit) {
        methodSpec = MethodSpec().apply(block)
    }

    fun before(block: HookCall.() -> Unit) {
        val executable = resolveExecutable()
        module.hook(executable)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val param = HookCall(chain)
                param.block()
                chain.proceed(param.args)
            }
    }

    fun after(block: HookCall.() -> Unit) {
        val executable = resolveExecutable()
        module.hook(executable)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val param = HookCall(chain, chain.proceed())
                param.block()
                param.result
            }
    }

    fun replace(block: HookCall.() -> Any?) {
        val executable = resolveExecutable()
        module.hook(executable)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                HookCall(chain).block()
            }
    }

    fun replaceWithTrue() {
        replace { true }
    }

    private fun resolveExecutable(): Executable {
        val spec = requireNotNull(methodSpec) { "method block must be declared before hook callback" }
        val name = requireNotNull(spec.name) { "method name is required" }
        val executable = if (name == CONSTRUCTOR_NAME) {
            targetClass.declaredConstructors.firstOrNull { spec.matches(it.parameterTypes) }
        } else {
            targetClass.declaredMethods.firstOrNull { it.name == name && spec.matches(it.parameterTypes) }
        } ?: throw NoSuchMethodException("${targetClass.name}#$name/${spec.describeParams()}")
        executable.isAccessible = true
        return executable
    }

    private companion object {
        private const val CONSTRUCTOR_NAME = "<init>"
    }
}

internal class MethodSpec {
    var name: String? = null
    var paramCount: Int? = null
    private var paramTypes: Array<out Class<*>>? = null

    fun emptyParam() {
        paramCount = 0
        paramTypes = emptyArray()
    }

    fun param(vararg types: Class<*>) {
        paramTypes = types
        paramCount = types.size
    }

    fun matches(actualTypes: Array<Class<*>>): Boolean {
        paramTypes?.let { expectedTypes ->
            return actualTypes.size == expectedTypes.size &&
                actualTypes.zip(expectedTypes).all { (actual, expected) -> actual == expected }
        }
        return paramCount?.let { actualTypes.size == it } ?: true
    }

    fun describeParams(): String {
        return paramTypes?.joinToString(prefix = "(", postfix = ")") { it.name }
            ?: paramCount?.toString()
            ?: "any"
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
