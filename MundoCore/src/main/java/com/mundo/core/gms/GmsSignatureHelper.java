package com.mundo.core.gms;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.util.Log;

import com.mundo.MundoCore;
import com.mundo.app.BActivityThread;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * Fetches the SHA1 of a guest app's signing certificate from the virtual
 * package manager (BPackageManagerService).
 *
 * Google's auth endpoint validates:
 *   app       = caller package name   (e.g. "com.pubg.imobile")
 *   client_sig = SHA1 hex of the APK's signing cert
 *
 * We must pass the REAL guest app's cert SHA1, not the host app's.
 * The virtual PM installed the original APK so its PackageInfo.signatures
 * holds the original certificate.
 */
public class GmsSignatureHelper {

    private static final String TAG = "GmsSignatureHelper";

    // Google GMS own signature — fallback only
    public static final String GMS_CERT_SHA1 =
            "38918a453d07199354f8b19af05ec6562ced5788";

    /**
     * Returns the SHA1 hex of the first signing cert of the given package
     * as known to the virtual package manager.
     * Falls back to GMS_CERT_SHA1 if the package is not found.
     */
    public static String getCallerCertSha1(String packageName) {
        if (packageName == null) return GMS_CERT_SHA1;
        try {
            int userId = BActivityThread.getUserId();
            PackageInfo pi = MundoCore.getBPackageManager()
                    .getPackageInfo(packageName,
                            PackageManager.GET_SIGNATURES, userId);
            if (pi != null && pi.signatures != null && pi.signatures.length > 0) {
                String sha1 = sha1(pi.signatures[0].toByteArray());
                Log.d(TAG, "cert sha1 for " + packageName + " = " + sha1);
                return sha1;
            }
        } catch (Exception e) {
            Log.w(TAG, "getCallerCertSha1 fallback for " + packageName
                    + ": " + e.getMessage());
        }
        return GMS_CERT_SHA1;
    }

    private static String sha1(byte[] certBytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(certBytes);
        StringBuilder sb = new StringBuilder(40);
        for (byte b : digest) {
            sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
