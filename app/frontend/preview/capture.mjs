import { access, mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright-core";

const previewRoot = path.dirname(fileURLToPath(import.meta.url));
const frontendRoot = path.resolve(previewRoot, "..");
const outputRoot = path.resolve(frontendRoot, "..", "..", "target", "ui-preview");
const baseUrl = process.env.ORDERLY_PREVIEW_URL || "http://127.0.0.1:5173/";

const edgeCandidates = [
    path.join(process.env.LOCALAPPDATA || "", "Microsoft", "Edge", "Application", "msedge.exe"),
    "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
    "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"
];

async function resolveEdge() {
    for (const candidate of edgeCandidates) {
        try {
            await access(candidate);
            return candidate;
        } catch {
            // Continue to the next common installation path.
        }
    }
    throw new Error("未找到 Microsoft Edge；可通过 ORDERLY_EDGE_PATH 指定浏览器路径。");
}

async function openContext(browser, viewport, settings) {
    const context = await browser.newContext({ viewport });
    await context.addInitScript((value) => {
        localStorage.setItem("orderly.settings.v1", JSON.stringify(value));
    }, settings);
    return context;
}

async function waitForApp(page) {
    await page.goto(baseUrl, { waitUntil: "load" });
    await page.locator("#main-content .view-stack, #main-content .loading-panel").first().waitFor();
    await page.locator("#ai-dock").waitFor();
    await page.locator(".preview-console").evaluate((node) => {
        node.style.display = "none";
    });
    await page.waitForTimeout(600);
}

async function capturePage(browser, {
    filename,
    viewport,
    theme = "paper",
    motion = "gentle",
    setup
}) {
    const context = await openContext(browser, viewport, {
        theme,
        motion,
        aiIcon: "spark",
        aiPanelWidth: 460,
        notifications: true,
        moduleOrderIds: []
    });
    const page = await context.newPage();
    const errors = [];
    page.on("pageerror", (error) => errors.push(error.message));
    try {
        await waitForApp(page);
        await setup?.(page);
        await page.evaluate(async () => {
            await document.fonts.ready;
            document.getAnimations().forEach((animation) => {
                try {
                    animation.finish();
                } catch {
                    // Infinite status animations cannot be finished; the screenshot keeps their current frame.
                }
            });
        });
        const outputPath = path.join(outputRoot, filename);
        await page.screenshot({ path: outputPath, fullPage: false });
        return { filename, errors };
    } finally {
        await context.close();
    }
}

const edgePath = process.env.ORDERLY_EDGE_PATH || await resolveEdge();
await mkdir(outputRoot, { recursive: true });
const browser = await chromium.launch({
    executablePath: edgePath,
    headless: true
});

try {
    const results = [];
    for (const viewport of [
        { width: 1440, height: 900, name: "1440x900" },
        { width: 1024, height: 768, name: "1024x768" },
        { width: 390, height: 844, name: "390x844" }
    ]) {
        results.push(await capturePage(browser, {
            filename: `overview-${viewport.name}.png`,
            viewport
        }));
    }

    results.push(await capturePage(browser, {
        filename: "settings-1440x900.png",
        viewport: { width: 1440, height: 900 },
        setup: async (page) => {
            await page.keyboard.press("Control+,");
            await page.locator("#settings-dialog[open]").waitFor();
            await page.waitForTimeout(320);
        }
    }));

    results.push(await capturePage(browser, {
        filename: "settings-390x844.png",
        viewport: { width: 390, height: 844 },
        setup: async (page) => {
            await page.keyboard.press("Control+,");
            await page.locator("#settings-dialog[open]").waitFor();
            await page.waitForTimeout(320);
        }
    }));

    results.push(await capturePage(browser, {
        filename: "settings-ai-1440x900.png",
        viewport: { width: 1440, height: 900 },
        setup: async (page) => {
            await page.keyboard.press("Control+,");
            await page.locator("#settings-dialog[open]").waitFor();
            await page.locator(".settings-nav__item").filter({ hasText: "AI 接入" }).click();
            await page.locator("#settings-panel").waitFor();
        }
    }));

    results.push(await capturePage(browser, {
        filename: "settings-system-1440x900.png",
        viewport: { width: 1440, height: 900 },
        setup: async (page) => {
            await page.keyboard.press("Control+,");
            await page.locator("#settings-dialog[open]").waitFor();
            await page.locator(".settings-nav__item").filter({ hasText: "系统设置" }).click();
            await page.locator("#settings-panel").waitFor();
        }
    }));

    results.push(await capturePage(browser, {
        filename: "ai-dock-390x844.png",
        viewport: { width: 390, height: 844 },
        setup: async (page) => {
            await page.locator("#ai-launcher").click();
            await page.locator('#ai-dock[data-composer="open"]').waitFor();
            await page.waitForTimeout(320);
        }
    }));

    results.push(await capturePage(browser, {
        filename: "history-timeline-1440x900.png",
        viewport: { width: 1440, height: 900 },
        setup: async (page) => {
            await page.locator('.nav-item[data-view="history"]').dispatchEvent("click");
            await page.locator("#history-timeline").waitFor();
        }
    }));

    results.push(await capturePage(browser, {
        filename: "history-summary-1440x900.png",
        viewport: { width: 1440, height: 900 },
        setup: async (page) => {
            await page.locator('.nav-item[data-view="history"]').dispatchEvent("click");
            await page.locator("#history-timeline").waitFor();
            await page.locator('[data-history-view="summary"]').click();
            await page.locator("#run-list").waitFor();
            await page.locator("#run-list .run-row").first().waitFor();
        }
    }));

    results.push(await capturePage(browser, {
        filename: "overview-motion-none-1440x900.png",
        viewport: { width: 1440, height: 900 },
        motion: "none"
    }));

    const errors = results.flatMap((result) =>
        result.errors.map((message) => `${result.filename}: ${message}`));
    console.log(JSON.stringify({
        outputRoot,
        screenshots: results.map((result) => result.filename),
        errors
    }, null, 2));
    if (errors.length > 0) {
        process.exitCode = 1;
    }
} finally {
    await browser.close();
}
