package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Ensures APK/manifest updates cannot keep serving an old same-named GLB. */
public class ModelsApiHandlerCacheValidationTest {

    @Test
    public void cachedModelMustMatchManifestSizeAndSha256() throws Exception {
        Path model = Files.createTempFile("surfmate-model-cache", ".glb");
        try {
            byte[] bytes = "verified-model-v12".getBytes(StandardCharsets.UTF_8);
            Files.write(model, bytes);
            String sha = hex(MessageDigest.getInstance("SHA-256").digest(bytes));

            assertTrue(ModelsApiHandler.cacheMatchesManifest(
                    model.toFile(), bytes.length, sha));
            assertTrue(ModelsApiHandler.cacheMatchesManifest(
                    model.toFile(), bytes.length, ""));
            assertFalse(ModelsApiHandler.cacheMatchesManifest(
                    model.toFile(), bytes.length + 1L, sha));
            assertFalse(ModelsApiHandler.cacheMatchesManifest(
                    model.toFile(), bytes.length,
                    "0000000000000000000000000000000000000000000000000000000000000000"));
        } finally {
            Files.deleteIfExists(model);
        }
    }

    @Test
    public void everyBundledModelMatchesItsManifestMetadata() throws Exception {
        Path manifestPath = repositoryPath(
                "app/src/main/assets/web/shared/models/manifest.json");
        JSONObject manifest = new JSONObject(new String(
                Files.readAllBytes(manifestPath), StandardCharsets.UTF_8));
        JSONArray models = manifest.getJSONArray("models");

        for (int i = 0; i < models.length(); i++) {
            JSONObject entry = models.getJSONObject(i);
            if (!entry.optBoolean("bundled", false)) continue;

            Path model = manifestPath.getParent().resolve(entry.getString("file"));
            assertTrue("Missing bundled model: " + model, Files.isRegularFile(model));
            assertTrue(entry.getString("id"),
                    ModelsApiHandler.cacheMatchesManifest(
                            model.toFile(),
                            entry.getLong("sizeBytes"),
                            entry.getString("sha256")));
        }
    }

    @Test
    public void manifestV19InvalidatesSealUFromV14AndContainsDmi() throws Exception {
        Path manifestPath = repositoryPath(
                "app/src/main/assets/web/shared/models/manifest.json");
        JSONObject manifest = new JSONObject(new String(
                Files.readAllBytes(manifestPath), StandardCharsets.UTF_8));
        assertEquals(19, manifest.getInt("version"));

        JSONObject sealU = model(manifest, "seal-u");
        assertEquals(931668L, sealU.getLong("sizeBytes"));
        assertEquals("368b0f4d46b2dd3db5252f555005d0b70ae05890a0441b0f2e461dfb3a92b2b6",
                sealU.getString("sha256"));

        // Simulates the same-named v14 cache file. The v19 metadata must force
        // a new download before the server reports it as ready.
        Path oldCache = Files.createTempFile("seal-u-v14", ".glb");
        try {
            Files.write(oldCache, new byte[931548]);
            assertFalse(ModelsApiHandler.cacheMatchesManifest(oldCache.toFile(),
                    sealU.getLong("sizeBytes"), sealU.getString("sha256")));
        } finally {
            Files.deleteIfExists(oldCache);
        }

        JSONObject dmi = model(manifest, "seal-u-dmi");
        assertNotEquals("BEV and DM-i must stay separate selectable models",
                sealU.getString("id"), dmi.getString("id"));
        assertEquals("seal-u-dmi.glb", dmi.getString("file"));
        assertTrue(dmi.getJSONArray("aliases").toString().contains("Song Plus DM-i"));
        assertEquals(100688L, dmi.getLong("sizeBytes"));
        assertEquals("166252c69ecd77060c15addae7eea29b9a63d6b0b781eab0d285e283f0868793",
                dmi.getString("sha256"));
        assertTrue(dmi.getBoolean("verfuegbar"));

        JSONObject shark = model(manifest, "shark");
        assertEquals("shark.glb", shark.getString("file"));
        assertNotEquals("Shark must never alias the Seal geometry", "seal.glb", shark.getString("file"));
        assertEquals("CC0-1.0", shark.getString("license"));

        JSONObject gt = model(manifest, "seal6-gt");
        JSONObject touring = model(manifest, "seal6-dmi-touring");
        assertEquals("seal6-gt.glb", gt.getString("file"));
        assertEquals("seal6-dmi-touring.glb", touring.getString("file"));
        assertFalse("Seal 6 GT stays hidden until visual approval", gt.getBoolean("verfuegbar"));
        assertFalse("Touring stays hidden until visual approval", touring.getBoolean("verfuegbar"));
        assertTrue(touring.getBoolean("phev"));

        String client = new String(Files.readAllBytes(repositoryPath(
                "app/src/main/assets/web/shared/vehicle-control.js")), StandardCharsets.UTF_8);
        assertTrue("picker values use the manifest id without aliasing", client.contains("opt.value = m.id"));
        assertTrue("selection persists the concrete manifest id",
                client.contains("this._saveSelected({ modelId: id })"));
    }

