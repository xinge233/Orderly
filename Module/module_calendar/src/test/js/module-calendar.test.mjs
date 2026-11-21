import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("module_calendar refreshes from terminal host events and cleans up subscriptions", async () => {
    const calendarUrl = new URL(
        "../../main/resources/orderly-ui/module.js",
        import.meta.url
    );
    const calendar = await readFile(calendarUrl, "utf8");
    const { calendarDataEvent } = await import(calendarUrl);
    const moduleId = "module_calendar";

    assert.equal(calendarDataEvent({ type: "STEP", moduleId, status: "COMPLETED" }, moduleId), true);
    assert.equal(calendarDataEvent({ type: "STEP", moduleId, status: "FAILED" }, moduleId), true);
    assert.equal(calendarDataEvent({ type: "ROLLBACK_STEP", moduleId, status: "ROLLED_BACK" }, moduleId), true);
    assert.equal(calendarDataEvent({ type: "ROLLBACK_STEP", moduleId, status: "FAILED" }, moduleId), true);
    assert.equal(calendarDataEvent({ type: "STEP", moduleId, status: "RUNNING" }, moduleId), false);
    assert.equal(calendarDataEvent({ type: "STEP", moduleId: "module_todo", status: "COMPLETED" }, moduleId), false);
    assert.equal(calendarDataEvent({ type: "EXECUTION", moduleId: null, status: "COMPLETED" }, moduleId), false);
    assert.match(calendar, /context\.subscribeEvents\(handleFrameworkEvent\)/);
    assert.match(calendar, /CALENDAR_EVENT_REFRESH_DELAY/);
    assert.match(calendar, /window\.clearTimeout\(dataRefreshTimer\)/);
    assert.match(calendar, /dataRefreshInFlight/);
    assert.match(calendar, /unsubscribeEvents\?\.\(\)/);
});

test("module_calendar aligns clock ticks to wall-clock second boundaries", async () => {
    const calendarUrl = new URL(
        "../../main/resources/orderly-ui/module.js",
        import.meta.url
    );
    const calendar = await readFile(calendarUrl, "utf8");
    const { clockDelayUntilNextSecond } = await import(calendarUrl);

    assert.equal(clockDelayUntilNextSecond(30_999), 5);
    assert.equal(clockDelayUntilNextSecond(30_000), 1004);
    assert.equal(clockDelayUntilNextSecond(30_001), 1003);
    assert.equal(clockDelayUntilNextSecond(Number.NaN), 1004);
    assert.match(calendar, /startClock\(\);/);
    assert.match(calendar, /window\.setTimeout\(tickClock, clockDelayUntilNextSecond\(\)\)/);
    assert.match(calendar, /document\.addEventListener\("visibilitychange", clockVisibilityHandler\)/);
    assert.match(calendar, /function stopClock\(\)[\s\S]*?window\.clearTimeout\(clockTimer\)/);
    assert.doesNotMatch(calendar, /setInterval\(renderClock/);
});
