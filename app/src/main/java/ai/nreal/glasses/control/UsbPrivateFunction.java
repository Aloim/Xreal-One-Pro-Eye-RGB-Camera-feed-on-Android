package ai.nreal.glasses.control;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;
import android.util.Log;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class UsbPrivateFunction {
    private static final String TAG = "UsbPrivateFunction";
    private final UsbDeviceConnection connection;
    private final UsbDevice device;
    private UsbEndpoint endpointIn;
    private UsbEndpoint endpointOut;
    private UsbInterface hidInterface;
    private int interfaceNumber = -1;
    private UsbManager usbManager;

    public UsbPrivateFunction(UsbDevice usbDevice, UsbDeviceConnection usbDeviceConnection, UsbManager usbManager) {
        this.device = usbDevice;
        this.connection = usbDeviceConnection;
        this.usbManager = usbManager;
    }

    public void releaseInterface() {
        UsbInterface usbInterface;
        UsbDeviceConnection usbDeviceConnection = this.connection;
        if (usbDeviceConnection == null || (usbInterface = this.hidInterface) == null) {
            return;
        }
        usbDeviceConnection.releaseInterface(usbInterface);
    }

    private boolean isDeviceDisconnected(UsbDevice usbDevice) {
        return !this.usbManager.getDeviceList().containsValue(usbDevice);
    }

    public int usbRawWrite(int i, ByteBuffer byteBuffer, int i2) {
        if (this.interfaceNumber != i) {
            Log.d(TAG, "usbRawWrite: get interface" + this.interfaceNumber + "," + i);
            int i3 = 0;
            while (true) {
                if (i3 >= this.device.getInterfaceCount()) {
                    break;
                }
                UsbInterface usbInterface = this.device.getInterface(i3);
                if (usbInterface.getId() == i && usbInterface.getInterfaceClass() == 3) {
                    this.hidInterface = usbInterface;
                    break;
                }
                i3++;
            }
            UsbInterface usbInterface2 = this.hidInterface;
            if (usbInterface2 == null) {
                return -2;
            }
            if (!this.connection.claimInterface(usbInterface2, true)) {
                return -3;
            }
            for (int i4 = 0; i4 < this.hidInterface.getEndpointCount(); i4++) {
                UsbEndpoint endpoint = this.hidInterface.getEndpoint(i4);
                if (endpoint.getDirection() == 0) {
                    this.endpointOut = endpoint;
                } else if (endpoint.getDirection() == 128) {
                    this.endpointIn = endpoint;
                }
            }
            if (this.endpointIn != null && this.endpointOut != null) {
                this.interfaceNumber = i;
            } else {
                this.interfaceNumber = -1;
            }
        }
        if (!this.connection.claimInterface(this.hidInterface, true)) {
            return -3;
        }
        byte[] bArrArray = byteBuffer.array();
        int iArrayOffset = byteBuffer.arrayOffset();
        byte[] bArrCopyOfRange = Arrays.copyOfRange(bArrArray, byteBuffer.position() + iArrayOffset, iArrayOffset + byteBuffer.limit());
        UsbEndpoint usbEndpoint = this.endpointOut;
        if (usbEndpoint != null) {
            return this.connection.bulkTransfer(usbEndpoint, bArrCopyOfRange, i2, 0);
        }
        Log.d(TAG, "usbRawWrite: invalid endpoint out");
        return -1;
    }

    public int usbRawReadTimeout(int i, ByteBuffer byteBuffer, int i2, int i3) {
        if (this.interfaceNumber != i) {
            Log.d(TAG, "usbRawReadTimeout: get interface" + this.interfaceNumber + "," + i);
            int i4 = 0;
            while (true) {
                if (i4 >= this.device.getInterfaceCount()) {
                    break;
                }
                UsbInterface usbInterface = this.device.getInterface(i4);
                if (usbInterface.getId() == i && usbInterface.getInterfaceClass() == 3) {
                    this.hidInterface = usbInterface;
                    break;
                }
                i4++;
            }
            UsbInterface usbInterface2 = this.hidInterface;
            if (usbInterface2 == null) {
                return -2;
            }
            if (!this.connection.claimInterface(usbInterface2, true)) {
                return -3;
            }
            for (int i5 = 0; i5 < this.hidInterface.getEndpointCount(); i5++) {
                UsbEndpoint endpoint = this.hidInterface.getEndpoint(i5);
                if (endpoint.getDirection() == 0) {
                    this.endpointOut = endpoint;
                } else if (endpoint.getDirection() == 128) {
                    this.endpointIn = endpoint;
                }
            }
            if (this.endpointIn != null && this.endpointOut != null) {
                this.interfaceNumber = i;
            } else {
                this.interfaceNumber = -1;
            }
        }
        if (!this.connection.claimInterface(this.hidInterface, true)) {
            return -3;
        }
        byteBuffer.clear();
        byte[] bArrArray = byteBuffer.array();
        int iArrayOffset = byteBuffer.arrayOffset();
        byte[] bArrCopyOfRange = Arrays.copyOfRange(bArrArray, byteBuffer.position() + iArrayOffset, iArrayOffset + byteBuffer.limit());
        if (this.endpointIn != null) {
            long jElapsedRealtime = SystemClock.elapsedRealtime();
            int iBulkTransfer = this.connection.bulkTransfer(this.endpointIn, bArrCopyOfRange, i2, i3);
            long jElapsedRealtime2 = SystemClock.elapsedRealtime() - jElapsedRealtime;
            if (iBulkTransfer >= 0) {
                byteBuffer.put(bArrCopyOfRange);
            } else if (iBulkTransfer == -1) {
                if (isDeviceDisconnected(this.device)) {
                    Log.d(TAG, "usbRawReadTimeout: device disconnected");
                } else {
                    if (jElapsedRealtime2 > ((double) i3) * 0.8d) {
                        Log.d(TAG, "usbRawReadTimeout: read msg timeout");
                        return 0;
                    }
                    Log.d(TAG, "usbRawReadTimeout: actual timeout " + jElapsedRealtime2 + " set timeout " + i3);
                }
            } else {
                Log.d(TAG, "usbRawReadTimeout: return error number " + iBulkTransfer);
            }
            return iBulkTransfer;
        }
        Log.d(TAG, "usbRawReadTimeout: invalid endpoint in");
        return -1;
    }

    public boolean check_device_is_correct() {
        for (int i = 0; i < this.device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = this.device.getInterface(i);
            if (usbInterface.getInterfaceClass() == 3 && usbInterface.getEndpointCount() == 2 && usbInterface.getEndpoint(0).getMaxPacketSize() >= 64 && usbInterface.getEndpoint(1).getMaxPacketSize() >= 64) {
                Log.d("Main", "check_device_is_correct: get correct usb device");
                return true;
            }
        }
        return false;
    }
}
