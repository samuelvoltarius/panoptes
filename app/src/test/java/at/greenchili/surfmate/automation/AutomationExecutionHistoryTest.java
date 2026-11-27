/*
 * Based on OverDrive's AutomationExecutionHistoryTest (PR #733, MIT License,
 * Copyright (c) Yash Srivastava and OverDrive contributors); extended for Panoptes.
 */
package at.greenchili.surfmate.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.automation.condition.EventData;
import at.greenchili.surfmate.automation.value.StringValue;
import at.greenchili.surfmate.automation.value.Value;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Upstream #733: isolated rule evaluation and a bounded execution history. */
public class AutomationExecutionHistoryTest {

    private static final EventData SIGNAL = new EventData("ausfuehrungsprotokoll-test");

    @BeforeClass
    public static void configureLogger() {
        at.greenchili.surfmate.logging.DaemonLogger.Config config =
                new at.greenchili.surfmate.logging.DaemonLogger.Config();
        config.enableConsoleLog = false;
        config.enableFileLog = false;
        config.enableStdoutLog = true;
        at.greenchili.surfmate.logging.DaemonLogger.configure(config);
    }

    @Before
    public void clear() {
        AutomationExecutionHistory.clearForTest();
    }

    @Test
    public void historyIsNewestFirstAndBounded() throws Exception {
        for (int i = 0; i < 105; i++) {
            AutomationExecutionHistory.record("rule-" + i, "trigger", "queued", i,
                    "signal observed");
        }
        JSONArray history = AutomationExecutionHistory.snapshot(200);
        assertEquals(AutomationExecutionHistory.CAPACITY, history.length());
        assertEquals("rule-104", history.getJSONObject(0).getString("automationId"));
        assertEquals("rule-5", history.getJSONObject(99).getString("automationId"));
        assertTrue(history.getJSONObject(0).getLong("timestamp") > 0L);
        assertTrue(Automations.diagnosticContext("automation health")
                .getJSONArray("recentExecutions").length() > 0);
    }

    @Test
    public void detailIsBoundedAndOptional() throws Exception {
        AutomationExecutionHistory.record("rule", "execution", "failed", 12, repeat('x', 400));
        AutomationExecutionHistory.record("rule-2", "execution", "completed", 3, null);
        JSONArray history = AutomationExecutionHistory.snapshot(2);
        assertFalse(history.getJSONObject(0).has("detail"));
        assertEquals(AutomationExecutionHistory.MAX_DETAIL_LENGTH,
                history.getJSONObject(1).getString("detail").length());
    }

    @Test
    public void quickRepeatsAreCoalescedIntoTheNewestEntry() throws Exception {
        for (int i = 0; i < 5; i++) {
            AutomationExecutionHistory.record("busy", "trigger", "queued", i, "signal");
        }
        AutomationExecutionHistory.record("busy", "execution", "completed", 1, null);
        JSONArray history = AutomationExecutionHistory.snapshot(10);
        assertEquals(2, history.length());
        assertEquals("completed", history.getJSONObject(0).getString("status"));
        assertEquals(5, history.getJSONObject(1).getInt("repeats"));
    }

    @Test
    public void failedRuleEvaluationDoesNotPreventFollowingRules() throws Exception {
        final boolean[] laterRuleRan = {false};
        Automations.runIsolatedAutomationEvaluation("broken-rule", () -> {
            throw new IllegalStateException("broken condition");
        });
        Automations.runIsolatedAutomationEvaluation("healthy-rule", () -> laterRuleRan[0] = true);

        assertTrue(laterRuleRan[0]);
        JSONArray history = AutomationExecutionHistory.snapshot(2);
        assertEquals(1, history.length());
        assertEquals("broken-rule", history.getJSONObject(0).getString("automationId"));
        assertEquals("failed", history.getJSONObject(0).getString("status"));
    }

    /**
     * Kaputte Regel neben gesunder Regel, über die echte Trigger-Schleife: eine Regel wirft eine
     * Ausnahme in isTriggered, eine zweite einen Error in conditionsMet, die gesunde Regel
     * dahinter wird trotzdem ausgewertet.
     */
    @Test
    public void brokenRulesNextToAHealthyRuleInTheTriggerLoop() throws Exception {
        AtomicInteger healthyEvaluations = new AtomicInteger();
        Map<String, Automation> rules = new LinkedHashMap<>();
        rules.put("kaputt-ausnahme", new TestRule() {
            @Override public boolean isTriggered(EventData trigger) {
                throw new IllegalStateException("kaputte Definition");
            }
        });
        rules.put("kaputt-error", new TestRule() {
            @Override public boolean conditionsMet(Map<EventData, Value> state) {
                throw new NoClassDefFoundError("fehlende OEM-Klasse");
            }
        });
        rules.put("gesund", new TestRule() {
            @Override public boolean conditionsMet(Map<EventData, Value> state) {
                healthyEvaluations.incrementAndGet();
                return false;   // keine Bedingung erfüllt, kein Else → nichts in die Warteschlange
            }
        });

        Automations.evaluateTriggeredRules(rules, SIGNAL, new StringValue("alt"));

        assertEquals(1, healthyEvaluations.get());
        JSONArray history = AutomationExecutionHistory.snapshot(10);
        assertEquals(2, history.length());
        JSONObject newest = history.getJSONObject(0);
        JSONObject older = history.getJSONObject(1);
        assertEquals("kaputt-error", newest.getString("automationId"));
        assertEquals("failed", newest.getString("status"));
        assertEquals("NoClassDefFoundError", newest.getString("detail"));
        assertEquals("kaputt-ausnahme", older.getString("automationId"));
        assertEquals("IllegalStateException", older.getString("detail"));
        assertFalse(history.toString().contains("kaputte Definition"));
    }

    @Test
    public void unknownPreviousValueEvaluatesNoRule() {
        AtomicInteger evaluations = new AtomicInteger();
        Map<String, Automation> rules = new LinkedHashMap<>();
        rules.put("gesund", new TestRule() {
            @Override public boolean isTriggered(EventData trigger) {
                evaluations.incrementAndGet();
                return false;
            }
        });
        Automations.evaluateTriggeredRules(rules, SIGNAL, null);
        assertEquals(0, evaluations.get());
    }

    @Test(expected = OutOfMemoryError.class)
    public void fatalVmErrorsAreNotSwallowed() {
        Automations.runIsolatedAutomationEvaluation("fatal", () -> {
            throw new OutOfMemoryError("test");
        });
    }

    @Test
    public void pollerDiagnosticsRecordFailureTypeWithoutExceptionMessage() {
        Automations.recordPollerHealth("gear", "failed", 0L,
                new IllegalStateException("sensitive service payload"));
        String json = AutomationExecutionHistory.snapshot(1).toString();
        assertTrue(json.contains("IllegalStateException"));
        assertTrue(json.contains("signal:gear"));
        assertFalse(json.contains("sensitive service payload"));
    }

    /** Enabled rule that is triggered by every signal; subclasses break one step. */
    private static class TestRule extends Automation {
        TestRule() {
            super(Collections.<EventData>singletonList(SIGNAL),
                    Collections.<AutomationCondition>emptyList(), 0,
                    Collections.<AutomationAction>emptyList(), false);
        }

        @Override public boolean isTriggered(EventData trigger) {
            return true;
        }

        @Override public boolean conditionsMet(Map<EventData, Value> state) {
            return false;
        }

        @Override public boolean hasElseActions() {
            return false;
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
