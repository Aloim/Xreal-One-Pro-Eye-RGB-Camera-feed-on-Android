package ai.nreal.glasses.control;

public interface IOta {
    int getOtaNum(String str);
    boolean isNative();
    int startOta(String str, boolean z, OtaCallback otaCallback, IReportCallback iReportCallback);
}