    @Test
    public void clientRefreshesActiveEntryAndHidesRawHttpErrors() throws Exception {
        String client = new String(Files.readAllBytes(repositoryPath(
                "app/src/main/assets/web/shared/vehicle-control.js")), StandardCharsets.UTF_8);
        assertTrue(client.contains("metadataChanged(oldEntry, newEntry)"));
        assertTrue(client.contains("self.loadModel(self.activeModelId)"));
        assertTrue(client.contains("vehicle.model_file_not_found"));
        assertTrue(client.contains("vehicle.model_validation_failed"));
        assertFalse(client.contains("onDone(null, s.error ||"));
        assertTrue(client.contains("if (s.downloaded === true)"));
        assertFalse(client.contains("s.state === 'done' || s.downloaded === true"));
        assertTrue(client.contains("HTTP\\s+(404|410)|not[ -]?found"));
        assertTrue(client.contains("xhr.status === 404 || xhr.status === 410"));
    }

    @Test
    public void oldManifestDownloadCannotWinRaceAgainstNewGeneration() throws Exception {
        String id = "seal-u";
        JSONObject oldEntry = new JSONObject()
                .put("id", id).put("file", "seal-u.glb")
                .put("sizeBytes", 931548L).put("sha256", repeat('a', 64));
        JSONObject newEntry = new JSONObject()
                .put("id", id).put("file", "seal-u.glb")
                .put("sizeBytes", 931668L).put("sha256", repeat('b', 64));
        JSONObject oldManifest = new JSONObject().put("version", 14)
                .put("models", new JSONArray().put(oldEntry));
        JSONObject newManifest = new JSONObject().put("version", 17)
                .put("models", new JSONArray().put(newEntry));

        ModelsApiHandler.DownloadState oldJob =
                new ModelsApiHandler.DownloadState(oldEntry, 14);
        ModelsApiHandler.DownloadState newJob =
                new ModelsApiHandler.DownloadState(newEntry, 17);
        try {
            ModelsApiHandler.downloads.put(id, oldJob);
            assertTrue(ModelsApiHandler.mayPublishDownload(id, oldJob, oldManifest));

            CountDownLatch oldJobReadyToPublish = new CountDownLatch(1);
            CountDownLatch newGenerationInstalled = new CountDownLatch(1);
            AtomicBoolean oldJobCouldPublish = new AtomicBoolean(true);
            Thread oldWorker = new Thread(() -> {
                oldJobReadyToPublish.countDown();
                try {
                    newGenerationInstalled.await(5, TimeUnit.SECONDS);
                    oldJobCouldPublish.set(ModelsApiHandler.mayPublishDownload(
                            id, oldJob, newManifest));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, "old-model-download");
            oldWorker.start();
            assertTrue(oldJobReadyToPublish.await(5, TimeUnit.SECONDS));

            // A v17 request replaces the active generation while the v14 worker
            // is at its publish barrier. Releasing it must not allow its rename.
            ModelsApiHandler.downloads.put(id, newJob);
            newGenerationInstalled.countDown();
            oldWorker.join(5000);
            assertFalse("old worker did not finish", oldWorker.isAlive());
            assertFalse(oldJobCouldPublish.get());
            assertTrue(ModelsApiHandler.mayPublishDownload(id, newJob, newManifest));
        } finally {
            ModelsApiHandler.downloads.remove(id);
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static JSONObject model(JSONObject manifest, String id) throws Exception {
        JSONArray models = manifest.getJSONArray("models");
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.getJSONObject(i);
            if (id.equals(model.getString("id"))) return model;
        }
        throw new AssertionError("Missing model: " + id);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private static Path repositoryPath(String relativePath) {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return candidate;

            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) return fromModule;
            current = at.greenchili.surfmate.testutil.RepoWurzel.hoch(current);
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
