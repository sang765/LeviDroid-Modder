package org.levimc.launcher.core.content;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Version stamping helpers used to write world version tags from the instance. */
public class WorldCreatorVersionTest {

    @Test
    public void parseVersionParts_fullVersion() {
        assertArrayEquals(new int[]{1, 26, 52, 3, 0}, WorldCreator.parseVersionParts("1.26.52.3"));
    }

    @Test
    public void parseVersionParts_padsToFive() {
        assertArrayEquals(new int[]{1, 21, 50, 0, 0}, WorldCreator.parseVersionParts("1.21.50"));
    }

    @Test
    public void parseVersionParts_ignoresNonNumericTail() {
        assertArrayEquals(new int[]{1, 21, 124, 0, 0}, WorldCreator.parseVersionParts("1.21.124-beta"));
    }

    @Test
    public void parseVersionParts_nullOrGarbageReturnsNull() {
        assertNull(WorldCreator.parseVersionParts(null));
        assertNull(WorldCreator.parseVersionParts("unknown"));
    }

    @Test
    public void inventoryVersionString_fromParts() {
        assertEquals("1.26.52.3", WorldCreator.inventoryVersionString(new int[]{1, 26, 52, 3, 0}));
        assertEquals("1.21.50", WorldCreator.inventoryVersionString(new int[]{1, 21, 50, 0, 0}));
        assertEquals("1.21.50", WorldCreator.inventoryVersionString(null));
    }

    @Test
    public void protocolFor_knownVersions() {
        assertEquals(748, WorldCreator.protocolFor(new int[]{1, 21, 44, 0, 0}));
        assertEquals(860, WorldCreator.protocolFor(new int[]{1, 21, 124, 0, 0}));
        assertEquals(2193, WorldCreator.protocolFor(new int[]{1, 26, 52, 3, 0}));
    }

    @Test
    public void protocolFor_unknownFallsBackToOldProtocol() {
        assertEquals(748, WorldCreator.protocolFor(null));
        assertEquals(748, WorldCreator.protocolFor(new int[]{9, 9, 9, 0, 0}));
    }
}
