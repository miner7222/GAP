package io.github.miner7222.gap;

import android.content.Context;
import android.os.IBinder;
import androidx.annotation.Nullable;
import com.zui.server.lsr.LsrService;

public final class LsrServiceRegistry {

    private static LsrService sLsrService;
    private static volatile IBinder sFallbackBinder;

    private LsrServiceRegistry() {
    }

    public static synchronized void ensureRegistered(Context context) {
        if (!AndroidInternals.useCompatibilityLsr()) {
            return;
        }
        if (AndroidInternals.getService(LsrService.LSR_SERVICE) != null) {
            return;
        }
        if (sLsrService == null) {
            Context appContext = context.getApplicationContext();
            sLsrService = new LsrService(appContext != null ? appContext : context);
        }
        sLsrService.onStart();
    }

    public static synchronized void publishBinderService(String name, IBinder binder) {
        if (!AndroidInternals.useCompatibilityLsr()) {
            return;
        }
        if (AndroidInternals.getService(name) != null) {
            return;
        }
        try {
            AndroidInternals.addService(name, binder);
            AndroidInternals.log("Registered Binder service " + name);
        } catch (Throwable throwable) {
            AndroidInternals.log("Failed to register Binder service " + name
                + "; keeping fallback binder for ServiceManager hook", throwable);
            sFallbackBinder = binder;
        }
    }

    /**
     * Returns the binder that was intended for ServiceManager but could not be
     * registered (e.g. due to SELinux denial). Only meaningful in the same
     * process that attempted registration (system_server).
     */
    @Nullable
    public static IBinder getFallbackBinder() {
        return sFallbackBinder;
    }
}
