package zui.lsr;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

public interface ILsrImageSrCallback extends IInterface {

    String DESCRIPTOR = "zui.lsr.ILsrImageSrCallback";

    void onImageSrDone(String inImgUri, String outImgUri, int width, int height, int status)
        throws RemoteException;

    class Default implements ILsrImageSrCallback {
        @Override
        public void onImageSrDone(String inImgUri, String outImgUri, int width, int height, int status)
            throws RemoteException {
        }

        @Override
        public IBinder asBinder() {
            return null;
        }
    }

    abstract class Stub extends Binder implements ILsrImageSrCallback {
        static final int TRANSACTION_onImageSrDone = 1;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static ILsrImageSrCallback asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof ILsrImageSrCallback) {
                return (ILsrImageSrCallback) iin;
            }
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code >= 1 && code <= 0x00FFFFFF) {
                data.enforceInterface(DESCRIPTOR);
            }
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            if (code == TRANSACTION_onImageSrDone) {
                String inImgUri = data.readString();
                String outImgUri = data.readString();
                int width = data.readInt();
                int height = data.readInt();
                int status = data.readInt();
                onImageSrDone(inImgUri, outImgUri, width, height, status);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static final class Proxy implements ILsrImageSrCallback {

            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            @Override
            public void onImageSrDone(String inImgUri, String outImgUri, int width, int height, int status)
                throws RemoteException {
                Parcel data = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(inImgUri);
                    data.writeString(outImgUri);
                    data.writeInt(width);
                    data.writeInt(height);
                    data.writeInt(status);
                    mRemote.transact(TRANSACTION_onImageSrDone, data, null, IBinder.FLAG_ONEWAY);
                } finally {
                    data.recycle();
                }
            }
        }
    }
}
