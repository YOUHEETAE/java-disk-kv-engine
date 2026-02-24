package minidb.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class DummyDataGenerator {

    private static final long SEED = 42L;
    private static final Random RANDOM = new Random(SEED);
    private static final String[] DISTRICTS = {"Gangnam", "Seocho", "Jongno", "Yongsan", "Mapo"};
    private static final String[] PROVINCES = {"Seoul", "Busan", "Incheon", "Daegu", "Gwangju"};
    private static final double MIN_LAT = 33.0;
    private static final double MAX_LAT = 38.5;
    private static final double MIN_LNG = 126.0;
    private static final double MAX_LNG = 129.5;

    public static List<Hospital> generateDummyList(int count) {
        List<Hospital> hospitals = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            String hospitalCode = String.format("H%05d", i);
            double coordinateY = MIN_LAT + RANDOM.nextDouble() * (MAX_LAT - MIN_LAT);
            double coordinateX = MIN_LNG + RANDOM.nextDouble() * (MAX_LNG - MIN_LNG);
            String doctorNum = String.valueOf(1 + RANDOM.nextInt(50));
            String hospitalAddress = "Address " + i;
            String hospitalName = "Hospital " + i;
            String hospitalTel = "010-" + (1000 + RANDOM.nextInt(9000)) + "-" + (1000 + RANDOM.nextInt(9000));
            String districtName = DISTRICTS[RANDOM.nextInt(DISTRICTS.length)];
            String hospitalHomepage = "http://www.hospital" + i + ".com";
            String provinceName = PROVINCES[RANDOM.nextInt(PROVINCES.length)];

            hospitals.add(new Hospital(
                    hospitalCode, coordinateX, coordinateY,
                    doctorNum, hospitalAddress, hospitalName,
                    hospitalTel, districtName, hospitalHomepage, provinceName
            ));
        }
        return hospitals;
    }


    public static void main(String[] args) {
        List<Hospital> dummyData = DummyDataGenerator.generateDummyList(1000);
        System.out.println("Generated " + dummyData.size() + " Hospital objects");
        System.out.println(dummyData.get(0).hospitalName + ", " + dummyData.get(0).coordinateX + ", " + dummyData.get(0).coordinateY);
    }
}