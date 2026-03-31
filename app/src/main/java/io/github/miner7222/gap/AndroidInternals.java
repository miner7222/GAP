package io.github.miner7222.gap;

import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Locale;

public final class AndroidInternals {

    private static final String TAG = "LSRXposed";
    private static final boolean DEBUG = BuildConfig.DEBUG;
    private static final String PRODUCT_BOARD_PROPERTY = "ro.product.board";
    private static final String BALDUR_BOARD_MARKER = "baldur";

    private AndroidInternals() {
    }

    @Nullable
    public static IBinder getService(String name) {
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method method = serviceManager.getDeclaredMethod("getService", String.class);
            method.setAccessible(true);
            return (IBinder) method.invoke(null, name);
        } catch (Throwable throwable) {
            log("Unable to query service " + name, throwable);
            return null;
        }
    }

    public static void addService(String name, IBinder binder) throws ReflectiveOperationException {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method[] methods = serviceManager.getDeclaredMethods();
        for (Method method : methods) {
            if (!"addService".equals(method.getName())) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            method.setAccessible(true);
            if (parameterTypes.length == 2) {
                method.invoke(null, name, binder);
                return;
            }
            if (parameterTypes.length == 3
                && parameterTypes[0] == String.class
                && IBinder.class.isAssignableFrom(parameterTypes[1])
                && parameterTypes[2] == boolean.class) {
                method.invoke(null, name, binder, false);
                return;
            }
            if (parameterTypes.length == 4
                && parameterTypes[0] == String.class
                && IBinder.class.isAssignableFrom(parameterTypes[1])
                && parameterTypes[2] == boolean.class
                && parameterTypes[3] == int.class) {
                method.invoke(null, name, binder, false, 0);
                return;
            }
        }
        throw new NoSuchMethodException("android.os.ServiceManager.addService");
    }

    public static String getSystemProperty(String key, String defaultValue) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method method = systemProperties.getDeclaredMethod("get", String.class, String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, key, defaultValue);
        } catch (Throwable throwable) {
            log("Unable to read system property " + key, throwable);
            return defaultValue;
        }
    }

    public static void setSystemProperty(String key, String value) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method method = systemProperties.getDeclaredMethod("set", String.class, String.class);
            method.setAccessible(true);
            method.invoke(null, key, value);
            // Verify write succeeded
            String actual = getSystemProperty(key, "<unset>");
            if (!value.equals(actual)) {
                log("Property write FAILED: " + key + " expected=" + value + " actual=" + actual);
            }
        } catch (Throwable throwable) {
            log("Unable to write system property " + key, throwable);
        }
    }

    public static boolean isBaldurBoard() {
        return getSystemProperty(PRODUCT_BOARD_PROPERTY, "")
            .toLowerCase(Locale.ROOT)
            .contains(BALDUR_BOARD_MARKER);
    }

    public static boolean useCompatibilityLsr() {
        return !isBaldurBoard();
    }

    public static void log(String message) {
        if (!DEBUG) {
            return;
        }
        Log.i(TAG, message);
        de.robv.android.xposed.XposedBridge.log(TAG + ": " + message);
    }

    public static void log(String message, Throwable throwable) {
        if (!DEBUG) {
            return;
        }
        Log.e(TAG, message, throwable);
        de.robv.android.xposed.XposedBridge.log(TAG + ": " + message);
        de.robv.android.xposed.XposedBridge.log(throwable);
    }
}
