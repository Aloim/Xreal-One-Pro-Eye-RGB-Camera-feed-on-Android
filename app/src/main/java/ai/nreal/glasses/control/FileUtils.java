package ai.nreal.glasses.control;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;

public final class FileUtils {
    private static final String TAG = "FileUtils";
    static HashMap<String, ReportInfo> reportMap = new HashMap<>();

    public static void moveAssetToStorageDir(Context context, String str) {
        String str2 = context.getFilesDir().getPath() + "/" + str;
        File file2 = new File(str2);
        if (file2.exists()) {
            RecursionDeleteFile(file2);
            file2.mkdir();
        } else {
            file2.mkdir();
        }
        XLog.m0d(TAG, "rootPath " + str2);
        try {
            String[] list = context.getAssets().list(str);
            for (int i = 0; i < list.length; i++) {
                XLog.m0d(TAG, "handle " + list[i]);
                if (new File(list[i]).isDirectory()) {
                    new File(str2 + list[i]).mkdir();
                    XLog.m0d(TAG, "copy " + list[i]);
                    moveAssetToStorageDir(context, list[i]);
                } else {
                    File file;
                    InputStream inputStreamOpen;
                    if (str.length() == 0) {
                        file = new File(str2 + list[i]);
                        inputStreamOpen = context.getAssets().open(list[i]);
                    } else {
                        file = new File(str2 + "/" + list[i]);
                        inputStreamOpen = context.getAssets().open(str + "/" + list[i]);
                    }
                    file.createNewFile();
                    XLog.m0d(TAG, "copy dest " + list[i]);
                    FileOutputStream fileOutputStream = new FileOutputStream(file);
                    byte[] bArr = new byte[inputStreamOpen.available()];
                    inputStreamOpen.read(bArr);
                    fileOutputStream.write(bArr);
                    fileOutputStream.close();
                    inputStreamOpen.close();
                }
            }
        } catch (Exception e) {
            XLog.m0d(TAG, "moveAssetToStorageDir: " + e.getMessage());
        }
    }

    public static void RecursionDeleteFile(File file) {
        if (file.isFile()) {
            file.delete();
            return;
        }
        if (file.isDirectory()) {
            File[] listFiles = file.listFiles();
            if (listFiles == null || listFiles.length == 0) {
                file.delete();
                return;
            }
            for (File f : listFiles) {
                RecursionDeleteFile(f);
            }
            file.delete();
        }
    }

    static class ReportInfo {
        String currentVersion;
        boolean force;
        boolean result;
        String toUpdateVersion;

        ReportInfo(String currentVersion, String toUpdateVersion, boolean result, boolean force) {
            this.currentVersion = currentVersion;
            this.toUpdateVersion = toUpdateVersion;
            this.result = result;
            this.force = force;
        }

        public String toString() {
            return "ReportInfo{currentVersion='" + this.currentVersion + "', toUpdateVersion='" + this.toUpdateVersion + "', result=" + this.result + ", force=" + this.force + '}';
        }
    }
}
