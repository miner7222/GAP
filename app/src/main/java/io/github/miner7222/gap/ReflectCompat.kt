package io.github.miner7222.gap

import java.lang.reflect.Method

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
