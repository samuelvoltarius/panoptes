package at.greenchili.surfmate.daemon.telegram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TelegramCommandLocalizationTest {

    private static final long CHAT_ID = 123456L;

    @Test
    public void unknownCommandUsesLocalizedRouterMessage() {
        FakeCommandContext context = new FakeCommandContext();

        assertTrue(new CommandRouter(context).route(CHAT_ID, "/not-a-command"));

        assertEquals(Arrays.asList("router.unknown_command"), context.translationKeys());
        assertEquals(1, context.textMessages.size());
        assertEquals(CHAT_ID, context.textMessages.get(0).chatId);
        assertEquals("tr:router.unknown_command", context.textMessages.get(0).text);
    }

    @Test
    public void helpListsPanoptesCommandsWithLocalizedButtons() {
        FakeCommandContext context = new FakeCommandContext();

        assertTrue(new CommandRouter(context).route(CHAT_ID, "/hilfe"));

        List<String> keys = context.translationKeys();
        assertEquals("help.title", keys.get(0));
        assertTrue(keys.contains("help.status"));
        assertTrue(keys.contains("help.guard"));
        assertTrue(keys.contains("help.camera"));
        assertTrue(keys.contains("help.where"));
        assertTrue(keys.contains("help.charge"));
        assertTrue(keys.contains("help.help"));
        assertTrue(keys.contains("help.footer"));

        assertEquals(1, context.buttonMessages.size());
        ButtonMessage sent = context.buttonMessages.get(0);
        assertEquals(CHAT_ID, sent.chatId);
        assertTrue(sent.text.startsWith("tr:help.title"));
        assertButton(sent.buttons, 0, 0, "buttons.status", "cmd:/status");
        assertButton(sent.buttons, 0, 1, "buttons.events", "cmd:/aufnahmen");
        assertButton(sent.buttons, 1, 0, "buttons.camera", "cmd:/kamera");
        assertButton(sent.buttons, 1, 1, "buttons.location", "cmd:/wo");
    }

    @Test
    public void englishAliasAndBotSuffixReachTheSameCommand() {
        assertEquals("/hilfe", CommandRouter.canonical("/help"));
        assertEquals("/hilfe", CommandRouter.canonical("/Help@MeinAutoBot"));
        assertEquals("/waechter", CommandRouter.canonical("/guardian"));
        assertEquals("/waechter", CommandRouter.canonical("/wächter"));
        assertEquals("/kamera", CommandRouter.canonical("/camera"));
        assertEquals("/wo", CommandRouter.canonical("/where"));
        assertEquals("/laden", CommandRouter.canonical("/charge"));
        // Alte Befehle bleiben still erreichbar.
        assertEquals("/aufnahmen", CommandRouter.canonical("/events"));
        assertEquals("/aufnahme", CommandRouter.canonical("/download"));
        assertEquals("/fernzugang", CommandRouter.canonical("/url"));
        assertEquals("/dienste", CommandRouter.canonical("/daemons"));
        assertEquals("/dienst", CommandRouter.canonical("/daemon"));
        assertEquals("/sicherung", CommandRouter.canonical("/backup"));
        assertEquals("/wo", CommandRouter.canonical("/parking"));
        assertEquals(null, CommandRouter.canonical("/update"));
        assertEquals(null, CommandRouter.canonical("/sendlog"));
    }

    @Test
    public void startNoLongerArmsTheGuardian() {
        FakeCommandContext context = new FakeCommandContext();
        context.ipcResponse = jsonResponse(true);

        assertTrue(new CommandRouter(context).route(CHAT_ID, "/start"));

        assertEquals(0, context.ipcCalls);
        assertEquals(1, context.buttonMessages.size());
        assertEquals("tr:welcome.paired", context.buttonMessages.get(0).text);
    }

    @Test
    public void guardOnUsesLocalizedMessageAndButtons() {
        FakeCommandContext context = new FakeCommandContext();
        context.ipcResponse = jsonResponse(true);

        assertTrue(new CommandRouter(context).route(CHAT_ID, "/waechter an"));

        assertEquals("START", context.lastIpcCommand);
        assertEquals(1, context.buttonMessages.size());
        ButtonMessage sent = context.buttonMessages.get(0);
        assertEquals("tr:guard.on", sent.text);
        assertButton(sent.buttons, 0, 0, "buttons.guard_off", "cmd:/waechter aus");
        assertButton(sent.buttons, 0, 1, "buttons.status", "cmd:/status");
    }

    @Test
    public void legacyStopPutsGuardianToSleep() {
        FakeCommandContext context = new FakeCommandContext();
        context.ipcResponse = jsonResponse(true);

        assertTrue(new CommandRouter(context).route(CHAT_ID, "/stop"));

        assertEquals("STOP", context.lastIpcCommand);
        assertEquals("tr:guard.off", context.buttonMessages.get(0).text);
    }

    @Test
    public void guardOnFailureUsesLocalizedFailureMessage() {
        FakeCommandContext context = new FakeCommandContext();
        context.ipcResponse = jsonResponse(false);

        assertTrue(new CommandRouter(context).route(CHAT_ID, "/guardian on"));

        assertEquals(Arrays.asList("guard.on_failed"), context.translationKeys());
        assertEquals(1, context.textMessages.size());
        assertEquals("tr:guard.on_failed", context.textMessages.get(0).text);
        assertEquals(0, context.buttonMessages.size());
    }

    @Test
    public void onOffWordsInBothLanguages() {
        assertEquals(Boolean.TRUE, SurveillanceCommandHandler.parseOnOff("an"));
        assertEquals(Boolean.TRUE, SurveillanceCommandHandler.parseOnOff("on"));
        assertEquals(Boolean.FALSE, SurveillanceCommandHandler.parseOnOff("aus"));
        assertEquals(Boolean.FALSE, SurveillanceCommandHandler.parseOnOff("off"));
        assertEquals(null, SurveillanceCommandHandler.parseOnOff(""));
    }

    private static JSONObject jsonResponse(boolean success) {
        try {
            return new JSONObject().put("success", success);
        } catch (Exception e) {
            throw new AssertionError("Could not build test IPC response", e);
        }
    }

    private static void assertButton(String[][][] buttons, int row, int column,
                                     String translationKey, String callbackData) {
        assertEquals("tr:" + translationKey, buttons[row][column][0]);
        assertEquals(callbackData, buttons[row][column][1]);
    }

    private static final class FakeCommandContext implements CommandContext {
        final List<TranslationCall> translationCalls = new ArrayList<>();
        final List<TextMessage> textMessages = new ArrayList<>();
        final List<ButtonMessage> buttonMessages = new ArrayList<>();
        JSONObject ipcResponse;

        @Override
        public String tr(String key, Object... args) {
            Object[] capturedArgs = args == null ? new Object[0] : args.clone();
            translationCalls.add(new TranslationCall(key, capturedArgs));
            return "tr:" + key;
        }

        List<String> translationKeys() {
            List<String> keys = new ArrayList<>();
            for (TranslationCall call : translationCalls) keys.add(call.key);
            return keys;
        }

        @Override
        public boolean sendMessage(long chatId, String text) {
            textMessages.add(new TextMessage(chatId, text));
            return true;
        }

        @Override
        public boolean sendMessageWithButtons(long chatId, String text, String[][][] buttons) {
            buttonMessages.add(new ButtonMessage(chatId, text, buttons));
            return true;
        }

        @Override
        public boolean sendVideo(long chatId, String videoPath, String caption) {
            return true;
        }

        int ipcCalls;
        String lastIpcCommand;

        @Override
        public JSONObject sendIpcCommand(int port, JSONObject command) {
            ipcCalls++;
            lastIpcCommand = command == null ? null : command.optString("command", null);
            return ipcResponse;
        }

        @Override
        public String execShell(String command) {
            return "";
        }

        @Override
        public void log(String message) {
            // No-op for pure JVM tests.
        }
    }

    private static final class TranslationCall {
        final String key;
        final Object[] args;

        TranslationCall(String key, Object[] args) {
            this.key = key;
            this.args = args;
        }
    }

    private static final class TextMessage {
        final long chatId;
        final String text;

        TextMessage(long chatId, String text) {
            this.chatId = chatId;
            this.text = text;
        }
    }

    private static final class ButtonMessage {
        final long chatId;
        final String text;
        final String[][][] buttons;

        ButtonMessage(long chatId, String text, String[][][] buttons) {
            this.chatId = chatId;
            this.text = text;
            this.buttons = buttons;
        }
    }
}
