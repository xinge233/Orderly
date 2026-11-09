import {
  filterSidebar,
  getModules,
  loadRegistry,
  renderSidebar
} from "./registry.js";
import { applyTheme, getCurrentTheme } from "./theme.js";
import { initSettings } from "./settings.js";
import { initDock } from "./dock.js";

async function boot() {
  try {
    await applyTheme(getCurrentTheme());
  } catch {
    document.body.dataset.oThemeUnavailable = "true";
  }

  await loadRegistry();
  renderSidebar();
  initSettings();
  initDock();

  const search = document.querySelector("#sidebar-search");
  search.addEventListener("input", (event) => {
    filterSidebar(event.target.value);
  });

  document.querySelector("#module-count").textContent =
    String(getModules().length);
}

boot();
