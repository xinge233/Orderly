import {
  highlightTarget,
  isTargetVisible,
  shouldAnimate
} from "./motion.js";
import { getFirstVisibleButton } from "./registry.js";

const FLIGHT_DURATION = 620;

export function initDock() {
  const callButton = document.querySelector("#demo-call");
  const input = document.querySelector("#dock-input");
  const dock = document.querySelector("#ai-dock");

  const trigger = () => {
    const target = getFirstVisibleButton();
    if (!target) {
      return;
    }

    if (!isTargetVisible(target)) {
      target.scrollIntoView({ block: "nearest" });
      highlightTarget(target);
      return;
    }

    if (!shouldAnimate(target.dataset.moduleId)) {
      highlightTarget(target);
      return;
    }

    animateDot(target, dock.querySelector(".o-dock__bubble"));
  };

  callButton.addEventListener("click", trigger);
  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      event.preventDefault();
      trigger();
    }
  });
}

function animateDot(target, startElement) {
  const start = startElement.getBoundingClientRect();
  const end = target.getBoundingClientRect();
  const layer = document.querySelector("#motion-layer");
  const dot = document.createElement("span");
  dot.className = "o-flight-dot";
  layer.append(dot);

  const startX = start.left + start.width / 2;
  const startY = start.top + start.height / 2;
  const endX = end.left + end.width / 2;
  const endY = end.top + end.height / 2;
  const midX = (startX + endX) / 2;
  const midY = Math.min(startY, endY) - 90;

  const animation = dot.animate([
    { transform: `translate3d(${startX}px, ${startY}px, 0) scale(0.65)` },
    { transform: `translate3d(${midX}px, ${midY}px, 0) scale(1)` },
    { transform: `translate3d(${endX}px, ${endY}px, 0) scale(0.55)` }
  ], {
    duration: FLIGHT_DURATION,
    easing: "cubic-bezier(0.22, 0.74, 0.18, 1)",
    fill: "forwards"
  });

  animation.finished
    .catch(() => {})
    .finally(() => {
      dot.remove();
      highlightTarget(target);
    });
}
