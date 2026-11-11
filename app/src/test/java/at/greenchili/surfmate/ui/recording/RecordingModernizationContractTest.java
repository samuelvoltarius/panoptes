package at.greenchili.surfmate.ui.recording;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards the portrait/landscape recording UI contract and player states. */
public class RecordingModernizationContractTest {

    // 0.1.39 A1: bothRecordingScreensKeepCoreAndModernActions entfällt – prüfte nur die gelöschte alte Oberfläche

    // 0.1.39 A1: inlinePlayerHasPortraitRestoreGuard entfällt – prüfte nur die gelöschte alte Oberfläche

    @Test
    public void mediaAndPlayerExposeStableStatesAndLargeActions() throws IOException {
        // 0.1.39 A1: Prüfungen gelöschter Dateien (alte Oberfläche) entfernt
        String portraitItem = read("app/src/main/res/layout/item_recording.xml");
        String landscapeItem = read("app/src/main/res/layout-land/item_recording.xml");

        assertTrue(portraitItem.contains("RecordingAspectRatioFrameLayout"));
        assertTrue(landscapeItem.contains("android:layout_width=\"144dp\""));
    }

    @Test
    public void selectionAndThumbnailWorkAreLifecycleSafe() throws IOException {
        // 0.1.39 A1: Prüfungen gelöschter Dateien (alte Oberfläche) entfernt
        String adapter = read(
                "app/src/main/java/at/greenchili/surfmate/ui/adapter/RecordingAdapter.kt");

        assertTrue(adapter.contains("fun restoreSelection("));
        assertTrue(adapter.contains("fun getSelectedPaths()"));
        assertTrue(adapter.contains("fun selectAllLoaded()"));
        assertTrue(adapter.contains("fun retainSelection("));
        assertTrue(adapter.contains("fun dispose()"));
        assertTrue(adapter.contains("thumbnailScope.cancel()"));
        assertTrue(adapter.contains("thumbnailCache.evictAll()"));
    }

    @Test
    public void nativeRecordingCardsCreateTheSameAuthenticatedEvidencePack()
            throws IOException {
        // 0.1.39 A1: Prüfungen gelöschter Dateien (alte Oberfläche) entfernt
        String row = read("app/src/main/res/layout/item_recording.xml");
        String adapter = read(
                "app/src/main/java/at/greenchili/surfmate/ui/adapter/RecordingAdapter.kt");
        String client = read(
                "app/src/main/java/at/greenchili/surfmate/ui/util/RecordingsApiClient.kt");
        String providerPaths = read("app/src/main/res/xml/file_paths.xml");

        assertTrue(row.contains("@+id/btnMore"));
        assertTrue(adapter.contains("R.string.action_create_evidence_pack"));
        assertTrue(adapter.contains("onEvidencePack?.invoke(recording)"));
        assertTrue(client.contains("DaemonHttpClient.open("));
        assertTrue(client.contains("\"/api/genai/incidents\""));
        assertTrue(client.contains("\"/api/genai/incidents/$packId/download\""));
        assertTrue(client.contains("Thread.currentThread().isInterrupted"));
        assertTrue(providerPaths.contains("path=\"incident-packs/\""));
    }

    @Test
    public void optionalMetadataCannotChangeRepeatedTileHeight() throws IOException {
        String portrait = read("app/src/main/res/layout/item_recording.xml");
        String landscape = read("app/src/main/res/layout-land/item_recording.xml");
        String adapter = read(
                "app/src/main/java/at/greenchili/surfmate/ui/adapter/RecordingAdapter.kt");

        for (String layout : new String[] {portrait, landscape}) {
            String location = openingTagForId(layout, "tvLocation");
            assertTrue(location.contains("android:minLines=\"1\""));
            assertTrue(location.contains("android:visibility=\"invisible\""));
        }
        String actor = openingTagForId(landscape, "tvActorSummary");
        assertTrue(actor.contains("android:minLines=\"1\""));
        assertTrue(actor.contains("android:visibility=\"invisible\""));
        assertTrue(openingTagForId(portrait, "tvSize")
                .contains("android:singleLine=\"true\""));
        for (String id : new String[] {
                "tvTypeBadge", "tvSeverity", "tvStorageBadge", "tvSize"
        }) {
            String metadata = openingTagForId(landscape, id);
            assertTrue(id + " must not increase repeated tile height",
                    metadata.contains("android:singleLine=\"true\""));
            assertTrue(id + " must truncate safely",
                    metadata.contains("android:ellipsize=\"end\""));
        }
        assertTrue(adapter.contains("tvLocation?.visibility = View.INVISIBLE"));
        assertTrue(adapter.contains("tvActorSummary?.visibility = View.INVISIBLE"));
    }

