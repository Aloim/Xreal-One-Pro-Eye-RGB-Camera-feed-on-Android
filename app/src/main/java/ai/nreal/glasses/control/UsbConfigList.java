package ai.nreal.glasses.control;

public class UsbConfigList {
    public int ncm;
    public int ecm;
    public int uac;
    public int hid_ctrl;
    public int mtp;
    public int mass_storage;
    public int uvc0;
    public int uvc1;
    public int enable;

    public UsbConfigList() {
        this.ncm = 0;
        this.ecm = 0;
        this.uac = 0;
        this.hid_ctrl = 0;
        this.mtp = 0;
        this.mass_storage = 0;
        this.uvc0 = 0;
        this.uvc1 = 0;
        this.enable = 0;
    }

    public UsbConfigList(int ncm, int ecm, int uac, int hid_ctrl, int mtp, int mass_storage, int uvc0, int uvc1, int enable) {
        this.ncm = ncm;
        this.ecm = ecm;
        this.uac = uac;
        this.hid_ctrl = hid_ctrl;
        this.mtp = mtp;
        this.mass_storage = mass_storage;
        this.uvc0 = uvc0;
        this.uvc1 = uvc1;
        this.enable = enable;
    }
}
