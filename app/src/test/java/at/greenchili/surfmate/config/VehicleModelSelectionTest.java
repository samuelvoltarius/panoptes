package at.greenchili.surfmate.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VehicleModelSelectionTest {

    @Test
    public void exactSealUDmiAliasesResolveToOwnCatalogEntry() {
        for (String alias : new String[] {
                "seal-u-dmi", "Seal U DM-i", "SEAL U DMI", "BYD Seal U DMi",
                "Song Plus DM-i", "BYD SONG_PLUS_DMI"
        }) {
            assertEquals(alias, "seal-u-dmi", VehicleModelSelection.canonicalModelId(alias));
            assertEquals(alias, "seal-u-dmi", VehicleModelSelection.detectExactModelId(alias));
        }
        assertEquals("seal-u-dmi", VehicleModelSelection.resolvedModelId(
                "BYD Seal U DM-i", VehicleModelSelection.SOURCE_AUTO));
    }

    @Test
    public void sealAndSealionNamesNeverFuzzyMatchSealUDmi() {
        for (String other : new String[] {
                "Seal", "BYD Seal", "Seal U", "BYD Seal U", "Sealion 6",
                "BYD Sealion 7", "Seal 6 DM-i", "Song Plus EV", "BYD AUTO"
        }) {
            assertNull(other, VehicleModelSelection.detectExactModelId(other));
            assertNotEquals(other, "seal-u-dmi", VehicleModelSelection.canonicalModelId(other));
        }
        assertNull(VehicleModelSelection.detectExactModelId("BYD AUTO", "Sealion 7"));
    }

    @Test
    public void rendererFallbackIsNotASelectedPhysicalVehicle() {
        assertFalse(VehicleModelSelection.isResolvedSelection(
                "seal", VehicleModelSelection.SOURCE_UNSET));
        assertNull(VehicleModelSelection.resolvedModelId(
                "seal", VehicleModelSelection.SOURCE_UNSET));
    }

    @Test
    public void explicitAndAutoSelectionsAreResolved() {
        assertTrue(VehicleModelSelection.isResolvedSelection(
                "atto3", VehicleModelSelection.SOURCE_USER));
        assertEquals("atto3", VehicleModelSelection.resolvedModelId(
                " atto3 ", VehicleModelSelection.SOURCE_AUTO));
    }

    @Test
    public void preProvenanceConfigsRemainCompatible() {
        assertEquals(VehicleModelSelection.SOURCE_LEGACY,
                VehicleModelSelection.normalizeSource(null, "atto3"));
        assertTrue(VehicleModelSelection.isResolvedSelection("atto3", null));
    }

    @Test
    public void missingModelCannotBecomeASelection() {
        assertEquals(VehicleModelSelection.SOURCE_UNSET,
                VehicleModelSelection.normalizeSource(null, ""));
        assertFalse(VehicleModelSelection.isResolvedSelection(
                " ", VehicleModelSelection.SOURCE_USER));
    }
}
