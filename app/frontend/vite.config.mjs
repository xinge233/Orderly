import { existsSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";

const frontendRoot = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(frontendRoot, "..", "..");
const themesRoot = path.join(repositoryRoot, "themes");
const modulesRoot = path.join(repositoryRoot, "Module");
const previewApiTarget = process.env.ORDERLY_PREVIEW_API || "http://127.0.0.1:8080";

const THEME_CSS_ROUTE = /^\/api\/themes\/([^/]+)\/assets\/(.+\.css)$/;
const MODULE_CSS_ROUTE = /^\/api\/modules\/(module_[a-z0-9_]+)\/ui\/(.+\.css)$/;
const SAFE_THEME_ID = /^[a-z][a-z0-9-]{0,63}$/;
const SAFE_MODULE_ID = /^module_[a-z0-9_]+$/;
const SAFE_CSS_PATH = /^(?:[a-z0-9][a-z0-9.-]*\/)*[a-z0-9][a-z0-9.-]*\.css$/i;

function decodePathSegment(value) {
    try {
        return decodeURIComponent(value);
    } catch {
        return "";
    }
}

function sendCss(response, filePath) {
    const content = readFileSync(filePath);
    response.statusCode = 200;
    response.setHeader("Content-Type", "text/css; charset=utf-8");
    response.setHeader("Cache-Control", "no-store");
    response.end(content);
}

function sourceCssMiddleware() {
    return (request, response, next) => {
        if (!request.url || request.method !== "GET") {
            next();
            return;
        }

        const pathname = new URL(request.url, "http://127.0.0.1").pathname;
        const themeMatch = THEME_CSS_ROUTE.exec(pathname);
        if (themeMatch) {
            const themeId = decodePathSegment(themeMatch[1]);
            const assetPath = decodePathSegment(themeMatch[2]);
            if (!SAFE_THEME_ID.test(themeId) || !SAFE_CSS_PATH.test(assetPath)) {
                next();
                return;
            }
            const themeRoot = path.resolve(themesRoot, themeId);
            const filePath = path.resolve(themeRoot, assetPath);
            if (!filePath.startsWith(`${themeRoot}${path.sep}`)
                || !existsSync(filePath)
                || !statSync(filePath).isFile()) {
                next();
                return;
            }
            sendCss(response, filePath);
            return;
        }

        const moduleMatch = MODULE_CSS_ROUTE.exec(pathname);
        if (!moduleMatch) {
            next();
            return;
        }
        const moduleId = decodePathSegment(moduleMatch[1]);
        const assetPath = decodePathSegment(moduleMatch[2]);
        if (!SAFE_MODULE_ID.test(moduleId) || !SAFE_CSS_PATH.test(assetPath)) {
            next();
            return;
        }
        const moduleRoot = path.resolve(
            modulesRoot,
            moduleId,
            "src",
            "main",
            "resources",
            "orderly-ui"
        );
        const filePath = path.resolve(moduleRoot, assetPath);
        if (!filePath.startsWith(`${moduleRoot}${path.sep}`)
            || !existsSync(filePath)
            || !statSync(filePath).isFile()) {
            next();
            return;
        }
        sendCss(response, filePath);
    };
}

function isWatchedSourceStyle(filePath) {
    const normalized = path.resolve(filePath);
    const relativeToThemes = path.relative(themesRoot, normalized);
    if (relativeToThemes && !relativeToThemes.startsWith("..")
        && !path.isAbsolute(relativeToThemes)
        && normalized.toLowerCase().endsWith(".css")) {
        return true;
    }
    const relativeToModules = path.relative(modulesRoot, normalized);
    return relativeToModules
        && !relativeToModules.startsWith("..")
        && !path.isAbsolute(relativeToModules)
        && relativeToModules.includes(`${path.sep}orderly-ui${path.sep}`)
        && normalized.toLowerCase().endsWith(".css");
}

function orderlyDevPreviewPlugin() {
    return {
        name: "orderly-dev-preview",
        apply: "serve",
        transformIndexHtml() {
            return [
                {
                    tag: "link",
                    attrs: { rel: "stylesheet", href: "/preview/dev-preview.css" },
                    injectTo: "head"
                },
                {
                    tag: "script",
                    attrs: { type: "module", src: "/preview/dev-preview.js" },
                    injectTo: "body"
                }
            ];
        },
        configureServer(server) {
            server.middlewares.use(sourceCssMiddleware());
            server.watcher.add([
                path.join(themesRoot, "**", "*.css"),
                path.join(
                    modulesRoot,
                    "module_*",
                    "src",
                    "main",
                    "resources",
                    "orderly-ui",
                    "**",
                    "*.css"
                )
            ]);
            server.watcher.on("change", (filePath) => {
                if (isWatchedSourceStyle(filePath)) {
                    server.ws.send({ type: "full-reload", path: "*" });
                }
            });
        }
    };
}

export default defineConfig({
    root: frontendRoot,
    plugins: [orderlyDevPreviewPlugin()],
    server: {
        host: "127.0.0.1",
        port: 5173,
        strictPort: false,
        proxy: {
            "/api": {
                target: previewApiTarget,
                changeOrigin: true
            }
        }
    }
});
