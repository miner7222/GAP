package io.github.miner7222.gap;

import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DeviceCompatibility {

    public static final String ZUI_VERSION_PROPERTY = "ro.com.zui.version";
    public static final String SOC_MODEL_PROPERTY = "ro.soc.model";
    public static final String SUPPORTED_SOC_SM8750P = "SM8750P";
    public static final String NATIVE_LSR_SOC_SM8850P = "SM8850P";

    private static final int[] MINIMUM_ZUI_VERSION = {17, 0};
    private static final Pattern VERSION_NUMBER_PATTERN = Pattern.compile("\\d+");

    private DeviceCompatibility() {
    }

    public static CompatibilityStatus evaluate(
        @Nullable String zuiVersion,
        @Nullable String socModel
    ) {
        if (!isSupportedZuiVersion(zuiVersion)) {
            return CompatibilityStatus.UNSUPPORTED_OS_VERSION;
        }
        if (!isSupportedSoc(socModel)) {
            return CompatibilityStatus.UNSUPPORTED_SOC;
        }
        return CompatibilityStatus.SUPPORTED;
    }

    public static boolean isSupportedZuiVersion(@Nullable String value) {
        int[] currentVersion = parseVersionNumbers(value);
        if (currentVersion.length == 0) {
            return false;
        }
        int length = Math.max(currentVersion.length, MINIMUM_ZUI_VERSION.length);
        for (int index = 0; index < length; index++) {
            int current = index < currentVersion.length ? currentVersion[index] : 0;
            int minimum = index < MINIMUM_ZUI_VERSION.length ? MINIMUM_ZUI_VERSION[index] : 0;
            if (current > minimum) {
                return true;
            }
            if (current < minimum) {
                return false;
            }
        }
        return true;
    }

    public static boolean isSupportedSoc(@Nullable String value) {
        String socModel = normalizeSocModel(value);
        return SUPPORTED_SOC_SM8750P.equals(socModel) || NATIVE_LSR_SOC_SM8850P.equals(socModel);
    }

    public static boolean hasNativeLsrService(@Nullable String value) {
        return NATIVE_LSR_SOC_SM8850P.equals(normalizeSocModel(value));
    }

    private static int[] parseVersionNumbers(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) {
            return new int[0];
        }
        Matcher matcher = VERSION_NUMBER_PATTERN.matcher(value);
        List<Integer> numbers = new ArrayList<>();
        while (matcher.find()) {
            try {
                numbers.add(Integer.parseInt(matcher.group()));
            } catch (NumberFormatException ignored) {
                numbers.add(Integer.MAX_VALUE);
            }
        }
        int[] result = new int[numbers.size()];
        for (int index = 0; index < numbers.size(); index++) {
            result[index] = numbers.get(index);
        }
        return result;
    }

    private static String normalizeSocModel(@Nullable String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    public enum CompatibilityStatus {
        SUPPORTED,
        UNSUPPORTED_OS_VERSION,
        UNSUPPORTED_SOC,
    }
}
