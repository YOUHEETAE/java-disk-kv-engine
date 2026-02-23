package minidb.benchmark;

import java.nio.ByteBuffer;

public class Hospital {
    public final String hospitalCode;
    public final double coordinateX;   // 경도
    public final double coordinateY;   // 위도
    public final String doctorNum;
    public final String hospitalAddress;
    public final String hospitalName;
    public final String hospitalTel;
    public final String districtName;
    public final String hospitalHomepage;
    public final String provinceName;

    public Hospital(String hospitalCode, double coordinateX, double coordinateY,
                    String doctorNum, String hospitalAddress, String hospitalName,
                    String hospitalTel, String districtName,
                    String hospitalHomepage, String provinceName) {
        this.hospitalCode     = hospitalCode;
        this.coordinateX      = coordinateX;
        this.coordinateY      = coordinateY;
        this.doctorNum        = doctorNum;
        this.hospitalAddress  = hospitalAddress;
        this.hospitalName     = hospitalName;
        this.hospitalTel      = hospitalTel;
        this.districtName     = districtName;
        this.hospitalHomepage = hospitalHomepage;
        this.provinceName     = provinceName;
    }


    public static byte[] toBytes(Hospital h) {
        byte[] doctorBytes    = h.doctorNum.getBytes();
        byte[] addressBytes   = h.hospitalAddress.getBytes();
        byte[] nameBytes      = h.hospitalName.getBytes();
        byte[] telBytes       = h.hospitalTel.getBytes();
        byte[] districtBytes  = h.districtName.getBytes();
        byte[] homepageBytes  = h.hospitalHomepage.getBytes();
        byte[] provinceBytes  = h.provinceName.getBytes();

        int size = 8 + 8  // coordinateX, Y
                + 4 + doctorBytes.length
                + 4 + addressBytes.length
                + 4 + nameBytes.length
                + 4 + telBytes.length
                + 4 + districtBytes.length
                + 4 + homepageBytes.length
                + 4 + provinceBytes.length;

        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putDouble(h.coordinateX);
        buf.putDouble(h.coordinateY);
        buf.putInt(doctorBytes.length);   buf.put(doctorBytes);
        buf.putInt(addressBytes.length);  buf.put(addressBytes);
        buf.putInt(nameBytes.length);     buf.put(nameBytes);
        buf.putInt(telBytes.length);      buf.put(telBytes);
        buf.putInt(districtBytes.length); buf.put(districtBytes);
        buf.putInt(homepageBytes.length); buf.put(homepageBytes);
        buf.putInt(provinceBytes.length); buf.put(provinceBytes);

        return buf.array();
    }

    public static Hospital fromBytes(String hospitalCode, byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);

        double coordinateX = buf.getDouble();
        double coordinateY = buf.getDouble();

        String doctorNum       = readString(buf);
        String hospitalAddress = readString(buf);
        String hospitalName    = readString(buf);
        String hospitalTel     = readString(buf);
        String districtName    = readString(buf);
        String hospitalHomepage= readString(buf);
        String provinceName    = readString(buf);

        return new Hospital(
                hospitalCode, coordinateX, coordinateY,
                doctorNum, hospitalAddress, hospitalName,
                hospitalTel, districtName, hospitalHomepage, provinceName
        );
    }

    private static String readString(ByteBuffer buf) {
        int length = buf.getInt();
        byte[] bytes = new byte[length];
        buf.get(bytes);
        return new String(bytes);
    }
}