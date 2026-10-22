package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.automation.Automations;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Regression coverage for live locale changes on the schema-driven Automations page. */
public class AutomationSchemaLocaleTest {

    @Before
    public void seedMessageCatalogs() throws Exception {
        Messages.invalidate();
        Field catalogsField = Messages.class.getDeclaredField("CATALOGS");
        catalogsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, JSONObject> catalogs =
                (Map<String, JSONObject>) catalogsField.get(null);
        synchronized (Messages.class) {
            catalogs.put("en", readCatalog("en"));
            catalogs.put("fr", readCatalog("fr"));
        }

        // The delay schema used to cache Messages.get(...) as its Label key. Reset it so
        // this test reproduces the real fr-first -> English switch in either test order.
        Field delayField = Automations.class.getDeclaredField("delay");
        delayField.setAccessible(true);
        delayField.set(null, null);
    }

    @After
    public void clearMessageCatalogs() {
        Messages.invalidate();
    }

    @Test
    public void schemaUsesRequestedLocaleAcrossFrenchToEnglishSwitch() throws Exception {
        JSONObject enAuto = readCatalog("en").getJSONObject("automation");
        JSONObject frAuto = readCatalog("fr").getJSONObject("automation");

        JSONArray french = requestSchema("fr");
        assertEquals(frAuto.getString("delay"),
                findSection(french, "delay").getString("label"));
        assertEquals(frAuto.getString("send_notification"),
                findOption(french, "actions", "notification").getString("label"));

        JSONArray english = requestSchema("en");
        assertEquals(enAuto.getString("delay"),
                findSection(english, "delay").getString("label"));
        assertEquals(enAuto.getString("send_notification"),
                findOption(english, "actions", "notification").getString("label"));
    }

    @Test
    public void nestedLocaleOverridesRestoreTheOuterRequest() throws Exception {
        String en = readCatalog("en").getJSONObject("automation").getString("delay");
        String fr = readCatalog("fr").getJSONObject("automation").getString("delay");
        String rendered = Messages.withLocale("fr", () ->
                Messages.get("automation.delay") + "|"
                        + Messages.withLocale("en",
                                () -> Messages.get("automation.delay"))
                        + "|" + Messages.get("automation.delay"));

        assertEquals(fr + "|" + en + "|" + fr, rendered);
    }

    @Test
    public void pageReloadsEverySchemaDrivenSurfaceAfterLocaleChange() throws Exception {
        String script = new String(Files.readAllBytes(findRepositoryFile(
                "app/src/main/assets/web/shared/automations.js")), StandardCharsets.UTF_8);
        String page = new String(Files.readAllBytes(findRepositoryFile(
                "app/src/main/assets/web/local/automations.html")), StandardCharsets.UTF_8);

        assertTrue(script.contains(
                "'/api/automations/schema?lang=' + encodeURIComponent(lang)"));
        assertTrue(script.contains(
                "BYD.i18n.onChange(() => this.refreshLocale());"));
        assertTrue(script.contains("this.renderGroupList();"));
        assertTrue(script.contains("AudioLibrary.refresh();"));
        assertTrue(script.contains("CommunityAutomations.loadPage("));
        assertTrue(page.contains("automations.js?v=av72"));
    }

    private static JSONArray requestSchema(String lang) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(AutomationApiHandler.handle(
                "GET", "/api/automations/schema?lang=" + lang, "", out));
        String response = new String(out.toByteArray(), StandardCharsets.UTF_8);
        int bodyStart = response.indexOf("\r\n\r\n");
        assertTrue("schema response did not contain an HTTP body", bodyStart >= 0);
        return new JSONArray(response.substring(bodyStart + 4));
    }

    private static JSONObject findSection(JSONArray schema, String id) throws Exception {
        for (int i = 0; i < schema.length(); i++) {
            JSONObject section = schema.getJSONObject(i);
            if (id.equals(section.optString("id"))) return section;
        }
        throw new AssertionError("Missing schema section: " + id);
    }

    private static JSONObject findOption(
            JSONArray schema, String sectionId, String optionId) throws Exception {
        JSONArray options = findSection(schema, sectionId).getJSONArray("options");
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.getJSONObject(i);
            if (optionId.equals(option.optString("id"))) return option;
        }
        throw new AssertionError("Missing " + sectionId + " option: " + optionId);
    }

    private static JSONObject readCatalog(String lang) throws Exception {
        Path path = findRepositoryFile(
                "app/src/main/assets/server-i18n/" + lang + ".json");
        return new JSONObject(new String(
                Files.readAllBytes(path), StandardCharsets.UTF_8));
    }

    private static Path findRepositoryFile(String relativePath) {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return candidate;
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) return fromModule;
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
