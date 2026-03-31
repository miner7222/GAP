package com.zui.server.lsr;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;
import androidx.annotation.Nullable;
import io.github.miner7222.gap.AndroidInternals;
import io.github.miner7222.gap.BuildConfig;
import io.github.miner7222.gap.LsrServiceRegistry;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import zui.lsr.ILsrImageSrCallback;
import zui.lsr.ILsrService;
import zui.lsr.LsrUtils;

public class LsrService {

    public static final int CROP_W_MAX_NUM = 3;
    public static final boolean DEBUG = BuildConfig.DEBUG;
    public static final boolean DEBUG_PERFORMANCE = BuildConfig.DEBUG;
    public static final String LSR_SERVICE = "lenovosr";
    public static final int SR_MAX_H = 0x870;
    public static final int SR_MAX_W = 0xF00;
    public static final int SR_MIN_WH = 0x2;
    public static final int SR_STATUS_FAIL_FORMAT = -2;
    public static final int SR_STATUS_FAIL_INIT_MODELS = -10;
    public static final int SR_STATUS_FAIL_MISMATCH = -3;
    public static final int SR_STATUS_FAIL_REL_MODELS = -11;
    public static final int SR_STATUS_FAIL_SR = -4;
    public static final int SR_STATUS_FAIL_UNKNOWN = -1;
    public static final int SR_STATUS_OK = 1;
    public static final int SR_STATUS_OK_INIT_MODELS = 10;
    public static final int SR_STATUS_OK_REL_MODELS = 11;
    public static final String TAG = "LsrService";

    private static final int MESSAGE_DO_IMAGE_SR = 1;
    private static final int MESSAGE_INIT_MODELS = 2;
    private static final int MESSAGE_REL_MODELS = 3;

    public Map<ILsrImageSrCallback, Integer> mCallbackUidMap = new ConcurrentHashMap<>();
    public final Context mContext;
    public boolean mDoingSR;
    public LsrWorkerHandler mHandler;
    public HandlerThread mHandlerThread;
    public RemoteCallbackList<ILsrImageSrCallback> mImageCallbackList = new RemoteCallbackList<>();
    public int mSrStatus = SR_STATUS_FAIL_UNKNOWN;
    private boolean mStarted;

    public LsrService(Context context) {
        Context appContext = context.getApplicationContext();
        mContext = appContext != null ? appContext : context;
    }

