package com.mundo.core.gms;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Minimal device check-in to obtain androidId + securityToken from Google.
 * Uses a hand-rolled proto3 encoding — no Wire/protobuf dependency needed.
 *
 * Proto layout mirrors android.server.checkin.proto:
 *   CheckinRequest  { userSerialNumber(4), checkin(4→nested), id(7), digest(9),
 *                     locale(11), loggingId(12), marketCheckin(14), macAddress(29), ... }
 *   CheckinResponse { androidId(7), securityToken(8), ... }
 *
 * We only write the minimum fields Google requires for a fresh device register,
 * and read back androidId + securityToken.
 */
public class GmsCheckin {

    private static final String TAG   = "GmsCheckin";
    private static final String PREFS = "gms_checkin";
    private static final long   MIN_INTERVAL_MS = 3 * 3600_000L; // 3 hours

    private static final String KEY_ANDROID_ID  = "android_id";
    private static final String KEY_SEC_TOKEN    = "sec_token";
    private static final String KEY_LAST_TIME    = "last_time";

    // ---- public API --------------------------------------------------------

    public static long getAndroidId(Context ctx) {
        return getPrefs(ctx).getLong(KEY_ANDROID_ID, 0L);
    }

    public static long getSecurityToken(Context ctx) {
        return getPrefs(ctx).getLong(KEY_SEC_TOKEN, 0L);
    }

    /** Performs check-in if >3 h since last one, or force=true. Stores result. */
    public static boolean checkin(Context ctx, boolean force) {
        SharedPreferences prefs = getPrefs(ctx);
        long now = System.currentTimeMillis();
        if (!force && now - prefs.getLong(KEY_LAST_TIME, 0) < MIN_INTERVAL_MS
                && prefs.getLong(KEY_ANDROID_ID, 0) != 0) {
            return true; // cached
        }
        try {
            byte[] req   = buildCheckinRequest(prefs.getLong(KEY_ANDROID_ID, 0));
            byte[] resp  = GmsAuthHttpClient.postCheckin(req);
            long   aid   = parseAndroidId(resp);
            long   sec   = parseSecurityToken(resp);
            if (aid != 0) {
                prefs.edit()
                        .putLong(KEY_ANDROID_ID, aid)
                        .putLong(KEY_SEC_TOKEN, sec)
                        .putLong(KEY_LAST_TIME, now)
                        .apply();
                Log.d(TAG, "checkin ok, androidId=" + Long.toHexString(aid));
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "checkin failed: " + e.getMessage());
        }
        return false;
    }

    // ---- proto encode -------------------------------------------------------

    private static byte[] buildCheckinRequest(long existingAndroidId) throws IOException {
        // Minimal proto3 encoding for AndroidCheckinRequest.
        // Field numbers from android/server/checkin.proto (open source).
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);

        // field 4: checkin (nested AndroidCheckinProto)
        byte[] checkin = buildCheckinNested();
        writeTag(out, 4, 2); // LEN
        writeVarint(out, checkin.length);
        out.write(checkin);

        // field 7: id (androidId if re-registering, else 0 = new)
        if (existingAndroidId != 0) {
            writeTag(out, 7, 0); // VARINT
            writeVarint(out, existingAndroidId);
        }

        // field 11: locale
        writeTag(out, 11, 2);
        writeString(out, "en_US");

        // field 12: loggingId (random)
        writeTag(out, 12, 0);
        writeVarint(out, System.nanoTime());

        // field 14: marketCheckin
        writeTag(out, 14, 2);
        writeString(out, "");

        return out.toByteArray();
    }

    private static byte[] buildCheckinNested() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);

        // field 1: build (nested AndroidBuildProto)
        byte[] build = buildBuildNested();
        writeTag(out, 1, 2);
        writeVarint(out, build.length);
        out.write(build);

        // field 4: type = DEVICE(2)
        writeTag(out, 4, 0);
        writeVarint(out, 2);

        return out.toByteArray();
    }

    private static byte[] buildBuildNested() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        // field 1: id = "generic/google_sdk/generic:14/UQ1A.230805.004/10316470:user/release-keys"
        writeTag(out, 1, 2);
        writeString(out, Build.FINGERPRINT);
        // field 2: product
        writeTag(out, 2, 2);
        writeString(out, Build.PRODUCT);
        // field 3: carrier
        writeTag(out, 3, 2);
        writeString(out, "");
        // field 4: radio
        writeTag(out, 4, 2);
        writeString(out, Build.getRadioVersion() != null ? Build.getRadioVersion() : "");
        // field 5: bootloader
        writeTag(out, 5, 2);
        writeString(out, Build.BOOTLOADER);
        // field 7: clientId
        writeTag(out, 7, 2);
        writeString(out, "android-google");
        // field 8: timestamp
        writeTag(out, 8, 0);
        writeVarint(out, System.currentTimeMillis() / 1000);
        // field 9: googleServices = 252432 (25.24.32 ≈ microG version we emulate)
        writeTag(out, 9, 0);
        writeVarint(out, 252432);
        // field 11: device
        writeTag(out, 11, 2);
        writeString(out, Build.DEVICE);
        // field 12: sdkVersion
        writeTag(out, 12, 0);
        writeVarint(out, Build.VERSION.SDK_INT);
        // field 13: model
        writeTag(out, 13, 2);
        writeString(out, Build.MODEL);
        // field 14: manufacturer
        writeTag(out, 14, 2);
        writeString(out, Build.MANUFACTURER);
        return out.toByteArray();
    }

    // ---- proto decode -------------------------------------------------------

    private static long parseAndroidId(byte[] data) {
        return parseLong(data, 7); // field 7
    }

    private static long parseSecurityToken(byte[] data) {
        return parseLong(data, 8); // field 8
    }

    private static long parseLong(byte[] data, int targetField) {
        try {
            int i = 0;
            while (i < data.length) {
                long tag   = readVarintAt(data, i);
                int  tagLen = varintLen(data, i);
                i += tagLen;
                int fieldNum = (int) (tag >>> 3);
                int wireType = (int) (tag & 7);
                if (wireType == 0) { // VARINT
                    long val    = readVarintAt(data, i);
                    int  valLen = varintLen(data, i);
                    if (fieldNum == targetField) return val;
                    i += valLen;
                } else if (wireType == 2) { // LEN
                    long len    = readVarintAt(data, i);
                    int  lenLen = varintLen(data, i);
                    i += lenLen + (int) len;
                } else if (wireType == 1) { // 64-bit
                    i += 8;
                } else if (wireType == 5) { // 32-bit
                    i += 4;
                } else {
                    break;
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    // ---- proto helpers ------------------------------------------------------

    private static void writeTag(ByteArrayOutputStream out, int field, int wireType) throws IOException {
        writeVarint(out, (long) (field << 3 | wireType));
    }

    private static void writeString(ByteArrayOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeVarint(out, b.length);
        out.write(b);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) throws IOException {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                out.write((int) value);
                return;
            }
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
    }

    private static long readVarintAt(byte[] data, int offset) {
        long result = 0;
        int shift = 0;
        for (int i = offset; i < data.length && i < offset + 10; i++) {
            long b = data[i] & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }

    private static int varintLen(byte[] data, int offset) {
        int len = 0;
        for (int i = offset; i < data.length && i < offset + 10; i++) {
            len++;
            if ((data[i] & 0x80) == 0) break;
        }
        return len;
    }

    private static SharedPreferences getPrefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
