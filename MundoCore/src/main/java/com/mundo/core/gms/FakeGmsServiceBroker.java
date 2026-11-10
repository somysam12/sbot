package com.mundo.core.gms;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/**
 * Fake Binder that implements com.google.android.gms.common.internal.IGmsServiceBroker.
 *
 * The guest app calls bindService on com.google.android.gms/.chimera.GmsApiService,
 * gets connected to this Binder, and then calls:
 *   getService(IGmsCallbacks callback, GetServiceRequest request)  = transaction 45
 *
 * We handle serviceId=4068 (Sign-In API) by returning our FakeSignInService Binder.
 * All other serviceIds are declined with status NOT_AVAILABLE.
 *
 * GetServiceRequest SafeParcel layout (fields that matter to us):
 *   field 2 = serviceId (int)
 *   field 4 = packageName (string)
 *
 * IGmsCallbacks:
 *   onPostInitComplete(int statusCode, IBinder service, Bundle extras) = transaction 128
 */
public class FakeGmsServiceBroker extends Binder {

    private static final String TAG = "FakeGmsServiceBroker";

    // IGmsServiceBroker descriptor
    private static final String DESCRIPTOR =
            "com.google.android.gms.common.internal.IGmsServiceBroker";
    // IGmsCallbacks descriptor
    private static final String CB_DESCRIPTOR =
            "com.google.android.gms.common.internal.IGmsCallbacks";

    // Transaction codes
    private static final int TRANSACTION_GET_SERVICE      = 45;
    private static final int CB_ON_POST_INIT_COMPLETE     = 128;

    // Service IDs we handle
    private static final int SERVICE_ID_SIGN_IN           = 4068;

    // Status codes
    private static final int STATUS_OK            = 0;
    private static final int STATUS_NOT_AVAILABLE = 16;

    private final Context ctx;

    public FakeGmsServiceBroker(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        attachInterface(null, DESCRIPTOR);
    }

    @Override
    public String getInterfaceDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code != TRANSACTION_GET_SERVICE) {
            return super.onTransact(code, data, reply, flags);
        }

        data.enforceInterface(DESCRIPTOR);
        IBinder callbackBinder = data.readStrongBinder();
        // Parse GetServiceRequest SafeParcel to extract serviceId + packageName
        int    serviceId  = 0;
        String callerPkg  = null;
        try {
            GetServiceRequest req = parseGetServiceRequest(data);
            serviceId = req.serviceId;
            callerPkg = req.packageName;
        } catch (Exception e) {
            Log.w(TAG, "getService parse error: " + e.getMessage());
        }
        Log.d(TAG, "getService serviceId=" + serviceId + " pkg=" + callerPkg);

        if (serviceId == SERVICE_ID_SIGN_IN) {
            IBinder service = new FakeSignInService(ctx, callerPkg);
            sendPostInitComplete(callbackBinder, STATUS_OK, service);
        } else {
            Log.d(TAG, "getService: unhandled serviceId=" + serviceId);
            sendPostInitComplete(callbackBinder, STATUS_NOT_AVAILABLE, null);
        }

        if (reply != null) reply.writeNoException();
        return true;
    }

    // ---- callback -----------------------------------------------------------

    private void sendPostInitComplete(IBinder cb, int statusCode, IBinder service) {
        try {
            Parcel data  = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInterfaceToken(CB_DESCRIPTOR);
            data.writeInt(statusCode);
            data.writeStrongBinder(service); // may be null → 0 on wire
            data.writeInt(0); // null Bundle
            cb.transact(CB_ON_POST_INIT_COMPLETE, data, reply, 0);
            data.recycle();
            reply.recycle();
        } catch (Exception e) {
            Log.w(TAG, "sendPostInitComplete: " + e.getMessage());
        }
    }

    // ---- SafeParcel decoder for GetServiceRequest ---------------------------

    private static GetServiceRequest parseGetServiceRequest(Parcel source) {
        GetServiceRequest req = new GetServiceRequest();
        // Read SafeParcel header
        int indicator = source.readInt();
        if (indicator != SafeParcelHelper.SAFE_PARCEL_INDICATOR) {
            // Not a SafeParcel — try to read raw
            return req;
        }
        int totalSize = source.readInt();
        int end = source.dataPosition() + totalSize;

        while (source.dataPosition() < end) {
            int header   = source.readInt();
            int fieldId  = header >>> 16;
            int size     = header & 0xFFFF;

            int dataSize;
            if (size == 0xFFFF) {
                dataSize = source.readInt();
            } else {
                dataSize = size;
            }

            int fieldEnd = source.dataPosition() + dataSize;
            switch (fieldId) {
                case 2: // serviceId
                    req.serviceId = source.readInt();
                    break;
                case 4: // packageName
                    req.packageName = source.readString();
                    break;
                default:
                    source.setDataPosition(fieldEnd);
                    break;
            }
            // Align to fieldEnd in case we read less
            if (source.dataPosition() < fieldEnd) {
                source.setDataPosition(fieldEnd);
            }
        }
        return req;
    }

    private static class GetServiceRequest {
        int    serviceId   = 0;
        String packageName = null;
    }
}
