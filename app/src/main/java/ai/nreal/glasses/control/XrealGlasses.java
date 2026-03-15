package ai.nreal.glasses.control;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class XrealGlasses implements IOta {
    private static final int GINA_KERNEL = 1078;
    private static final int GINA_ROM_PID = 37905;
    private static final int GINA_ROM_VID = 16722;
    private static final int GLASS_GINA = 42;
    private static final int GLASS_GINA_KERNEL = 41;
    private static final int GLASS_GINA_KERNEL_IMU = 37;
    private static final int GLASS_GINA_KERNEL_VSYNC = 38;
    private static final int GLASS_GINA_UBOOT = 40;
    private static final int GLASS_GINA_UBOOT_ROM = 39;
    private static final int XREAL_VID = 13080;
    private static final int VIDDA_VID = 4251;
    private static final int FLORA_ROM_VID = 21315;
    static String TAG = null;

    private Context context;
    IPermission iPermission;
    OtaCallback otaCb;
    IReportCallback reportCallback;
    int step;
    private UsbManager usbManager;

    GlassDevice[] glass_devices = {
        new GlassDevice(13080, 1059, 4, "Air Boot"),
        new GlassDevice(13080, 1060, 5, "Air App"),
        new GlassDevice(13080, 1073, 28, "P55E Boot"),
        new GlassDevice(13080, 1074, 29, "P55E App"),
        new GlassDevice(13080, 1063, 22, "P55 Boot"),
        new GlassDevice(13080, 1064, 23, "P55 App"),
        new GlassDevice(13080, 1061, 34, "Flora Uboot"),
        new GlassDevice(13080, 1062, 35, "Flora Kernel"),
        new GlassDevice(13080, 1077, 40, "Gina Uboot"),
        new GlassDevice(13080, 1078, 41, "Gina Kernel"),
        new GlassDevice(13080, 1079, 46, "Gf Uboot"),
        new GlassDevice(13080, 1080, 47, "Gf Kernel"),
        new GlassDevice(13080, 1085, 70, "Gs Uboot"),
        new GlassDevice(13080, 1086, 71, "Gs Kernel"),
        new GlassDevice(13080, 1081, 52, "Hylla Uboot"),
        new GlassDevice(13080, 1082, 53, "Hylla Kernel"),
        new GlassDevice(13080, 1083, 64, "Core Pro Boot"),
        new GlassDevice(13080, 1084, 65, "Core Pro App"),
        new GlassDevice(4251, 24578, 58, "Vidda Uboot"),
        new GlassDevice(4251, 24579, 59, "Vidda Kernel"),
        new GlassDevice(21315, 512, 33, "Flora RomCode"),
        new GlassDevice(16722, 37905, 39, "Gina RomCode")
    };

    private ByteBuffer readBuffer = ByteBuffer.allocateDirect(1024);
    private ByteBuffer writeBuffer = ByteBuffer.allocateDirect(1024);
    Map<String, UsbDeviceConnection> keyMap = new HashMap();
    Map<Integer, UsbPrivateFunction> fdMap = new HashMap();

    public interface IPermission {
        int getPermission(int i, int i2, String str);
    }

    // Native methods
    public native boolean NRBSPCheckServiceReady(String str);
    public native int NRBSPGetCameraStatus(String str);
    public native String NRBSPGetProperty(String str, String str2);
    public native UsbConfigList NRBSPGetUsbConfigAll(String str);
    public native int NRBSPSetUsbConfigAll(UsbConfigList usbConfigList, String str);
    public native boolean NRBSPWaitPilotReady(int i, String str);
    public native String NROTAGetFwVersion(int i, String str);
    public native int NROTAGetOtaNum(String str, String str2);
    public native int NROTAGetTotalNum(String str, String str2);
    public native short[] NROTASendMsg(long j, short[] sArr, String str);
    public native int NROTASetGlassToBoot(String str);
    public native int NROTAStart(String str, boolean z, String str2);
    public native XrealGlassTypeInfo getGlassSdkType(String str);
    public native String getSku(String str);
    public native int test_load();

    @Override
    public boolean isNative() {
        return false;
    }

    static {
        System.loadLibrary("ota-lib");
        TAG = "XrealGlasses";
    }

    private static boolean isFileExist(String str) {
        Log.d(TAG, "filePath: " + str);
        return new File(str).exists();
    }

    private static boolean isFileByName(String str) {
        return str.contains(".");
    }

    public static void copyFileFromAssets(Context context, String str, String str2) {
        Log.d(TAG, "copyFileFromAssets ");
        if (str2 != null) {
            boolean zEndsWith = str2.endsWith(".cfg");
            Log.d(TAG, "cfg file  skip ");
            if (!zEndsWith && isFileExist(str2)) {
                Log.d(TAG, "copyFileFromAssets  file has exist targetFileFullPath: " + str2);
                return;
            }
        }
        try {
            copyFile(context.getAssets().open(str), str2);
        } catch (IOException e) {
            Log.d(TAG, "copyFileFromAssets IOException-" + e.getMessage());
        }
    }

    private static void copyFile(InputStream inputStream, String str) {
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(new File(str));
            byte[] bArr = new byte[1024];
            while (true) {
                int i = inputStream.read(bArr);
                if (i != -1) {
                    fileOutputStream.write(bArr, 0, i);
                } else {
                    fileOutputStream.flush();
                    inputStream.close();
                    fileOutputStream.close();
                    return;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void copyFolderFromAssets(Context context, String str, String str2) {
        Log.d(TAG, "copyFolderFromAssets rootDirFullPath-" + str + " targetDirFullPath-" + str2);
        try {
            for (String str3 : context.getAssets().list(str)) {
                Log.d(TAG, "name-" + str + "/" + str3);
                if (isFileByName(str3)) {
                    Log.d(TAG, "copyFolderFromAssets: file");
                    copyFileFromAssets(context, str + "/" + str3, str2 + "/" + str3);
                } else {
                    Log.d(TAG, "copyFolderFromAssets: folder");
                    String str4 = str + "/" + str3;
                    String str5 = str2 + "/" + str3;
                    new File(str5).mkdirs();
                    copyFolderFromAssets(context, str4, str5);
                }
            }
        } catch (IOException e) {
            Log.d(TAG, "copyFolderFromAssets IOException-" + e.getMessage() + ", " + e.getLocalizedMessage());
        }
    }

    public XrealGlasses(Context context, IPermission iPermission) {
        this.context = context;
        this.iPermission = iPermission;
        this.usbManager = (UsbManager) context.getSystemService("usb");
        File dataDir = context.getDataDir();
        Log.d(TAG, "ota: target path - " + dataDir.getPath());
        copyFolderFromAssets(context, "nr_ota_default", dataDir.getPath() + File.separator + "nr_ota_default");
        Log.d(TAG, "Ota: end Ota");
    }

    private String getDefaultDir() {
        return this.context.getDataDir().getPath() + File.separator + "nr_ota_default";
    }

    @Override
    public int getOtaNum(String str) {
        return NROTAGetOtaNum(str, null);
    }

    @Override
    public int startOta(String str, boolean z, OtaCallback otaCallback, IReportCallback iReportCallback) {
        this.otaCb = otaCallback;
        this.reportCallback = iReportCallback;
        return NROTAStart(str, z, null);
    }

    int getPermission(int i, int i2, String str) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("you need invoke it with a new thread");
        }
        return this.iPermission.getPermission(i, i2, str);
    }

    void otaProgress(int i, int i2, int i3, int i4, String str) {
        String str2;
        Log.d(TAG, "otaProgress: " + i + ", " + i2 + ", " + i3 + ", " + i4 + ", " + str + ", " + (this.otaCb == null));
        OtaCallback otaCallback = this.otaCb;
        if (otaCallback != null && i == 1) {
            if (i2 == 1) { str2 = "mcu"; }
            else if (i2 == 2) { str2 = "dp"; }
            else if (i2 == 3) { str2 = "dsp"; }
            else if (i2 == 4) { str2 = "camera"; }
            else if (i2 == 5) { str2 = "bin_fw"; }
            else if (i2 == 6) { str2 = "app"; }
            else { str2 = "na"; }
            if (i3 == 0) {
                otaCallback.onOtaResult(str2, true);
                FileUtils.ReportInfo reportInfo = FileUtils.reportMap.get(str2);
                if (reportInfo != null) {
                    reportInfo.result = true;
                } else {
                    Log.d(TAG, str2.concat(" info is null - true"));
                }
                return;
            }
            if (i3 == 2) {
                otaCallback.onOtaResult(str2, false);
                FileUtils.ReportInfo reportInfo2 = FileUtils.reportMap.get(str2);
                if (reportInfo2 != null) {
                    reportInfo2.result = false;
                } else {
                    Log.d(TAG, str2.concat(" info is null - false"));
                }
                return;
            }
            if (this.step != i4) {
                this.step = i4;
                otaCallback.onOtaProgress(str2, i4);
            }
        }
    }

    private String getPhyPortName(UsbDevice usbDevice) {
        String str = null;
        try {
            str = (String) Class.forName("android.hardware.usb.UsbDevice").getDeclaredMethod("getPhyPortName", new Class[0]).invoke(usbDevice, new Object[0]);
            Log.d(TAG, "phyPortName: " + str);
            return str;
        } catch (Exception e) {
            Log.d(TAG, "find_x_port Exception");
            e.printStackTrace();
            return str;
        }
    }

    int getFd(int i, int i2, String str) {
        UsbDeviceConnection usbDeviceConnectionOpenDevice;
        int fileDescriptor = -1;
        int i3 = 0;
        while (fileDescriptor == -1 && i3 < 10) {
            HashMap<String, UsbDevice> deviceList = this.usbManager.getDeviceList();
            if (deviceList.size() > 0) {
                Iterator<UsbDevice> it = deviceList.values().iterator();
                while (true) {
                    if (!it.hasNext()) {
                        break;
                    }
                    UsbDevice next = it.next();
                    if (str == null || str.length() == 0) {
                        int vendorId = next.getVendorId();
                        int productId = next.getProductId();
                        if (vendorId == i && productId == i2) {
                            UsbDeviceConnection conn = this.usbManager.openDevice(next);
                            if (conn != null) {
                                fileDescriptor = conn.getFileDescriptor();
                                this.keyMap.put("nrSingle", conn);
                                UsbPrivateFunction usbPrivateFunction = new UsbPrivateFunction(next, conn, this.usbManager);
                                if (!usbPrivateFunction.check_device_is_correct()) {
                                    Log.d(TAG, "getFd: not the correct device");
                                    fileDescriptor = -1;
                                } else {
                                    this.fdMap.put(Integer.valueOf(fileDescriptor), usbPrivateFunction);
                                    Log.d(TAG, "getFd: get fd , usb info : " + vendorId + " " + productId + " " + fileDescriptor);
                                    break;
                                }
                            } else {
                                Log.d(TAG, "getFd: open usb device failed");
                            }
                        }
                    } else if (str.equals(getPhyPortName(next)) && (usbDeviceConnectionOpenDevice = this.usbManager.openDevice(next)) != null) {
                        fileDescriptor = usbDeviceConnectionOpenDevice.getFileDescriptor();
                        this.keyMap.put(str, usbDeviceConnectionOpenDevice);
                        UsbPrivateFunction usbPrivateFunction2 = new UsbPrivateFunction(next, usbDeviceConnectionOpenDevice, this.usbManager);
                        if (!usbPrivateFunction2.check_device_is_correct()) {
                            Log.d(TAG, "getFd: not the correct device");
                            fileDescriptor = -1;
                        } else {
                            this.fdMap.put(Integer.valueOf(fileDescriptor), usbPrivateFunction2);
                            break;
                        }
                    }
                }
            }
            i3++;
            if (fileDescriptor == -1) {
                Log.d(TAG, "getFd: try again count: " + i3);
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException e) {
                    Log.d(TAG, "getFd: try to sleep failed");
                    e.printStackTrace();
                }
            }
        }
        Log.d(TAG, "getFd in java : " + fileDescriptor + " count : " + i3);
        return fileDescriptor;
    }

    int hidRawWrite(int i, short s, ByteBuffer byteBuffer, int i2) {
        if (this.fdMap.containsKey(Integer.valueOf(i))) {
            return this.fdMap.get(Integer.valueOf(i)).usbRawWrite(s, byteBuffer, i2);
        }
        Log.d(TAG, "hidRawWrite: invalid fd");
        return -1;
    }

    int hidRawReadTimeout(int i, short s, ByteBuffer byteBuffer, int i2, int i3) {
        if (this.fdMap.containsKey(Integer.valueOf(i))) {
            return this.fdMap.get(Integer.valueOf(i)).usbRawReadTimeout(s, byteBuffer, i2, i3);
        }
        Log.d(TAG, "hidRawReadTimeout: invalid fd");
        return -1;
    }

    void closeFd(String str) {
        Log.d(TAG, "closeFd: java");
        if (str != null) {
            try {
                if (str.length() != 0) {
                    if (this.keyMap.containsKey(str)) {
                        if (this.fdMap.containsKey(Integer.valueOf(this.keyMap.get(str).getFileDescriptor()))) {
                            this.fdMap.get(Integer.valueOf(this.keyMap.get(str).getFileDescriptor())).releaseInterface();
                            this.fdMap.remove(Integer.valueOf(this.keyMap.get(str).getFileDescriptor()));
                        }
                        this.keyMap.get(str).close();
                        this.keyMap.remove(str);
                        return;
                    }
                    return;
                }
            } catch (Exception e) {
                Log.d(TAG, "closeFd: java has exception!");
                e.printStackTrace();
                return;
            }
        }
        if (this.keyMap.containsKey("nrSingle")) {
            Log.d(TAG, "close fd : " + this.keyMap.get("nrSingle").getFileDescriptor());
            if (this.fdMap.containsKey(Integer.valueOf(this.keyMap.get("nrSingle").getFileDescriptor()))) {
                this.fdMap.get(Integer.valueOf(this.keyMap.get("nrSingle").getFileDescriptor())).releaseInterface();
                this.fdMap.remove(Integer.valueOf(this.keyMap.get("nrSingle").getFileDescriptor()));
            }
            this.keyMap.get("nrSingle").close();
            this.keyMap.remove("nrSingle");
        }
    }

    private int isNio() {
        Log.d(TAG, "isNio: Product - " + Build.PRODUCT);
        return Build.PRODUCT.equalsIgnoreCase("nova_a") ? 1 : 0;
    }

    int getCurrentGlass(String str) {
        HashMap<String, UsbDevice> deviceList = this.usbManager.getDeviceList();
        Log.d(TAG, "getCurrentGlass: key : " + str);
        int i = 0;
        if (deviceList.size() > 0) {
            int i2 = 0;
            for (UsbDevice usbDevice : deviceList.values()) {
                if (str == null || str.length() == 0) {
                    int vendorId = usbDevice.getVendorId();
                    int productId = usbDevice.getProductId();
                    GlassDevice[] glassDeviceArr = this.glass_devices;
                    for (GlassDevice glassDevice : glassDeviceArr) {
                        if (vendorId == glassDevice.vid && productId == glassDevice.pid) {
                            Log.d(TAG, "getCurrentGlass: found " + glassDevice.glass_name);
                            i2 = glassDevice.glass_enum;
                            break;
                        }
                    }
                } else if (str.equals(getPhyPortName(usbDevice))) {
                    int vendorId2 = usbDevice.getVendorId();
                    int productId2 = usbDevice.getProductId();
                    GlassDevice[] glassDeviceArr2 = this.glass_devices;
                    for (GlassDevice glassDevice2 : glassDeviceArr2) {
                        if (vendorId2 == glassDevice2.vid && productId2 == glassDevice2.pid) {
                            Log.d(TAG, "getCurrentGlass: found " + glassDevice2.glass_name);
                            i2 = glassDevice2.glass_enum;
                            break;
                        }
                    }
                }
            }
            i = i2;
        }
        Log.d(TAG, "getCurrentGlass: type : " + i);
        return i;
    }
}
