/*
 * Ported from OverDrive (https://github.com/yash-srivastava/Overdrive-release), PR #733
 * (merge 8310103) and its follow-up in the v65.0 sync (4aba33b): coalescing of repeats.
 * Copyright (c) Yash Srivastava and OverDrive contributors. MIT License (see LICENSE).
 * Adapted for Panoptes.
 */
package at.greenchili.surfmate.automation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Small in-memory, privacy-safe history of automation trigger and execution outcomes.
 *
 * <p>Bounded to {@link #CAPACITY} entries (newest first). Details are capped at
 * {@link #MAX_DETAIL_LENGTH} characters and never carry action parameters or OEM exception
 * messages — only rule id, stage, status, duration and a short fixed description.
 * Nothing is persisted; a daemon restart starts empty.
 */
final class AutomationExecutionHistory {
    static final int CAPACITY = 100;
    static final int MAX_DETAIL_LENGTH = 240;
    /** A repeat of the newest entry (same rule, stage and status) this soon updates it instead. */
    private static final long COALESCE_MS = 10_000L;
    private static final Deque<JSONObject> entries = new ArrayDeque<>();

    private AutomationExecutionHistory() { }

    static synchronized void record(
            String automationId, String stage, String status,
            long durationMs, String detail) {
        try {
            long now = System.currentTimeMillis();
            JSONObject head = entries.peekFirst();
            if (head != null && head.optString("automationId").equals(safe(automationId))
                    && head.optString("stage").equals(safe(stage))
                    && head.optString("status").equals(safe(status))
                    && now >= head.optLong("timestamp")
                    && now - head.optLong("timestamp") < COALESCE_MS) {
                head.put("timestamp", now);
                head.put("durationMs", Math.max(0L, durationMs));
                head.put("repeats", head.optInt("repeats", 1) + 1);
                return;
            }
            JSONObject item = new JSONObject();
            item.put("timestamp", now);
            item.put("automationId", safe(automationId));
            item.put("stage", safe(stage));
            item.put("status", safe(status));
            item.put("durationMs", Math.max(0L, durationMs));
            if (detail != null && !detail.isEmpty()) {
                item.put("detail", detail.substring(0,
                        Math.min(detail.length(), MAX_DETAIL_LENGTH)));
            }
            entries.addFirst(item);
            while (entries.size() > CAPACITY) entries.removeLast();
        } catch (Throwable ignored) {
            // Diagnostics must never affect automation execution.
        }
    }

    static synchronized JSONArray snapshot(int limit) {
        JSONArray result = new JSONArray();
        int max = Math.max(0, Math.min(limit, CAPACITY));
        int count = 0;
        for (JSONObject item : entries) {
            if (count++ >= max) break;
            try {
                result.put(new JSONObject(item.toString()));
            } catch (Exception ignored) {
                // Skip a malformed diagnostic record rather than affecting the caller.
            }
        }
        return result;
    }

    static synchronized void clearForTest() {
        entries.clear();
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.length() <= MAX_DETAIL_LENGTH
                ? value : value.substring(0, MAX_DETAIL_LENGTH);
    }
}
