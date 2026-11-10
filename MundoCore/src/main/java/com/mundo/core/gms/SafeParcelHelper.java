package com.mundo.core.gms;

import android.os.Parcel;

/**
 * Minimal SafeParcel encoder — enough to write GoogleSignInAccount on the wire.
 * SafeParcel header format (from AOSP + microG source):
 *   int32  field_id << 16 | size   (size=0xFFFF means "size follows as next int32")
 * String: header + length + UTF-16 data (Android Parcel.writeString).
 * Long:   header 0xFFFF + 8 bytes.
 */
class SafeParcelHelper {

    static final int SAFE_PARCEL_INDICATOR = 0x00005453; // "ST\0\0"

    /** Writes the outer object header; returns position for finishObjectHeader. */
    static int writeObjectHeader(Parcel dest) {
        dest.writeInt(SAFE_PARCEL_INDICATOR);
        int pos = dest.dataPosition();
        dest.writeInt(0); // placeholder for total size
        return pos;
    }

    static void finishObjectHeader(Parcel dest, int start) {
        int end  = dest.dataPosition();
        dest.setDataPosition(start);
        dest.writeInt(end - start - 4); // size excludes the size field itself
        dest.setDataPosition(end);
    }

    static void writeString(Parcel dest, int fieldId, String value) {
        if (value == null) return;
        // header with 0xFFFF size (variable-length follows)
        dest.writeInt((fieldId << 16) | 0xFFFF);
        int start = dest.dataPosition();
        dest.writeInt(0); // placeholder
        dest.writeString(value);
        int end = dest.dataPosition();
        dest.setDataPosition(start);
        dest.writeInt(end - start - 4);
        dest.setDataPosition(end);
    }

    static void writeLong(Parcel dest, int fieldId, long value) {
        dest.writeInt((fieldId << 16) | 0xFFFF);
        dest.writeInt(8);
        dest.writeLong(value);
    }

    static void writeInt(Parcel dest, int fieldId, int value) {
        dest.writeInt((fieldId << 16) | 4);
        dest.writeInt(value);
    }

    static void writeBoolean(Parcel dest, int fieldId, boolean value) {
        dest.writeInt((fieldId << 16) | 4);
        dest.writeInt(value ? 1 : 0);
    }
}
