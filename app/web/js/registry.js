const ORDER_KEY = "orderly.sidebarOrder";
let modules = [];
let draggedItem = null;

const ALLOWED_ICONS = new Set([
  "archive",
  "calendar",
  "file",
  "folder",
  "search",
  "settings",
  "spark",
  "todo"
]);

export async function loadRegistry() {
  const url = new URL(window.location.href);
  if (url.searchParams.get("empty") === "1") {
    modules = [];
    return modules;
  }

  try {
    const response = await fetch("./mock/registry.json");
    if (!response.ok) {
      throw new Error("Registry request failed");
    }
    const payload = await response.json();
    modules = Array.isArray(payload.modules) ? payload.modules : [];
  } catch {
    modules = [];
  }

  const savedOrder = readSavedOrder();
  if (savedOrder.length > 0) {
    const byId = new Map(modules.map((module) => [module.id, module]));
    const ordered = savedOrder
      .map((id) => byId.get(id))
      .filter(Boolean);
    const remaining = modules.filter((module) => !savedOrder.includes(module.id));
    modules = [...ordered, ...remaining];
  }

  return modules;
}

export function renderSidebar() {
  const list = document.querySelector("#sidebar-items");
  const empty = document.querySelector("#sidebar-empty");
  const count = document.querySelector("#module-count");
  list.replaceChildren();

  modules.forEach((module, index) => {
    const item = document.createElement("li");
    item.className = "o-module-item";
    item.dataset.moduleId = module.id;

    const button = document.createElement("button");
    button.type = "button";
    button.className = "o-module-button";
    button.draggable = true;
    button.dataset.moduleId = module.id;
    button.dataset.risk = module.risk || "read_only";
    button.dataset.accent = module.ui?.accent || "accent.secondary";
    button.dataset.slot = "sidebar.item";
    if (index === 0) {
      button.setAttribute("aria-current", "page");
    }

    const icon = document.createElement("span");
    const iconName = ALLOWED_ICONS.has(module.ui?.icon) ? module.ui.icon : "file";
    icon.className = `o-icon o-icon--${iconName}`;
    icon.setAttribute("aria-hidden", "true");

    const name = document.createElement("span");
    name.className = "o-module-button__name";
    name.textContent = module.name || module.id;

    const risk = document.createElement("span");
    risk.className = "o-module-button__risk";
    risk.setAttribute("aria-hidden", "true");

    button.append(icon, name, risk);
    button.addEventListener("click", () => {
      for (const candidate of list.querySelectorAll(".o-module-button")) {
        candidate.removeAttribute("aria-current");
      }
      button.setAttribute("aria-current", "page");
    });
    bindDrag(button, item);
    item.append(button);
    list.append(item);
  });

  empty.hidden = modules.length > 0;
  count.textContent = String(modules.length);
  filterSidebar(document.querySelector("#sidebar-search")?.value || "");
}

function bindDrag(button, item) {
  button.addEventListener("dragstart", (event) => {
    draggedItem = item;
    item.classList.add("is-dragging");
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = "move";
      event.dataTransfer.setData("text/plain", item.dataset.moduleId);
    }
  });

  button.addEventListener("dragover", (event) => {
    event.preventDefault();
    if (!draggedItem || draggedItem === item) {
      return;
    }
    const rect = item.getBoundingClientRect();
    const before = event.clientY < rect.top + rect.height / 2;
    const parent = item.parentElement;
    parent.insertBefore(draggedItem, before ? item : item.nextSibling);
  });

  button.addEventListener("dragend", () => {
    item.classList.remove("is-dragging");
    draggedItem = null;
    const list = document.querySelector("#sidebar-items");
    const order = [...list.querySelectorAll(".o-module-item")]
      .map((entry) => entry.dataset.moduleId);
    window.localStorage.setItem(ORDER_KEY, JSON.stringify(order));
  });
}

export function filterSidebar(query) {
  const normalized = query.trim().toLocaleLowerCase("zh-CN");
  const list = document.querySelector("#sidebar-items");
  let visibleCount = 0;

  for (const item of list.querySelectorAll(".o-module-item")) {
    const module = modules.find((candidate) => candidate.id === item.dataset.moduleId);
    const text = `${module?.name || ""} ${module?.summary || ""}`
      .toLocaleLowerCase("zh-CN");
    const visible = normalized.length === 0 || text.includes(normalized);
    item.hidden = !visible;
    if (visible) {
      visibleCount += 1;
    }
  }

  const empty = document.querySelector("#sidebar-empty");
  empty.hidden = modules.length > 0 && visibleCount > 0;
  if (modules.length > 0 && visibleCount === 0) {
    empty.textContent = "没有匹配的功能";
  } else {
    empty.textContent = "还没有可显示的模块";
  }
}

export function getModules() {
  return [...modules];
}

export function getFirstVisibleButton() {
  return document.querySelector(".o-module-item:not([hidden]) .o-module-button");
}

function readSavedOrder() {
  try {
    const value = JSON.parse(window.localStorage.getItem(ORDER_KEY) || "[]");
    return Array.isArray(value) ? value : [];
  } catch {
    return [];
  }
}
