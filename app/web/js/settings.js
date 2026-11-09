import {
  applyTheme,
  cycleTheme,
  getCurrentTheme,
  getThemeNames
} from "./theme.js";
import {
  initMotion,
  isReducedMotion,
  setReducedMotion
} from "./motion.js";
import { getModules } from "./registry.js";

export function initSettings() {
  const dialog = document.querySelector("#settings-dialog");
  const trigger = document.querySelector("#settings-trigger");
  const close = document.querySelector("#settings-close");
  const featureContainer = document.querySelector("#feature-settings");
  const moduleNav = document.querySelector("#settings-module-nav");
  const sectionButtons = [...document.querySelectorAll("[data-settings-section]")];

  const render = () => {
    featureContainer.replaceChildren();
    moduleNav.replaceChildren();

    for (const module of getModules()) {
      const card = document.createElement("article");
      card.className = "o-settings-module";
      card.id = `feature-settings-${module.id.replaceAll(".", "-")}`;

      const title = document.createElement("h4");
      title.textContent = module.name || module.id;
      card.append(title);

      if (!Array.isArray(module.settings) || module.settings.length === 0) {
        const empty = document.createElement("p");
        empty.className = "o-muted";
        empty.textContent = "该模块暂无设置项";
        card.append(empty);
      } else {
        for (const setting of module.settings) {
          card.append(createField(setting));
        }
      }

      featureContainer.append(card);

      const navButton = document.createElement("button");
      navButton.type = "button";
      navButton.className = "o-settings-module-link";
      navButton.textContent = module.name || module.id;
      navButton.addEventListener("click", () => {
        showSection("features");
        card.scrollIntoView({ block: "nearest" });
      });
      moduleNav.append(navButton);
    }
  };

  const showSection = (name) => {
    const isSystem = name === "system";
    document.querySelector("#settings-system").hidden = !isSystem;
    document.querySelector("#settings-features").hidden = isSystem;
    for (const button of sectionButtons) {
      if (button.dataset.settingsSection === name) {
        button.setAttribute("aria-current", "page");
      } else {
        button.removeAttribute("aria-current");
      }
    }
  };

  trigger.addEventListener("click", () => {
    render();
    syncThemeSelect();
    syncMotionToggle();
    showSection("system");
    dialog.showModal();
  });

  close.addEventListener("click", () => dialog.close());
  dialog.addEventListener("click", (event) => {
    if (event.target === dialog) {
      dialog.close();
    }
  });

  for (const button of sectionButtons) {
    button.addEventListener("click", () => {
      showSection(button.dataset.settingsSection);
    });
  }

  document.querySelector("#theme-select").addEventListener("change", (event) => {
    applyTheme(event.target.value);
  });

  document.querySelector("#reduce-motion").addEventListener("change", (event) => {
    setReducedMotion(event.target.checked);
  });

  const themeToggle = document.querySelector("#theme-toggle");
  themeToggle.addEventListener("click", () => {
    cycleTheme();
  });

  document.addEventListener("orderly:themechange", syncThemeSelect);
  document.addEventListener("orderly:motionchange", syncMotionToggle);

  function syncThemeSelect() {
    const select = document.querySelector("#theme-select");
    select.value = getThemeNames().includes(getCurrentTheme())
      ? getCurrentTheme()
      : "default";
  }

  function syncMotionToggle() {
    document.querySelector("#reduce-motion").checked = isReducedMotion();
  }

  initMotion();
  syncThemeSelect();
  syncMotionToggle();
  return { render };
}

function createField(setting) {
  const row = document.createElement("label");
  row.className = `o-field o-field--${setting.control}`;

  const title = document.createElement("span");
  title.textContent = setting.label || setting.key;
  row.append(title);

  if (setting.control === "number") {
    const input = document.createElement("input");
    input.className = "o-input o-field__control";
    input.type = "number";
    input.value = String(setting.default);
    input.min = String(setting.min);
    input.max = String(setting.max);
    input.step = String(setting.step);
    row.append(input);
    return row;
  }

  if (setting.control === "toggle") {
    const input = document.createElement("input");
    input.className = "o-toggle o-field__control";
    input.type = "checkbox";
    input.checked = Boolean(setting.default);
    row.append(input);
    return row;
  }

  const select = document.createElement("select");
  select.className = "o-select o-field__control";
  for (const optionValue of setting.options || []) {
    const option = document.createElement("option");
    option.value = optionValue;
    option.textContent = optionValue;
    option.selected = optionValue === setting.default;
    select.append(option);
  }
  row.append(select);
  return row;
}
