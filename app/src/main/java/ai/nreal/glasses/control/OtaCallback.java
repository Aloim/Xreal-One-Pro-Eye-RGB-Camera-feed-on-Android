package ai.nreal.glasses.control;

public interface OtaCallback {
    @Deprecated
    void onFwVersion(int i, String str);

    @Deprecated
    void onNeedOta(int i, int i2);

    void onOtaFinished(boolean z);
    void onOtaProgress(String str, int i);
    void onOtaResult(String str, boolean z);
}
