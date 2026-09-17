package geoindex.test;

import geoindex.benchmark.DummyDataGenerator;
import geoindex.benchmark.Hospital;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 벤치마크가 재는 값이 믿을 만한지를 본다. 수치 자체는 환경마다 다르니 단정하지 않고,
 * 같은 입력에 같은 데이터가 나오는지와 두 경로의 답이 같은지만 본다.
 */
class BenchmarkTest {

    @Test
    void 같은_건수면_같은_데이터가_나온다() {
        List<Hospital> a = DummyDataGenerator.generateDummyList(100);
        List<Hospital> b = DummyDataGenerator.generateDummyList(100);

        for (int i = 0; i < 100; i++) {
            assertEquals(a.get(i).coordinateX, b.get(i).coordinateX, "호출 순서와 무관하게 같은 좌표여야 한다: " + i);
            assertEquals(a.get(i).coordinateY, b.get(i).coordinateY);
        }
    }

    @Test
    void 큰_목록의_앞부분은_작은_목록과_같다() {
        // 규모별 표의 각 행이 같은 데이터의 접두사여야 규모 효과만 비교하는 것이 된다
        List<Hospital> small = DummyDataGenerator.generateDummyList(100);
        List<Hospital> large = DummyDataGenerator.generateDummyList(1000);

        for (int i = 0; i < 100; i++) {
            assertEquals(small.get(i).coordinateX, large.get(i).coordinateX);
            assertEquals(small.get(i).coordinateY, large.get(i).coordinateY);
        }
    }
}