    @Test
    public void landscapeLibraryRowReservesTheFilenameLine()
            throws IOException {
        String row = read(
                "app/src/main/res/layout/item_recording_landscape.xml");

        assertTrue(row.contains("android:layout_height=\"136dp\""));
        assertTrue(openingTagForId(row, "tvFilename")
                .contains("android:maxLines=\"1\""));
    }

    @Test
    public void daemonMediaUrlsStayQualifiedToTheIndexedVolume() throws IOException {
        String index = read(
                "app/src/main/java/at/greenchili/surfmate/server/RecordingsIndex.java");
        String handler = read(
                "app/src/main/java/at/greenchili/surfmate/server/RecordingsApiHandler.java");
        String client = read(
                "app/src/main/java/at/greenchili/surfmate/ui/util/RecordingsApiClient.kt");

        assertTrue(index.contains(
                "rec.put(\"videoUrl\", \"/video/id/\" + recordingId);"));
        assertTrue(index.contains(
                "rec.put(\"thumbnailUrl\", \"/thumb/id/\" + recordingId);"));
        assertTrue(index.contains(
                "rec.put(\"eventUrl\", \"/api/events/id/\" + recordingId);"));
        assertTrue(index.contains("String mediaQuery = mediaPathQuery(absPath);"));
        assertTrue(index.contains(
                "rec.put(\"legacyVideoUrl\", \"/video/\" + name + mediaQuery);"));
        assertTrue(index.contains(
                "rec.put(\"legacyThumbnailUrl\", \"/thumb/\" + name + mediaQuery);"));
        assertTrue(handler.contains(
                "serveThumbnail(out, filename, mediaRequestedPath(path));"));
        assertTrue(handler.contains(
                "deleteRecording(out, filename, mediaRequestedPath(path));"));
        assertTrue(handler.contains(
                "serveEventTimeline(out, filename, mediaRequestedPath(path));"));
        assertTrue(handler.contains("isAllowedRecordingPath(requested)"));
        assertTrue(client.contains(
                "absoluteVideoUrl.replaceFirst(\"/video/\", \"/api/events/\")"));
    }

    @Test
    public void asyncTimelineAndThumbnailResultsCannotRebindStaleViews()
            throws IOException {
        // 0.1.39 A1: Prüfungen gelöschter Dateien (alte Oberfläche) entfernt
        String adapter = read(
                "app/src/main/java/at/greenchili/surfmate/ui/adapter/RecordingAdapter.kt");

        assertTrue(adapter.contains(
                "override fun onViewRecycled(holder: RecordingViewHolder)"));
        assertTrue(adapter.contains("holder.recycle()"));
        assertTrue(adapter.contains("thumbnailJob?.cancel()"));
        assertTrue(adapter.contains(
                "bindingAdapterPosition != RecyclerView.NO_POSITION"));
        assertTrue(adapter.contains(
                "getItem(bindingAdapterPosition).path == recording.path"));
        assertTrue(adapter.contains("inSampleSize = sampleSize("));
    }

    // 0.1.39 A1: recordingControlsAndFullscreenBackKeepTheirContracts entfällt – prüfte nur die gelöschte alte Oberfläche

    // 0.1.39 A1: inlinePlayerKeepsUsableSpaceAndAutoHidingControls entfällt – prüfte nur die gelöschte alte Oberfläche

    private static String openingTagForId(String xml, String id) {
        String marker = "android:id=\"@+id/" + id + "\"";
        int idIndex = xml.indexOf(marker);
        if (idIndex < 0) {
            throw new AssertionError("Missing view id: " + id);
        }
        int start = xml.lastIndexOf('<', idIndex);
        int end = xml.indexOf('>', idIndex);
        if (start < 0 || end < 0) {
            throw new AssertionError("Malformed opening tag for: " + id);
        }
        return xml.substring(start, end + 1);
    }

    private static String read(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = at.greenchili.surfmate.testutil.RepoWurzel.hoch(current);
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
