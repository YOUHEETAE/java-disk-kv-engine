package minidb.test;

import minidb.api.RecordId;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

class RecordIdTest {

    @Test
    void testRecordIdCreation(){
        RecordId recordId = new RecordId(890,5);
        assertNotNull(recordId);
        assertEquals(890,recordId.getPageId());
        assertEquals(5,recordId.getSlotId());
    }

    @Test
    void testToString(){
        RecordId recordId = new RecordId(890,5);
        String str = recordId.toString();
        assertNotNull(str);
        assertTrue(str.contains("890"));
        assertTrue(str.contains("5"));
        System.out.println("toString: " + str);
    }

    @Test
    void testEquals(){
        RecordId recordId1 = new RecordId(890,5);
        RecordId recordId2 = new RecordId(890,5);
        RecordId recordId3 = new RecordId(891,5);
        RecordId recordId4 = new RecordId(890,6);
        assertEquals(recordId1,recordId2);
        assertNotEquals(recordId1,recordId3);
        assertNotEquals(recordId1,recordId4);
        assertEquals(recordId1,recordId1);
        assertNotEquals(null, recordId1);
        assertNotEquals("문자열", recordId1);
    }

    @Test
    void testHashCode(){
        RecordId recordId1 = new RecordId(890,5);
        RecordId recordId2 = new RecordId(890,5);
        RecordId recordId3 = new RecordId(891,5);

        assertEquals(recordId1.hashCode(),recordId2.hashCode());
        assertNotEquals(recordId1.hashCode(),recordId3.hashCode());

        System.out.println("rid1: " + recordId1.hashCode());
        System.out.println("rid2: " + recordId2.hashCode());
        System.out.println("rid3: " + recordId3.hashCode());
    }

    @Test
    void testHashMapKey(){
        HashMap< RecordId, String> map = new HashMap<>();
        RecordId recordId1 = new RecordId(890,5);
        map.put(recordId1, "data1");

        RecordId recordId2 = new RecordId(890,5);
        String value = map.get(recordId2);

        assertEquals("data1", value);
    }

    @Test
    void testHashSet() {
        Set<RecordId> set = new HashSet<>();

        set.add(new RecordId(890, 5));
        set.add(new RecordId(890, 5));
        set.add(new RecordId(891, 3));

        assertEquals(2, set.size());
    }

    @Test
    void testHashCodeConsistency() {
        RecordId rid = new RecordId(890, 5);

        int hash1 = rid.hashCode();
        int hash2 = rid.hashCode();
        int hash3 = rid.hashCode();

        assertEquals(hash1, hash2);
        assertEquals(hash2, hash3);
    }

    @Test
    void testEqualsSymmetric() {
        RecordId rid1 = new RecordId(890, 5);
        RecordId rid2 = new RecordId(890, 5);

        assertTrue(rid1.equals(rid2));
        assertTrue(rid2.equals(rid1));
    }

    @Test
    void testEqualsTransitive() {
        RecordId rid1 = new RecordId(890, 5);
        RecordId rid2 = new RecordId(890, 5);
        RecordId rid3 = new RecordId(890, 5);

        assertTrue(rid1.equals(rid2));
        assertTrue(rid2.equals(rid3));
        assertTrue(rid1.equals(rid3));
    }
}
