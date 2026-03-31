package zui.lsr;

import android.content.ContentResolver;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import io.github.miner7222.gap.BuildConfig;
import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class LsrUtils {

    private static final int COPY_SIZE = 0x1400;
    private static final boolean DEBUG_PERFORMANCE = BuildConfig.DEBUG;
    public static final String LSR_PROVIDER_URI = "content://com.zui.lsr.provider.lsrprovider";
    private static final String TAG = "LsrService";

    private LsrUtils() {
    }

    public static void closeStreamQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    public static String saveFile2Provider(ContentResolver resolver, InputStream in, String fileName) {
        String savedUri = null;
        long startTime = System.currentTimeMillis();
        if (in != null && !TextUtils.isEmpty(fileName)) {
            OutputStream out = null;
            try {
                String uri = LSR_PROVIDER_URI + "/" + fileName;
                out = resolver.openOutputStream(Uri.parse(uri));
                if (out != null) {
                    byte[] buffer = new byte[COPY_SIZE];
                    int length;
                    while ((length = in.read(buffer)) != -1) {
                        out.write(buffer, 0, length);
                    }
                    out.flush();
                    savedUri = uri;
                }
            } catch (FileNotFoundException exception) {
                Log.e(TAG, "saveFile2Provider: open file failed! " + exception.getMessage());
            } catch (IOException exception) {
                Log.e(TAG, "saveFile2Provider: save file failed! " + exception.getMessage());
            } finally {
                closeStreamQuietly(out);
                closeStreamQuietly(in);
            }
        }
        if (DEBUG_PERFORMANCE) {
            Log.d(TAG, "PERFORMANCE:saveFile2Provider, time cost=" + (System.currentTimeMillis() - startTime));
        }
        return savedUri;
    }
}
