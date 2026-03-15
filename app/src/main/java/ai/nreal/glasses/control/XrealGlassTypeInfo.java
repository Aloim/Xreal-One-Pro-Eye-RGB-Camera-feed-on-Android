package ai.nreal.glasses.control;

public class XrealGlassTypeInfo {
    private int mGlassType;
    private int mHwId;
    public final int SDK_GLASS_UNKNOWN = 0;
    public final int SDK_GLASS_GINA_M = 1;
    public final int SDK_GLASS_GINA_L = 2;
    public final int SDK_GLASS_MAX = 3;
    public final int SDK_GLASS_GF = 10;
    public final int SDK_GLASS_ERROR = 255;

    XrealGlassTypeInfo(int glassType, int hwId) {
        this.mGlassType = glassType;
        this.mHwId = hwId;
    }

    public int getGlassType() {
        return this.mGlassType;
    }

    public int getHwId() {
        return this.mHwId;
    }

    public String toString() {
        return "XrealGlassTypeInfo: [ mGlassType: " + this.mGlassType + ", mHwId: " + this.mHwId + "]";
    }
}
