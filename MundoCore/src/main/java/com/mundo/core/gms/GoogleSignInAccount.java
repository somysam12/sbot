package com.mundo.core.gms;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;

/**
 * Hand-serialized Google sign-in account.
 * Mirrors com.google.android.gms.auth.api.signin.GoogleSignInAccount SafeParcelable layout:
 *
 *   field 2  = id / obfuscatedIdentifier (string)
 *   field 3  = idToken (string)
 *   field 4  = email (string)
 *   field 5  = displayName (string)
 *   field 6  = photoUrl (string)
 *   field 7  = serverAuthCode (string)
 *   field 8  = expirationTimeSecs (long)
 *   field 9  = obfuscatedId (string)
 *   field 11 = givenName (string)
 *   field 12 = familyName (string)
 *
 * When placed into an Intent extra named
 * "com.google.android.gms.auth.api.signin.api.additional_data"
 * the guest app's GoogleSignIn.getSignedInAccountFromIntent() can decode it
 * via reflection on the real com.google.android.gms classes — OR the guest
 * app will read it via the Parcelable CREATOR which calls SafeParcelReader.
 * We write the raw SafeParcel wire format so either path works.
 */
public class GoogleSignInAccount implements Parcelable {

    public final String id;
    public final String idToken;
    public final String email;
    public final String displayName;
    public final String givenName;
    public final String familyName;
    public final String photoUrl;
    public final String serverAuthCode;
    public final long   expirationTimeSecs;
    public final String obfuscatedId;

    public GoogleSignInAccount(String id, String idToken, String email,
                               String displayName, String givenName, String familyName,
                               String photoUrl, String serverAuthCode,
                               long expirationTimeSecs, String obfuscatedId) {
        this.id                 = id;
        this.idToken            = idToken;
        this.email              = email;
        this.displayName        = displayName;
        this.givenName          = givenName;
        this.familyName         = familyName;
        this.photoUrl           = photoUrl;
        this.serverAuthCode     = serverAuthCode;
        this.expirationTimeSecs = expirationTimeSecs;
        this.obfuscatedId       = obfuscatedId;
    }

    // ---- Parcelable (SafeParcel wire format) --------------------------------

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        // SafeParcel: each field is prefixed with a 4-byte header
        //   header = (fieldId << 16) | size (or 0xFFFF for objects)
        int start = SafeParcelHelper.writeObjectHeader(dest);

        SafeParcelHelper.writeString(dest, 2,  id);
        SafeParcelHelper.writeString(dest, 3,  idToken);
        SafeParcelHelper.writeString(dest, 4,  email);
        SafeParcelHelper.writeString(dest, 5,  displayName);
        SafeParcelHelper.writeString(dest, 6,  photoUrl);
        SafeParcelHelper.writeString(dest, 7,  serverAuthCode);
        SafeParcelHelper.writeLong  (dest, 8,  expirationTimeSecs);
        SafeParcelHelper.writeString(dest, 9,  obfuscatedId);
        SafeParcelHelper.writeString(dest, 11, givenName);
        SafeParcelHelper.writeString(dest, 12, familyName);

        SafeParcelHelper.finishObjectHeader(dest, start);
    }

    public static final Parcelable.Creator<GoogleSignInAccount> CREATOR =
            new Parcelable.Creator<GoogleSignInAccount>() {
        @Override
        public GoogleSignInAccount createFromParcel(Parcel source) {
            // We only need to write; reading is done by the Google SDK on the guest side.
            return null;
        }
        @Override
        public GoogleSignInAccount[] newArray(int size) { return new GoogleSignInAccount[size]; }
    };

    /** Convert to Bundle for returning in Intent extras. */
    public Bundle toBundle() {
        Bundle b = new Bundle();
        b.putString("email",       email);
        b.putString("id",          id);
        b.putString("displayName", displayName);
        b.putString("idToken",     idToken);
        b.putString("givenName",   givenName);
        b.putString("familyName",  familyName);
        b.putString("photoUrl",    photoUrl);
        return b;
    }
}
