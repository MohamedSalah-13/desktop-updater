package com.hamza.updater;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionTest {

    @Test
    void readsATagWithOrWithoutTheLeadingV() {
        assertEquals(Version.of("2.4.0"), Version.of("v2.4.0"));
        assertEquals("2.4.0", Version.of("v2.4.0").toString());
        assertEquals("2.4.0", Version.of("2.4").toString());
    }

    @Test
    void ordersByNumberNotByText() {
        assertTrue(Version.of("2.10.0").isNewerThan(Version.of("2.9.9")));
        assertTrue(Version.of("10.0.0").isNewerThan(Version.of("9.99.99")));
        assertFalse(Version.of("2.4.0").isNewerThan(Version.of("2.4.0")));
        assertFalse(Version.of("2.3.9").isNewerThan(Version.of("2.4.0")));
    }

    @Test
    void missingNumbersAreZero() {
        assertEquals(Version.of("2.4"), Version.of("2.4.0"));
        assertEquals(Version.of("2.4").hashCode(), Version.of("2.4.0.0").hashCode());
        assertTrue(Version.of("2.4.0.1").isNewerThan(Version.of("2.4")));
    }

    @Test
    void aPreReleaseIsOlderThanItsRelease() {
        assertTrue(Version.of("2.5.0").isNewerThan(Version.of("2.5.0-beta.1")));
        assertTrue(Version.of("2.5.0-beta.2").isNewerThan(Version.of("2.5.0-beta.1")));
        assertTrue(Version.of("2.5.0-beta.10").isNewerThan(Version.of("2.5.0-beta.9")));
        assertTrue(Version.of("2.5.0-rc.1").isNewerThan(Version.of("2.5.0-beta.9")));
        assertTrue(Version.of("2.5.0-beta.1").isNewerThan(Version.of("2.4.9")));
        assertTrue(Version.of("2.5.0-beta").isPreRelease());
        assertEquals("2.5.0-beta.1", Version.of("v2.5.0-beta.1").toString());
    }

    @Test
    void buildMetadataIsIgnored() {
        assertEquals(Version.of("2.4.0"), Version.of("2.4.0+build.7"));
    }

    @Test
    void whatIsNotAVersionIsEmpty() {
        assertTrue(Version.parse(null).isEmpty());
        assertTrue(Version.parse("").isEmpty());
        assertTrue(Version.parse("latest").isEmpty());
        assertTrue(Version.parse("2.4.x").isEmpty());
        assertTrue(Version.parse("1.2.3.4.5").isEmpty());
        assertTrue(Version.parse("release-2.4.0").isEmpty());
    }
}
