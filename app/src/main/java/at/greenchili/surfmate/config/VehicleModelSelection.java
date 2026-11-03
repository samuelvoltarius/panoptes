package at.greenchili.surfmate.config;

import java.util.Locale;

/**
 * Provenance rules for {@code unified.vehicle.modelId}.
 *
 * <p>The 3D vehicle renderer needs a visual fallback, but that fallback must
 * not masquerade as a user-selected physical vehicle. In particular, a fresh
 * install historically wrote {@code modelId=seal}; camera auto-configuration
 * then treated every unconfigured BYD as a Seal before onboarding had asked
 * the owner which car they had.
 */
public final class VehicleModelSelection {
    public static final String SOURCE_UNSET = "unset";
    public static final String SOURCE_USER = "user";
    public static final String SOURCE_AUTO = "auto";
    public static final String SOURCE_LEGACY = "legacy";

    private VehicleModelSelection() {
    }

    /**
     * Canonicalises only exact, unambiguous catalogue aliases. In particular, plain {@code Seal},
     * {@code Seal U} and every {@code Sealion} spelling deliberately remain untouched: they are
     * different vehicles and must never select the DM-i model through a fuzzy substring match.
     */
    public static String canonicalModelId(String modelId) {
        if (!hasModelId(modelId)) return null;
        String raw = modelId.trim();
        String key = raw.toLowerCase(Locale.US)
                .replaceAll("[\\s_\\-–—]+", "");
        if ("sealudmi".equals(key) || "bydsealudmi".equals(key)
                || "songplusdmi".equals(key) || "bydsongplusdmi".equals(key)) {
            return "seal-u-dmi";
        }
        return raw;
    }

    /**
     * Exact fail-closed detection for Android product identity fields. Unknown values are ignored;
     * two different recognised identities are treated as ambiguous instead of choosing one.
     */
    public static String detectExactModelId(String... identities) {
        String found = null;
        if (identities == null) return null;
        for (String identity : identities) {
            String canonical = canonicalModelId(identity);
            if (!"seal-u-dmi".equals(canonical)) continue;
            if (found != null && !found.equals(canonical)) return null;
            found = canonical;
        }
        return found;
    }

    /**
     * Normalize persisted provenance. Configs written before provenance was
     * introduced keep their model as a legacy selection for compatibility.
     */
    public static String normalizeSource(String source, String modelId) {
        String normalized = source == null
                ? ""
                : source.trim().toLowerCase(Locale.US);
        if (SOURCE_UNSET.equals(normalized)
                || SOURCE_USER.equals(normalized)
                || SOURCE_AUTO.equals(normalized)
                || SOURCE_LEGACY.equals(normalized)) {
            return normalized;
        }
        return hasModelId(modelId) ? SOURCE_LEGACY : SOURCE_UNSET;
    }

    /**
     * True only when the model represents physical-vehicle intent rather than
     * the renderer's cosmetic fallback.
     */
    public static boolean isResolvedSelection(String modelId, String source) {
        if (!hasModelId(modelId)) return false;
        return !SOURCE_UNSET.equals(normalizeSource(source, modelId));
    }

    public static String resolvedModelId(String modelId, String source) {
        return isResolvedSelection(modelId, source) ? canonicalModelId(modelId) : null;
    }

    /**
     * 0.1.21: Das wirklich gewählte Modell aus {@code unified.vehicle}, sonst null („kein Modell gewählt“). Eine Regel
     * für /api/system/profile, /api/models/selected und /api/launcher/v1/summary – nie das alte „seal“ einer
     * Neuinstallation als Wahl ausgeben.
     */
    public static String gewaehlt(org.json.JSONObject vehicle) {
        if (vehicle == null) return null;
        String raw = vehicle.optString("modelId", "");
        return resolvedModelId(raw, vehicle.optString("modelSource", ""));
    }

    private static boolean hasModelId(String modelId) {
        return modelId != null && !modelId.trim().isEmpty();
    }
}
