const STORAGE_KEY = "orderly.reduceMotion";
const lastTriggered = new Map();
const RAPID_REPEAT_WINDOW = 1000;
const HIGHLIGHT_DURATION = 760;

let systemReduced = false;

export function initMotion() {
  const media = window.matchMedia("(prefers-reduced-motion: reduce)");
  systemReduced = media.matches;
  const configured = window.localStorage.getItem(STORAGE_KEY) === "true";
  applyMotionState(configured || systemReduced);

  const onChange = (event) => {
    systemReduced = event.matches;
    applyMotionState(isReducedMotion());
  };

  if (typeof media.addEventListener === "function") {
    media.addEventListener("change", onChange);
  }
}

export function isReducedMotion() {
  return document.body.dataset.oMotion === "reduced";
}

export function setReducedMotion(value) {
  window.localStorage.setItem(STORAGE_KEY, String(Boolean(value)));
  applyMotionState(Boolean(value) || systemReduced);
}

function applyMotionState(reduced) {
  document.body.dataset.oMotion = reduced ? "reduced" : "full";
  document.dispatchEvent(new CustomEvent("orderly:motionchange", {
    detail: { reduced }
  }));
}

export function isTargetVisible(target) {
  if (!(target instanceof Element)) {
    return false;
  }

  const rect = target.getBoundingClientRect();
  return rect.bottom > 0
    && rect.right > 0
    && rect.top < window.innerHeight
    && rect.left < window.innerWidth;
}

export function shouldAnimate(key) {
  if (isReducedMotion()) {
    return false;
  }

  const now = Date.now();
  const previous = lastTriggered.get(key) ?? 0;
  lastTriggered.set(key, now);
  return now - previous >= RAPID_REPEAT_WINDOW;
}

export function highlightTarget(target) {
  if (!(target instanceof Element)) {
    return;
  }

  target.classList.remove("is-called");
  void target.offsetWidth;
  target.classList.add("is-called");
  window.setTimeout(() => {
    target.classList.remove("is-called");
  }, HIGHLIGHT_DURATION);
}
