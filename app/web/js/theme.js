const THEME_KEY = "orderly.theme";
const THEME_NAMES = ["default", "minimal"];
const THEME_FILES = {
  default: "./themes/default.json",
  minimal: "./themes/minimal.json"
};

const SEMANTIC_VARIABLES = [
  ["--accent-primary", "--raw-primary"],
  ["--accent-secondary", "--raw-secondary"],
  ["--danger-action", "--raw-danger"],
  ["--warning-action", "--raw-warning"],
  ["--surface-page", "--raw-page"],
  ["--surface-card", "--raw-card"],
  ["--text-primary", "--raw-text-primary"],
  ["--text-secondary", "--raw-text-secondary"],
  ["--border-subtle", "--raw-border"],
  ["--overlay", "--raw-overlay"],
  ["--elevation", "--raw-elevation"],
  ["--font-ui", "--raw-font-ui"],
  ["--radius-card", "--raw-radius-card"],
  ["--radius-control", "--raw-radius-control"],
  ["--space-1", "--raw-space-1"],
  ["--space-2", "--raw-space-2"],
  ["--space-3", "--raw-space-3"],
  ["--space-4", "--raw-space-4"],
  ["--space-5", "--raw-space-5"],
  ["--space-6", "--raw-space-6"],
  ["--duration-fast", "--raw-duration-fast"],
  ["--duration-base", "--raw-duration-base"],
  ["--sidebar-width", "--raw-sidebar-width"],
  ["--dock-collapsed-width", "--raw-dock-collapsed-width"],
  ["--dock-expanded-height", "--raw-dock-expanded-height"],
  ["--border-width", "--raw-border-width"]
];

let defaultTheme = null;
let currentTheme = window.localStorage.getItem(THEME_KEY) || "default";
const themeCache = new Map();

async function readTheme(name) {
  if (!themeCache.has(name)) {
    const response = await fetch(THEME_FILES[name]);
    if (!response.ok) {
      throw new Error(`Theme request failed: ${name}`);
    }
    themeCache.set(name, await response.json());
  }
  return themeCache.get(name);
}

export async function applyTheme(name) {
  const requested = THEME_NAMES.includes(name) ? name : "default";
  defaultTheme ||= await readTheme("default");
  const selectedTheme = requested === "default"
    ? defaultTheme
    : await readTheme(requested);
  const merged = {
    ...defaultTheme.raw,
    ...(selectedTheme.raw || {})
  };

  for (const [rawName, rawValue] of Object.entries(merged)) {
    document.documentElement.style.setProperty(rawName, rawValue);
  }

  for (const [semanticName, rawName] of SEMANTIC_VARIABLES) {
    const value = merged[rawName];
    if (value) {
      document.documentElement.style.setProperty(semanticName, value);
    }
  }

  currentTheme = requested;
  window.localStorage.setItem(THEME_KEY, requested);
  document.body.dataset.oTheme = requested;
  document.dispatchEvent(new CustomEvent("orderly:themechange", {
    detail: {
      name: selectedTheme.name,
      key: requested
    }
  }));
}

export function getThemeNames() {
  return [...THEME_NAMES];
}

export function getCurrentTheme() {
  return currentTheme;
}

export async function cycleTheme() {
  const index = THEME_NAMES.indexOf(currentTheme);
  const next = THEME_NAMES[(index + 1) % THEME_NAMES.length];
  await applyTheme(next);
  return next;
}
