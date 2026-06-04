package io.github.miner7222.gap

internal fun HookScope.hasMethodWithParamCount(
    className: String,
    methodName: String,
    paramCount: Int,
): Boolean {
    return HookTargetIntrospection.hasExecutableWithParamCount(
        className = className,
        executableName = methodName,
        paramCount = paramCount,
        classLoader = appClassLoader,
    )
}

internal object HookTargetIntrospection {
    fun hasExecutableWithParamCount(
        className: String,
        executableName: String,
        paramCount: Int,
        classLoader: ClassLoader?,
    ): Boolean {
        return runCatching {
            val targetClass = ReflectCompat.findClass(className, classLoader)
            if (executableName == CONSTRUCTOR_NAME) {
                targetClass.declaredConstructors.any { it.parameterTypes.size == paramCount }
            } else {
                targetClass.declaredMethods.any {
                    it.name == executableName && it.parameterTypes.size == paramCount
                }
            }
        }.getOrDefault(false)
    }

    private const val CONSTRUCTOR_NAME = "<init>"
}