    public synchronized void onStart() {
        if (mStarted) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "onStart");
        }
        mHandlerThread = new HandlerThread("LsrServiceWorker", 10);
        mHandlerThread.start();
        mHandler = new LsrWorkerHandler(mHandlerThread.getLooper());
        mImageCallbackList = new RemoteCallbackList<>();
        mCallbackUidMap = new ConcurrentHashMap<>();
        LsrServiceRegistry.publishBinderService(LSR_SERVICE, new BinderService(this));
        // gppservice reads this at startup, so prime it as soon as the binder
        // service is brought up in system_server.
        AndroidInternals.setSystemProperty("vendor.gpp.create_frc_extension", "1");
        mStarted = true;
    }

    /**
     * Lightweight start for the fallback path: initialises the worker thread
     * and callback infrastructure without touching ServiceManager. It still
     * primes the FRC-extension property so the native GPP daemon sees the
     * expected state once it starts.
     */
    public synchronized void onStartLocal() {
        if (mStarted) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "onStartLocal (fallback)");
        }
        mHandlerThread = new HandlerThread("LsrServiceWorker", 10);
        mHandlerThread.start();
        mHandler = new LsrWorkerHandler(mHandlerThread.getLooper());
        mImageCallbackList = new RemoteCallbackList<>();
        mCallbackUidMap = new ConcurrentHashMap<>();
        // Tell the GPU driver to create the FRC extension — required before
        // any vendor.gpp.frc.* / vendor.gpp.gfrc.* properties take effect.
        AndroidInternals.setSystemProperty("vendor.gpp.create_frc_extension", "1");
        mStarted = true;
    }

    public void onBootPhase(int phase) {
        if (DEBUG) {
            Log.d(TAG, "onBootPhase:phase=" + phase);
        }
    }

    boolean isFormatSrSupport(@Nullable String format) {
        if (format == null) {
            return false;
        }
        String normalized = removeFirstDot(format).toLowerCase(Locale.ROOT);
        return "jpg".equals(normalized)
            || "jpeg".equals(normalized)
            || "png".equals(normalized)
            || "webp".equals(normalized)
            || "bmp".equals(normalized);
    }

    boolean isSrSupport(int width, int height) {
        return width >= SR_MIN_WH
            && height >= SR_MIN_WH
            && width <= SR_MAX_W
            && height <= SR_MAX_H;
    }

    String removeFirstDot(@Nullable String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == '.') {
            return text.substring(1);
        }
        return text == null ? "" : text;
    }

    private boolean isPlatformQcom() {
        return "qcom".equals(AndroidInternals.getSystemProperty("ro.config.lgsi.platform", "none"));
    }

    private void notifyCallbacks(
        String inImgUri,
        String outImgUri,
        int width,
        int height,
        int status,
        int targetUid
    ) {
        int count = mImageCallbackList.beginBroadcast();
        try {
            for (int index = 0; index < count; index++) {
                ILsrImageSrCallback callback = mImageCallbackList.getBroadcastItem(index);
                Integer callbackUid = mCallbackUidMap.get(callback);
                if (targetUid != -1 && callbackUid != null && callbackUid != targetUid) {
                    continue;
                }
                try {
                    callback.onImageSrDone(inImgUri, outImgUri, width, height, status);
                } catch (RemoteException exception) {
                    Log.w(TAG, "Callback delivery failed", exception);
                }
            }
        } finally {
            mImageCallbackList.finishBroadcast();
        }
    }

    private String buildOutputName(LsrParams params) {
        String format = removeFirstDot(params.format);
        if (format.isEmpty()) {
            format = "png";
        }
        return "lsr_" + System.currentTimeMillis() + "." + format;
    }

    private final class LsrWorkerHandler extends Handler {
        LsrWorkerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MESSAGE_DO_IMAGE_SR:
                    handleImageRequest((LsrParams) message.obj);
                    return;
                case MESSAGE_INIT_MODELS:
                    handleInitModels(((Integer) message.obj).intValue());
                    return;
                case MESSAGE_REL_MODELS:
                    handleReleaseModels(((Integer) message.obj).intValue());
                    return;
                default:
                    Log.w(TAG, "Unknown message " + message.what);
            }
        }

        private void handleInitModels(int callingUid) {
            mSrStatus = SR_STATUS_OK_INIT_MODELS;
            notifyCallbacks("", "", -1, -1, mSrStatus, callingUid);
            mDoingSR = false;
        }

        private void handleReleaseModels(int callingUid) {
            mSrStatus = SR_STATUS_OK_REL_MODELS;
            notifyCallbacks("", "", -1, -1, mSrStatus, callingUid);
            mDoingSR = false;
        }

        private void handleImageRequest(LsrParams params) {
            String outputUri = "";
            mSrStatus = SR_STATUS_FAIL_UNKNOWN;
            try {
                if (!isFormatSrSupport(params.format)) {
                    mSrStatus = SR_STATUS_FAIL_FORMAT;
                } else if (!isSrSupport(params.width, params.height)) {
                    mSrStatus = SR_STATUS_FAIL_MISMATCH;
                } else {
                    ContentResolver resolver = mContext.getContentResolver();
                    InputStream inputStream = resolver.openInputStream(Uri.parse(params.sourceImgUri));
                    if (inputStream != null) {
                        outputUri = LsrUtils.saveFile2Provider(resolver, inputStream, buildOutputName(params));
                    }
                    mSrStatus = outputUri != null && !outputUri.isEmpty() ? SR_STATUS_OK : SR_STATUS_FAIL_SR;
                }
            } catch (Throwable throwable) {
                Log.w(TAG, "Image SR compatibility path failed", throwable);
                mSrStatus = SR_STATUS_FAIL_SR;
            } finally {
                notifyCallbacks(
                    params.sourceImgUri,
                    outputUri == null ? "" : outputUri,
                    params.width,
                    params.height,
                    mSrStatus,
                    params.callingUid
                );
                mDoingSR = false;
            }
        }
    }

    public static final class LsrParams {
        public int callingUid;
        public String format;
        public int height;
        public String sourceImgUri;
        public int width;
    }

    public enum SrType {
        MATCH,
        PADDINGMATCH,
        PADDINGCROPMATCH,
        MISMATCH
    }

    public static final class BinderService extends ILsrService.Stub {

        private final LsrService mService;

        public BinderService(LsrService service) {
            mService = service;
        }

        @Override
        public boolean isSrAvaliableForImage(String imgFormat, int width, int height) {
            boolean result = mService.isFormatSrSupport(imgFormat) && mService.isSrSupport(width, height);
            if (DEBUG) {
                Log.d(TAG, "isSRAvaliableForImage: imgFormat=" + imgFormat + " width=" + width
                    + " height=" + height + " ret=" + result);
            }
            return result;
        }

        @Override
        public boolean doSrForImageAsync(String imgUri, String imgFormat, int width, int height) {
            if (DEBUG) {
                Log.d(TAG, "doSrForImageAsync: imgUri=" + imgUri + " imgFormat=" + imgFormat
                    + " width=" + width + " height=" + height + " mDoingSR=" + mService.mDoingSR);
            }
            if (mService.mDoingSR) {
                if (DEBUG) {
                    Log.d(TAG, "doSrForImageAsync fail, other process is calling");
                }
                return false;
            }
            mService.mDoingSR = true;
            LsrParams params = new LsrParams();
            params.sourceImgUri = imgUri;
            params.format = mService.removeFirstDot(imgFormat);
            params.callingUid = Binder.getCallingUid();
            params.width = width;
            params.height = height;
            Message message = Message.obtain();
            message.what = MESSAGE_DO_IMAGE_SR;
            message.obj = params;
            mService.mHandler.sendMessage(message);
            return true;
        }

        @Override
        public void registerImageSrCallback(ILsrImageSrCallback callback) {
            if (DEBUG) {
                Log.d(TAG, "registerImageSrCallback:callback=" + callback);
            }
            if (callback == null) {
                return;
            }
            mService.mImageCallbackList.register(callback);
            mService.mCallbackUidMap.put(callback, Binder.getCallingUid());
        }

        @Override
        public void unregisterImageSrCallback(ILsrImageSrCallback callback) {
            if (DEBUG) {
                Log.d(TAG, "unregisterImageSrCallback:callback=" + callback);
            }
            if (callback == null) {
                return;
            }
            mService.mImageCallbackList.unregister(callback);
            mService.mCallbackUidMap.remove(callback);
        }

        @Override
        public int switchOnOffGameSR(Bundle cmd) {
            AndroidInternals.log("switchOnOffGameSR called, cmd=" + cmd
                + ", platform=" + AndroidInternals.getSystemProperty("ro.config.lgsi.platform", "none"));
            if (!mService.isPlatformQcom()) {
                AndroidInternals.log("switchOnOffGameSR: not qcom, returning 0");
                return 0;
            }
            boolean switchOn = cmd != null && (cmd.getBoolean("switchOnOff", false) || cmd.getBoolean("switchOn", false));
            int upscaleF = cmd != null ? cmd.getInt("upscaleF", 0) : 0;
            int interpF = cmd != null ? cmd.getInt("interpF", 0) : 0;
            AndroidInternals.log("switchOnOffGameSR: switchOn=" + switchOn
                + ", upscaleF=" + upscaleF + ", interpF=" + interpF);
            if (switchOn) {
                AndroidInternals.setSystemProperty("vendor.gpp.frc.enable", "0x22");
                AndroidInternals.setSystemProperty("vendor.gpp.gfrc.upscale.ratio", Integer.toString(upscaleF));
                AndroidInternals.setSystemProperty("vendor.gpp.gfrc.interp.rate", Integer.toString(interpF));
                AndroidInternals.setSystemProperty("vendor.gpp.frc.interp.factor", Integer.toString(interpF));
                AndroidInternals.setSystemProperty("vendor.gpp.frc.upscale.ratio", Integer.toString(upscaleF));
                AndroidInternals.log("switchOnOffGameSR: ON done, wrote all vendor.gpp properties");
            } else {
                AndroidInternals.setSystemProperty("vendor.gpp.frc.enable", "0x21");
                AndroidInternals.setSystemProperty("vendor.gpp.gfrc.upscale.ratio", "0");
                AndroidInternals.setSystemProperty("vendor.gpp.gfrc.interp.rate", "0");
                AndroidInternals.setSystemProperty("vendor.gpp.frc.interp.factor", "0");
                AndroidInternals.setSystemProperty("vendor.gpp.frc.upscale.ratio", "0");
                AndroidInternals.log("switchOnOffGameSR: OFF done, cleared all vendor.gpp properties");
            }
            return 1;
        }

        @Override
        public int enableGfrcDebug(boolean enable, Bundle cmd) {
            if (!mService.isPlatformQcom()) {
                return 0;
            }
            if (enable) {
                AndroidInternals.setSystemProperty("vendor.gpp.log.msg", "0x21");
                if (DEBUG) {
                    Log.i(TAG, "qcom enableGfrcDebug: On");
                }
            } else {
                AndroidInternals.setSystemProperty("vendor.gpp.log.msg", "");
                if (DEBUG) {
                    Log.i(TAG, "qcom enableGfrcDebug: Off");
                }
            }
            return 1;
        }

        @Override
        public boolean initModels() {
            if (DEBUG) {
                Log.d(TAG, "initModels mDoingSR=" + mService.mDoingSR);
            }
            if (mService.mDoingSR) {
                if (DEBUG) {
                    Log.d(TAG, "initModels fail, other process is calling");
                }
                return false;
            }
            mService.mDoingSR = true;
            Message message = Message.obtain();
            message.what = MESSAGE_INIT_MODELS;
            message.obj = Binder.getCallingUid();
            mService.mHandler.sendMessage(message);
            return true;
        }

        @Override
        public boolean releaseModels() {
            if (DEBUG) {
                Log.d(TAG, "releaseModels mDoingSR=" + mService.mDoingSR);
            }
            if (mService.mDoingSR) {
                if (DEBUG) {
                    Log.d(TAG, "releaseModels fail, other process is calling");
                }
                return false;
            }
            mService.mDoingSR = true;
            Message message = Message.obtain();
            message.what = MESSAGE_REL_MODELS;
            message.obj = Binder.getCallingUid();
            mService.mHandler.sendMessage(message);
            return true;
        }
    }
}
