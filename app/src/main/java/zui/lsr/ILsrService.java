package zui.lsr;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

public interface ILsrService extends IInterface {

    String DESCRIPTOR = "zui.lsr.ILsrService";

    boolean isSrAvaliableForImage(String imgFormat, int width, int height) throws RemoteException;

    boolean doSrForImageAsync(String imgUri, String imgFormat, int width, int height) throws RemoteException;

    void registerImageSrCallback(ILsrImageSrCallback callback) throws RemoteException;

    void unregisterImageSrCallback(ILsrImageSrCallback callback) throws RemoteException;

    int switchOnOffGameSR(Bundle cmd) throws RemoteException;

    int enableGfrcDebug(boolean enable, Bundle cmd) throws RemoteException;

    boolean initModels() throws RemoteException;

    boolean releaseModels() throws RemoteException;

    class Default implements ILsrService {
        @Override
        public boolean isSrAvaliableForImage(String imgFormat, int width, int height) throws RemoteException {
            return false;
        }

        @Override
        public boolean doSrForImageAsync(String imgUri, String imgFormat, int width, int height)
            throws RemoteException {
            return false;
        }

        @Override
        public void registerImageSrCallback(ILsrImageSrCallback callback) throws RemoteException {
        }

        @Override
        public void unregisterImageSrCallback(ILsrImageSrCallback callback) throws RemoteException {
        }

        @Override
        public int switchOnOffGameSR(Bundle cmd) throws RemoteException {
            return 0;
        }

        @Override
        public int enableGfrcDebug(boolean enable, Bundle cmd) throws RemoteException {
            return 0;
        }

        @Override
        public boolean initModels() throws RemoteException {
            return false;
        }

        @Override
        public boolean releaseModels() throws RemoteException {
            return false;
        }

        @Override
        public IBinder asBinder() {
            return null;
        }
    }

    abstract class Stub extends Binder implements ILsrService {
        static final int TRANSACTION_isSrAvaliableForImage = 1;
        static final int TRANSACTION_doSrForImageAsync = 2;
        static final int TRANSACTION_registerImageSrCallback = 3;
        static final int TRANSACTION_unregisterImageSrCallback = 4;
        static final int TRANSACTION_switchOnOffGameSR = 5;
        static final int TRANSACTION_enableGfrcDebug = 6;
        static final int TRANSACTION_initModels = 7;
        static final int TRANSACTION_releaseModels = 8;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static ILsrService asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof ILsrService) {
                return (ILsrService) iin;
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
            switch (code) {
                case TRANSACTION_isSrAvaliableForImage: {
                    String imgFormat = data.readString();
                    int width = data.readInt();
                    int height = data.readInt();
                    boolean result = isSrAvaliableForImage(imgFormat, width, height);
                    reply.writeNoException();
                    reply.writeInt(result ? 1 : 0);
                    return true;
                }
                case TRANSACTION_doSrForImageAsync: {
                    String imgUri = data.readString();
                    String imgFormat = data.readString();
                    int width = data.readInt();
                    int height = data.readInt();
                    boolean result = doSrForImageAsync(imgUri, imgFormat, width, height);
                    reply.writeNoException();
                    reply.writeInt(result ? 1 : 0);
                    return true;
                }
                case TRANSACTION_registerImageSrCallback: {
                    ILsrImageSrCallback callback = ILsrImageSrCallback.Stub.asInterface(data.readStrongBinder());
                    registerImageSrCallback(callback);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_unregisterImageSrCallback: {
                    ILsrImageSrCallback callback = ILsrImageSrCallback.Stub.asInterface(data.readStrongBinder());
                    unregisterImageSrCallback(callback);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_switchOnOffGameSR: {
                    Bundle cmd = readTypedObject(data, Bundle.CREATOR);
                    int result = switchOnOffGameSR(cmd);
                    reply.writeNoException();
                    reply.writeInt(result);
                    return true;
                }
                case TRANSACTION_enableGfrcDebug: {
                    boolean enable = data.readInt() != 0;
                    Bundle cmd = readTypedObject(data, Bundle.CREATOR);
                    int result = enableGfrcDebug(enable, cmd);
                    reply.writeNoException();
                    reply.writeInt(result);
                    return true;
                }
                case TRANSACTION_initModels: {
                    boolean result = initModels();
                    reply.writeNoException();
                    reply.writeInt(result ? 1 : 0);
                    return true;
                }
                case TRANSACTION_releaseModels: {
                    boolean result = releaseModels();
                    reply.writeNoException();
                    reply.writeInt(result ? 1 : 0);
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        static <T> T readTypedObject(Parcel parcel, Parcelable.Creator<T> creator) {
            if (parcel.readInt() != 0) {
                return creator.createFromParcel(parcel);
            }
            return null;
        }

        static void writeTypedObject(Parcel parcel, Parcelable value, int flags) {
            if (value != null) {
                parcel.writeInt(1);
                value.writeToParcel(parcel, flags);
            } else {
                parcel.writeInt(0);
            }
        }

        private static final class Proxy implements ILsrService {

            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            @Override
            public boolean isSrAvaliableForImage(String imgFormat, int width, int height) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(imgFormat);
                    data.writeInt(width);
                    data.writeInt(height);
                    mRemote.transact(TRANSACTION_isSrAvaliableForImage, data, reply, 0);
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public boolean doSrForImageAsync(String imgUri, String imgFormat, int width, int height)
                throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(imgUri);
                    data.writeString(imgFormat);
                    data.writeInt(width);
                    data.writeInt(height);
                    mRemote.transact(TRANSACTION_doSrForImageAsync, data, reply, 0);
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void registerImageSrCallback(ILsrImageSrCallback callback) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeStrongBinder(callback != null ? callback.asBinder() : null);
                    mRemote.transact(TRANSACTION_registerImageSrCallback, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void unregisterImageSrCallback(ILsrImageSrCallback callback) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeStrongBinder(callback != null ? callback.asBinder() : null);
                    mRemote.transact(TRANSACTION_unregisterImageSrCallback, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public int switchOnOffGameSR(Bundle cmd) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    writeTypedObject(data, cmd, 0);
                    mRemote.transact(TRANSACTION_switchOnOffGameSR, data, reply, 0);
                    reply.readException();
                    return reply.readInt();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public int enableGfrcDebug(boolean enable, Bundle cmd) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeInt(enable ? 1 : 0);
                    writeTypedObject(data, cmd, 0);
                    mRemote.transact(TRANSACTION_enableGfrcDebug, data, reply, 0);
                    reply.readException();
                    return reply.readInt();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public boolean initModels() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_initModels, data, reply, 0);
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public boolean releaseModels() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_releaseModels, data, reply, 0);
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
