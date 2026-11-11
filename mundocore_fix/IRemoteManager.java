package android.Meta;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/** Stub interface for MetaCore activation system */
public interface IRemoteManager extends IInterface {
    void activateSdk(String userkey) throws RemoteException;
    boolean getActivatedSdk() throws RemoteException;
    String getServerMessage() throws RemoteException;
    boolean getNetwork() throws RemoteException;

    abstract class Stub extends Binder implements IRemoteManager {
        public Stub() { attachInterface(this, "android.Meta.IRemoteManager"); }

        @Override public IBinder asBinder() { return this; }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            return super.onTransact(code, data, reply, flags);
        }

        @Override public void activateSdk(String userkey) throws RemoteException {}
        @Override public boolean getActivatedSdk() throws RemoteException { return false; }
        @Override public String getServerMessage() throws RemoteException { return null; }
        @Override public boolean getNetwork() throws RemoteException { return false; }
    }
}
