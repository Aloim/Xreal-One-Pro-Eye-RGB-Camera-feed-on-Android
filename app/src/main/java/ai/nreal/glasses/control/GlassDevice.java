package ai.nreal.glasses.control;

public class GlassDevice {
    public int vid;
    public int pid;
    public int glass_enum;
    public String glass_name;

    public GlassDevice(int vid, int pid, int glass_enum, String glass_name) {
        this.vid = vid;
        this.pid = pid;
        this.glass_enum = glass_enum;
        this.glass_name = glass_name;
    }
}
