const defaultSteps = [
  {
    id: "scan",
    title: "扫描 Downloads 目录",
    detail: "包含 842 个文件与子目录",
    tool: "list_items",
    risk: "read",
    selected: true,
  },
  {
    id: "metadata",
    title: "读取大小、时间与内容哈希",
    detail: "用于年份归档与重复项识别",
    tool: "read_meta",
    risk: "read",
    selected: true,
  },
  {
    id: "group",
    title: "按哈希分组重复文件",
    detail: "预计识别 13 组重复项",
    tool: "search_items",
    risk: "read",
    selected: true,
  },
  {
    id: "folders",
    title: "创建类型与年份目录",
    detail: "创建 10 个目录，保存变更前快照",
    tool: "create_folder",
    risk: "write",
    selected: true,
  },
  {
    id: "move",
    title: "批量移动 287 个文件",
    detail: "每步执行前记录原路径",
    tool: "move_item",
    risk: "write",
    selected: true,
  },
  {
    id: "dedupe",
    title: "将重复项移入 重复/",
    detail: "每组保留一个副本，文件进入回收站",
    tool: "delete_items",
    risk: "delete",
    selected: true,
  },
  {
    id: "todo",
    title: "提取截止日期并创建待办",
    detail: "根据文件内容生成 6 个可编辑事项",
    tool: "create_todo",
    risk: "write",
    selected: true,
  },
];

const state = {
  view: "workspace",
  steps: structuredClone(defaultSteps),
  goal: "",
  riskAuthorized: false,
  executionTimer: null,
  executionStatus: "idle",
  executionIndex: 0,
  eventNumber: 10482,
  redirectedAfterComplete: false,
  rolledBack: false,
  todoFilter: "all",
  aiUnread: 0,
  motionDemoOpen: false,
  taskLaunchTimer: null,
  toastTimer: null,
};

const riskLabels = {
  read: { label: "只读", className: "tag--green" },
  write: { label: "写入", className: "tag--amber" },
  delete: { label: "删除", className: "tag--red" },
};

const viewMeta = {
  workspace: ["工作流", "总览", "workspace"],
  plan: ["工作流", "计划预览", "workspace"],
  execution: ["工作流", "执行中", "workspace"],
  audit: ["审计记录", "执行详情", "audit"],
  files: ["文件与目录", "秋季学期", "files"],
  todos: ["待办", "接下来", "todos"],
  calendar: ["日程", "本周", "calendar"],
  trash: ["回收站", "个人工作区", "trash"],
  settings: ["授权与边界", "工具权限", "settings"],
};

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

const elements = {
  mainScroll: $("#mainScroll"),
  breadcrumbPrimary: $("#breadcrumbPrimary"),
  breadcrumbSecondary: $("#breadcrumbSecondary"),
  goalInput: $("#goalInput"),
  goalHint: $("#goalHint"),
  generatePlanButton: $("#generatePlanButton"),
  intentPreview: $("#intentPreview"),
  planTagRow: $("#planTagRow"),
  impactFiles: $("#impactFiles"),
  impactGroups: $("#impactGroups"),
  impactFolders: $("#impactFolders"),
  impactSpace: $("#impactSpace"),
  impactFilesLabel: $("#impactFilesLabel"),
  impactGroupsLabel: $("#impactGroupsLabel"),
  impactFoldersLabel: $("#impactFoldersLabel"),
  impactSpaceLabel: $("#impactSpaceLabel"),
  planStepList: $("#planStepList"),
  selectedStepCount: $("#selectedStepCount"),
  totalStepCount: $("#totalStepCount"),
  confirmPlanButton: $("#confirmPlanButton"),
  riskModal: $("#riskModal"),
  deleteConfirmInput: $("#deleteConfirmInput"),
  deleteConfirmError: $("#deleteConfirmError"),
  authorizeDeleteButton: $("#authorizeDeleteButton"),
  cancelRiskButton: $("#cancelRiskButton"),
  executionTitle: $("#executionTitle"),
  executionSubtitle: $("#executionSubtitle"),
  executionCurrent: $("#executionCurrent"),
  executionCounter: $("#executionCounter"),
  executionProgress: $("#executionProgress"),
  executionTimeline: $("#executionTimeline"),
  eventLog: $("#eventLog"),
  eventSequence: $("#eventSequence"),
  runningIcon: $("#runningIcon"),
  pauseButton: $("#pauseButton"),
  abortButton: $("#abortButton"),
  toast: $("#toast"),
  todoSummary: $("#todoSummary"),
  todoNavCount: $("#todoNavCount"),
  auditResultIcon: $("#auditResultIcon"),
  auditTitle: $("#auditTitle"),
  auditSubtitle: $("#auditSubtitle"),
  auditMovedLabel: $("#auditMovedLabel"),
  auditFoldersLabel: $("#auditFoldersLabel"),
  auditTrashLabel: $("#auditTrashLabel"),
  auditMoved: $("#auditMoved"),
  auditFolders: $("#auditFolders"),
  auditTrash: $("#auditTrash"),
  ledgerStatus: $("#ledgerStatus"),
  rollbackAllButton: $("#rollbackAllButton"),
  fullscreenButton: $("#fullscreenButton"),
  aiAssistant: $("#aiAssistant"),
  aiAssistantTrigger: $("#aiAssistantTrigger"),
  aiAssistantPanel: $("#aiAssistantPanel"),
  aiAssistantClose: $("#aiAssistantClose"),
  aiTranscript: $("#aiTranscript"),
  aiComposer: $("#aiComposer"),
  aiInput: $("#aiInput"),
  aiQuickbar: $("#aiQuickbar"),
  aiQuickInput: $("#aiQuickInput"),
  aiHistoryButton: $("#aiHistoryButton"),
  aiHistoryCount: $("#aiHistoryCount"),
  aiDockStatus: $("#aiDockStatus"),
  motionDemo: $("#motionDemo"),
  motionDemoTrigger: $("#motionDemoTrigger"),
  motionDemoPanel: $("#motionDemoPanel"),
  motionDemoClose: $("#motionDemoClose"),
  taskLaunch: $("#taskLaunch"),
  taskLaunchIcon: $("#taskLaunchIcon"),
  taskLaunchTitle: $("#taskLaunchTitle"),
  taskLaunchDetail: $("#taskLaunchDetail"),
};

function showToast(message) {
  window.clearTimeout(state.toastTimer);
  elements.toast.textContent = message;
  elements.toast.classList.add("is-visible");
  state.toastTimer = window.setTimeout(() => {
    elements.toast.classList.remove("is-visible");
  }, 2600);
}

function openAssistant(focusInput = true) {
  elements.aiAssistant.classList.add("is-open");
  elements.aiAssistantPanel.hidden = false;
  elements.aiAssistantTrigger.setAttribute("aria-expanded", "true");
  state.aiUnread = 0;
  updateAiHistoryCount();
  elements.aiTranscript.scrollTop = elements.aiTranscript.scrollHeight;
  if (focusInput) {
    window.setTimeout(() => elements.aiInput.focus(), 120);
  }
}

function closeAssistant() {
  elements.aiAssistant.classList.remove("is-open");
  elements.aiAssistantPanel.hidden = true;
  elements.aiAssistantTrigger.setAttribute("aria-expanded", "false");
}

function updateAiHistoryCount() {
  elements.aiHistoryCount.textContent = String(state.aiUnread);
  elements.aiHistoryCount.hidden = state.aiUnread === 0;
}

function addAiMessage(role, text) {
  const message = document.createElement("div");
  message.className = `ai-message ai-message--${role}`;
  const author = document.createElement("span");
  author.textContent = role === "user" ? "你" : "归序";
  const copy = document.createElement("p");
  copy.textContent = text;
  message.append(author, copy);
  elements.aiTranscript.append(message);
  elements.aiTranscript.scrollTop = elements.aiTranscript.scrollHeight;
  return message;
}

function assistantReply(prompt) {
  if (/删除|风险|永久/.test(prompt)) {
    return "删除步骤属于高风险操作。归序只会把文件移入回收站并保留原路径，还会在执行前再次要求你输入“删除”确认；整单回滚会逆序恢复所有变更。";
  }
  if (/撤销|回滚|恢复/.test(prompt)) {
    return "任务完成后，可以在“审计记录”中撤销单个步骤或整单回滚。回滚本身也会追加一条事件，原有审计链不会被删除。";
  }
  if (/修改|影响|文件/.test(prompt)) {
    return "当前计划会区分只读与写入步骤。只读操作自动放行；写入操作先保存快照；删除项只进入回收站。点击步骤右侧的编辑或移除按钮即可调整计划。";
  }
  return "我已经收到。当前是交互原型，我会结合当前任务上下文回答，但不会真正读取或修改文件。你可以问我计划步骤、风险范围或回滚方式。";
}

function sendAiMessage(rawText) {
  const text = rawText.trim();
  if (!text) return;
  addAiMessage("user", text);
  const pending = addAiMessage("assistant", "正在查看当前任务上下文…");
  pending.classList.add("is-pending");
  elements.aiDockStatus.textContent = "归序正在回答…";
  window.setTimeout(() => {
    pending.querySelector("p").textContent = assistantReply(text);
    pending.classList.remove("is-pending");
    elements.aiTranscript.scrollTop = elements.aiTranscript.scrollHeight;
    elements.aiDockStatus.textContent = "回答已就绪，可查看历史记录";
    if (elements.aiAssistantPanel.hidden) {
      state.aiUnread += 1;
      updateAiHistoryCount();
    }
  }, 520);
  elements.aiInput.value = "";
  elements.aiInput.style.height = "38px";
}

function toggleMotionDemo(force) {
  state.motionDemoOpen = typeof force === "boolean" ? force : !state.motionDemoOpen;
  elements.motionDemoPanel.hidden = !state.motionDemoOpen;
  elements.motionDemoTrigger.setAttribute("aria-expanded", String(state.motionDemoOpen));
}

function showTaskLaunch(title, detail, mode = "start") {
  window.clearTimeout(state.taskLaunchTimer);
  elements.taskLaunch.hidden = false;
  elements.taskLaunch.classList.toggle("is-complete", mode === "complete");
  elements.taskLaunchIcon.textContent = mode === "complete" ? "✓" : "✦";
  elements.taskLaunchTitle.textContent = title;
  elements.taskLaunchDetail.textContent = detail;
  state.taskLaunchTimer = window.setTimeout(() => {
    elements.taskLaunch.hidden = true;
  }, mode === "complete" ? 1100 : 1250);
}

function pulseDemoFeedback() {
  const shell = $(".app-shell");
  shell.classList.remove("is-demo-feedback");
  void shell.offsetWidth;
  shell.classList.add("is-demo-feedback");
  window.setTimeout(() => shell.classList.remove("is-demo-feedback"), 820);
}

function runDemoAction(action) {
  toggleMotionDemo(false);
  pulseDemoFeedback();

  if (action === "risk") {
    openRiskModal();
    return;
  }
  if (action === "execute") {
    startExecution();
    return;
  }
  if (action === "advance") {
    if (state.view !== "execution") {
      startExecution();
      return;
    }
    if (state.executionStatus === "paused") {
      togglePause();
    } else if (state.executionStatus === "running") {
      advanceExecution();
    }
    return;
  }
  if (action === "pause") {
    if (state.view !== "execution") {
      startExecution();
      window.setTimeout(() => {
        if (state.executionStatus === "running") togglePause();
      }, 700);
    } else if (state.executionStatus === "running") {
      togglePause();
    }
    return;
  }
  if (action === "complete") {
    stopExecution();
    state.executionIndex = selectedSteps().length;
    state.executionStatus = "complete";
    showAuditCompleted();
    setView("audit");
    showTaskLaunch("任务执行完成", "结果已保存，可进入审计记录检查或回滚", "complete");
    return;
  }
  if (action === "rollback") {
    stopExecution();
    state.executionStatus = "complete";
    showAuditCompleted();
    setView("audit");
    window.setTimeout(() => {
      rollbackAll();
      showTaskLaunch("任务已回滚", "所有变更已按事件逆序恢复", "complete");
    }, 420);
  }
}

function resizeAiInput() {
  elements.aiInput.style.height = "auto";
  elements.aiInput.style.height = `${Math.min(Math.max(elements.aiInput.scrollHeight, 38), 110)}px`;
}

function resizeGoalInput() {
  elements.goalInput.style.height = "auto";
  elements.goalInput.style.height = `${Math.min(Math.max(elements.goalInput.scrollHeight, 88), 148)}px`;
}

function setView(viewName) {
  if (!viewMeta[viewName]) return;
  state.view = viewName;

  $$(".view").forEach((view) => {
    view.classList.toggle("is-active", view.id === `view-${viewName}`);
  });

  const activeNav = viewMeta[viewName][2];
  $$(".nav-item").forEach((item) => {
    item.classList.toggle("is-active", item.dataset.view === activeNav);
  });

  elements.breadcrumbPrimary.textContent = viewMeta[viewName][0];
  elements.breadcrumbSecondary.textContent = viewMeta[viewName][1];
  elements.mainScroll.scrollTop = 0;
}

function exampleSteps(kind) {
  const base = structuredClone(defaultSteps);
  if (kind === "dedupe") {
    return base.map((step) => ({
      ...step,
      selected: ["scan", "metadata", "group", "dedupe"].includes(step.id),
      title:
        step.id === "dedupe"
          ? "删除 60 个重复文件"
          : step.id === "group"
            ? "按哈希分组 60 个重复文件"
            : step.title,
      risk: step.id === "dedupe" ? "delete" : step.risk,
    }));
  }
  if (kind === "schedule") {
    return base.map((step) => ({
      ...step,
      selected: ["scan", "metadata", "todo"].includes(step.id),
      title:
        step.id === "todo"
          ? "从课程资料提取 6 个未完成事项"
          : step.id === "scan"
            ? "扫描课程资料目录"
            : step.title,
    }));
  }
  return base;
}

function openPlan(goal, kind = "archive") {
  const finalGoal = goal.trim() || elements.goalInput.value.trim();
  if (!finalGoal) {
    elements.goalHint.textContent = "请先描述你希望完成的任务，再生成执行计划。";
    elements.goalHint.classList.add("is-error");
    elements.goalInput.focus();
    return;
  }

  stopExecution();
  state.goal = finalGoal;
  state.steps = exampleSteps(kind);
  state.riskAuthorized = false;
  state.executionStatus = "idle";
  state.executionIndex = 0;
  state.redirectedAfterComplete = false;
  const intentCopy = {
    archive:
      "将下载文件夹按文件类型和修改年份归档；重复文件集中移动到独立文件夹，保留每组中的一个副本。",
    dedupe:
      "检查重复文件并保留每组中修改时间最新的一个；其余副本移入归序回收站，保留原始路径。",
    schedule:
      "从指定课程资料中提取未完成事项，并结合已有课程与空闲时间生成下一周安排。",
  };
  const planTags = {
    archive: ["范围：Downloads", "包含子目录", "写入需确认", "删除需二次授权"],
    dedupe: ["范围：重复文件", "按内容哈希", "删除需二次授权", "可整单回滚"],
    schedule: ["范围：课程资料", "输出：待办", "写入需确认", "可撤销"],
  };
  const impactValues = {
    archive: ["842", "13", "10", "1.2 GB"],
    dedupe: ["60", "60", "0", "1.2 GB"],
    schedule: ["42", "0", "0", "6 项"],
  };
  const impactLabels = {
    archive: ["扫描文件", "重复文件组", "新建目录", "可释放空间"],
    dedupe: ["重复文件", "去重组数", "物理删除", "可释放空间"],
    schedule: ["扫描文件", "重复文件", "新建目录", "生成待办"],
  };
  elements.goalHint.textContent =
    "AI 将先检索目录、读取元信息，再生成计划。未确认前不会改动任何文件。";
  elements.goalHint.classList.remove("is-error");
  elements.intentPreview.textContent = intentCopy[kind] || intentCopy.archive;
  elements.planTagRow.replaceChildren(
    ...(planTags[kind] || planTags.archive).map((label) => {
      const tag = document.createElement("span");
      tag.className = "tag";
      tag.textContent = label;
      return tag;
    }),
  );
  const impacts = impactValues[kind] || impactValues.archive;
  elements.impactFiles.textContent = impacts[0];
  elements.impactGroups.textContent = impacts[1];
  elements.impactFolders.textContent = impacts[2];
  elements.impactSpace.textContent = impacts[3];
  const labels = impactLabels[kind] || impactLabels.archive;
  elements.impactFilesLabel.textContent = labels[0];
  elements.impactGroupsLabel.textContent = labels[1];
  elements.impactFoldersLabel.textContent = labels[2];
  elements.impactSpaceLabel.textContent = labels[3];
  renderPlanSteps();
  setView("plan");
}

function selectedSteps() {
  return state.steps.filter((step) => step.selected);
}

function renderPlanSteps() {
  const selected = selectedSteps().length;
  elements.selectedStepCount.textContent = String(selected);
  elements.totalStepCount.textContent = String(state.steps.length);
  elements.confirmPlanButton.disabled = selected === 0;

  elements.planStepList.replaceChildren(
    ...state.steps.map((step, index) => {
      const row = document.createElement("div");
      row.className = `step-row${step.selected ? "" : " is-excluded"}`;
      row.dataset.stepId = step.id;

      const indexButton = document.createElement("button");
      indexButton.type = "button";
      indexButton.className = "step-index";
      indexButton.dataset.stepToggle = step.id;
      indexButton.textContent = step.selected
        ? String(index + 1).padStart(2, "0")
        : "＋";
      indexButton.setAttribute(
        "aria-label",
        step.selected ? `移除步骤：${step.title}` : `恢复步骤：${step.title}`,
      );

      const main = document.createElement("div");
      main.className = "step-main";
      const title = document.createElement("strong");
      title.textContent = step.title;
      const detail = document.createElement("span");
      detail.textContent = step.detail;
      main.append(title, detail);

      const tool = document.createElement("span");
      tool.className = "step-tool";
      tool.textContent = step.tool;

      const risk = riskLabels[step.risk];
      const riskCell = document.createElement("span");
      const riskTag = document.createElement("span");
      riskTag.className = `tag ${risk.className}`;
      riskTag.textContent = risk.label;
      riskCell.append(riskTag);

      const actions = document.createElement("div");
      actions.className = "step-edit";

      const editButton = document.createElement("button");
      editButton.type = "button";
      editButton.className = "mini-action";
      editButton.dataset.stepEdit = step.id;
      editButton.textContent = "✎";
      editButton.setAttribute("aria-label", `编辑步骤：${step.title}`);

      const removeButton = document.createElement("button");
      removeButton.type = "button";
      removeButton.className = "mini-action";
      removeButton.dataset.stepToggle = step.id;
      removeButton.textContent = step.selected ? "−" : "↺";
      removeButton.setAttribute(
        "aria-label",
        step.selected ? `移除步骤：${step.title}` : `恢复步骤：${step.title}`,
      );

      actions.append(editButton, removeButton);
      row.append(indexButton, main, tool, riskCell, actions);
      return row;
    }),
  );
}

function beginStepEdit(stepId) {
  const row = $(`[data-step-id="${stepId}"]`, elements.planStepList);
  const step = state.steps.find((item) => item.id === stepId);
  if (!row || !step) return;

  const main = $(".step-main", row);
  main.replaceChildren();
  const input = document.createElement("input");
  input.value = step.title;
  input.setAttribute("aria-label", `修改步骤名称：${step.title}`);
  const detail = document.createElement("span");
  detail.textContent = "按 Enter 保存，按 Esc 取消";
  main.append(input, detail);
  input.focus();
  input.select();

  const save = () => {
    const nextTitle = input.value.trim();
    if (nextTitle) {
      step.title = nextTitle;
      showToast("步骤名称已更新，计划尚未执行。");
    }
    renderPlanSteps();
  };

  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter") save();
    if (event.key === "Escape") renderPlanSteps();
  });
  input.addEventListener("blur", save, { once: true });
}

function openRiskModal() {
  elements.deleteConfirmInput.value = "";
  elements.deleteConfirmError.textContent = "";
  elements.authorizeDeleteButton.disabled = true;
  elements.riskModal.hidden = false;
  window.setTimeout(() => elements.deleteConfirmInput.focus(), 30);
}

function closeRiskModal() {
  elements.riskModal.hidden = true;
}

function addLog(message) {
  state.eventNumber += 1;
  elements.eventSequence.textContent = `事件 #${state.eventNumber}`;
  const row = document.createElement("p");
  row.className = "is-done";
  const time = new Date().toLocaleTimeString("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
  const timeNode = document.createElement("span");
  timeNode.textContent = time;
  const marker = document.createElement("i");
  const messageNode = document.createElement("b");
  messageNode.textContent = message;
  row.append(timeNode, marker, messageNode);
  elements.eventLog.prepend(row);
}

function renderExecutionTimeline() {
  if (!state.steps.length) return;
  const activeSteps = selectedSteps();

  elements.executionTimeline.replaceChildren(
    ...activeSteps.map((step, index) => {
      const row = document.createElement("div");
      const done = index < state.executionIndex;
      const running =
        index === state.executionIndex &&
        !["complete", "aborted"].includes(state.executionStatus);
      row.className = `timeline-item${done ? " is-done" : ""}${running ? " is-running" : ""}`;

      const stateBadge = document.createElement("span");
      stateBadge.className = "timeline-state";
      stateBadge.textContent = done
        ? "✓"
        : running
          ? String(index + 1).padStart(2, "0")
          : String(index + 1).padStart(2, "0");

      const copy = document.createElement("div");
      copy.className = "timeline-copy";
      const title = document.createElement("strong");
      title.textContent = step.title;
      const detail = document.createElement("span");
      detail.textContent = done
        ? `${step.detail} · 输入输出摘要已记录`
        : running
          ? "正在执行，当前操作已进入流式状态推送"
          : "等待前序步骤完成";
      copy.append(title, detail);

      const risk = document.createElement("span");
      risk.className = "timeline-risk";
      risk.innerHTML = `<span class="tag ${riskLabels[step.risk].className}">${riskLabels[step.risk].label}</span>`;

      const time = document.createElement("span");
      time.className = "timeline-time";
      time.innerHTML = done
        ? `<strong>${String(index * 7 + 3).padStart(2, "0")}s</strong>成功`
        : running
          ? `<strong>00:${String(state.executionIndex * 7 + 3).padStart(2, "0")}</strong>执行中`
          : "<strong>--:--</strong>等待";

      row.append(stateBadge, copy, risk, time);
      return row;
    }),
  );

  const total = activeSteps.length;
  const completed = Math.min(state.executionIndex, total);
  const progress = total ? Math.round((completed / total) * 100) : 0;
  elements.executionCounter.textContent = `${completed} / ${total}`;
  elements.executionProgress.style.width = `${progress}%`;
  elements.executionCurrent.textContent =
    completed >= total
      ? "全部步骤完成"
      : activeSteps[completed]?.title || "准备执行";
}

function resetExecutionUi() {
  window.clearInterval(state.executionTimer);
  state.executionTimer = null;
  state.executionIndex = 0;
  state.executionStatus = "running";
  state.redirectedAfterComplete = false;
  elements.executionTitle.textContent = "正在执行已确认的计划";
  elements.executionSubtitle.textContent =
    "只读步骤并行，写入步骤串行；每一步都写入事件日志";
  elements.runningIcon.textContent = "▶";
  elements.pauseButton.textContent = "暂停";
  elements.pauseButton.disabled = false;
  elements.abortButton.disabled = false;
  elements.runningIcon.classList.add("is-pulsing");
  elements.eventLog.replaceChildren();
  elements.eventSequence.textContent = `事件 #${state.eventNumber}`;
  renderExecutionTimeline();
}

function advanceExecution() {
  const activeSteps = selectedSteps();
  if (state.executionStatus !== "running") return;

  if (state.executionIndex >= activeSteps.length) {
    finishExecution();
    return;
  }

  const step = activeSteps[state.executionIndex];
  addLog(`步骤「${step.title}」已完成，状态与快照已写入事件流`);
  state.executionIndex += 1;
  renderExecutionTimeline();

  if (state.executionIndex >= activeSteps.length) {
    finishExecution();
  }
}

function startExecution() {
  closeRiskModal();
  setView("execution");
  resetExecutionUi();
  addLog("任务已启动，已读取已确认计划");
  showTaskLaunch("任务已启动", "AI 正在进入受控执行流程，所有步骤将依次记录", "start");
  state.executionTimer = window.setInterval(advanceExecution, 850);
}

function finishExecution() {
  window.clearInterval(state.executionTimer);
  state.executionTimer = null;
  state.executionStatus = "complete";
  elements.runningIcon.textContent = "✓";
  elements.executionTitle.textContent = "任务执行完成";
  elements.executionSubtitle.textContent =
    "所有步骤已完成，事件链与执行前快照均已保存";
  elements.executionCurrent.textContent = "全部步骤完成";
  elements.pauseButton.textContent = "查看审计";
  elements.pauseButton.disabled = false;
  elements.abortButton.disabled = true;
  elements.runningIcon.classList.remove("is-pulsing");
  renderExecutionTimeline();
  addLog("任务完成，可查看审计记录或执行回滚");
  showTaskLaunch("任务执行完成", "全部步骤已结束，结果与快照均可检查", "complete");

  window.setTimeout(() => {
    if (state.view === "execution" && state.executionStatus === "complete") {
      showAuditCompleted();
      setView("audit");
    }
  }, 1200);
}

function stopExecution() {
  window.clearInterval(state.executionTimer);
  state.executionTimer = null;
}

function togglePause() {
  if (state.executionStatus === "complete") {
    showAuditCompleted();
    setView("audit");
    return;
  }
  if (state.executionStatus === "aborted") {
    setView("plan");
    return;
  }
  if (state.executionStatus === "running") {
    stopExecution();
    state.executionStatus = "paused";
    elements.pauseButton.textContent = "继续";
    elements.runningIcon.textContent = "Ⅱ";
    elements.runningIcon.classList.remove("is-pulsing");
    elements.executionTitle.textContent = "任务已暂停";
    elements.executionSubtitle.textContent = "当前进度已保留，未执行步骤不会继续";
    addLog("用户暂停任务，当前进度已保留");
    renderExecutionTimeline();
    return;
  }
  if (state.executionStatus === "paused") {
    state.executionStatus = "running";
    elements.pauseButton.textContent = "暂停";
    elements.runningIcon.textContent = "▶";
    elements.runningIcon.classList.add("is-pulsing");
    elements.executionTitle.textContent = "正在执行已确认的计划";
    elements.executionSubtitle.textContent =
      "只读步骤并行，写入步骤串行；每一步都写入事件日志";
    addLog("用户继续任务，从当前步骤恢复执行");
    renderExecutionTimeline();
    state.executionTimer = window.setInterval(advanceExecution, 850);
  }
}

function abortExecution() {
  if (state.executionStatus === "complete") return;
  stopExecution();
  state.executionStatus = "aborted";
  elements.runningIcon.textContent = "■";
  elements.runningIcon.classList.remove("is-pulsing");
  elements.executionTitle.textContent = "任务已中止";
  elements.executionSubtitle.textContent =
    "已完成步骤保留并支持撤销；未执行步骤已取消";
  elements.pauseButton.textContent = "返回计划";
  elements.pauseButton.disabled = false;
  elements.abortButton.disabled = true;
  addLog("用户中止任务，未执行步骤已取消");
  renderExecutionTimeline();
  showTaskLaunch("任务已中止", "未执行步骤已经停止，已完成进度仍可检查", "complete");
  showToast("任务已中止，已完成步骤仍可在审计记录中恢复。");
}

function showAuditCompleted() {
  state.rolledBack = false;
  const selectedIds = new Set(selectedSteps().map((step) => step.id));
  const banner = $(".result-banner");
  banner.classList.remove("is-rolled-back");
  elements.auditResultIcon.textContent = "✓";
  elements.auditTitle.textContent = "任务已完成，任何一步都能倒车";
  elements.auditSubtitle.textContent =
    "本次任务已完成 · 写入前快照已保存 · 审计链完整";
  elements.ledgerStatus.textContent = "已校验";
  elements.ledgerStatus.className = "tag tag--green";
  elements.rollbackAllButton.disabled = false;
  if (selectedIds.has("move")) {
    elements.auditMovedLabel.textContent = "移动文件";
    elements.auditMoved.textContent = "287";
  } else {
    elements.auditMovedLabel.textContent = "扫描文件";
    elements.auditMoved.textContent = "42";
  }
  if (selectedIds.has("folders")) {
    elements.auditFoldersLabel.textContent = "新建目录";
    elements.auditFolders.textContent = "10";
  } else if (selectedIds.has("group")) {
    elements.auditFoldersLabel.textContent = "去重组数";
    elements.auditFolders.textContent = "60";
  } else {
    elements.auditFoldersLabel.textContent = "执行步骤";
    elements.auditFolders.textContent = String(selectedSteps().length);
  }
  if (selectedIds.has("dedupe")) {
    elements.auditTrashLabel.textContent = "回收站";
    elements.auditTrash.textContent = "13";
  } else if (selectedIds.has("todo")) {
    elements.auditTrashLabel.textContent = "生成待办";
    elements.auditTrash.textContent = "6";
  } else {
    elements.auditTrashLabel.textContent = "回滚快照";
    elements.auditTrash.textContent = String(selectedSteps().length);
  }
  $$(".event-row:not(.event-row--head)", $("#eventTable")).forEach((row) => {
    row.classList.remove("is-reverted");
    const badge = $(".tag", row);
    if (badge) {
      badge.textContent = "成功";
      badge.className = "tag tag--green";
    }
    const button = $(".mini-action", row);
    if (button) {
      button.disabled = false;
      button.textContent = "↺";
      button.classList.remove("is-restored");
    }
  });
}

function rollbackAll() {
  if (state.rolledBack) return;
  state.rolledBack = true;
  const banner = $(".result-banner");
  banner.classList.add("is-rolled-back");
  elements.auditResultIcon.textContent = "↺";
  elements.auditTitle.textContent = "任务已回滚，审计链仍被保留";
  elements.auditSubtitle.textContent =
    "事件已按逆序回放 · 原路径已恢复 · 新增目录已移除";
  elements.ledgerStatus.textContent = "已回滚";
  elements.ledgerStatus.className = "tag tag--amber";
  elements.rollbackAllButton.disabled = true;
  elements.auditMoved.textContent = "0";
  elements.auditFolders.textContent = "0";
  elements.auditTrash.textContent = "0";
  elements.eventSequence.textContent = `事件 #${state.eventNumber + 9}`;

  $$(".event-row:not(.event-row--head)", $("#eventTable")).forEach((row) => {
    row.classList.add("is-reverted");
    const badge = $(".tag", row);
    if (badge) {
      badge.textContent = "已回滚";
      badge.className = "tag tag--amber";
    }
    const button = $(".mini-action", row);
    if (button) {
      button.disabled = true;
      button.textContent = "✓";
      button.classList.add("is-restored");
    }
  });
  showToast("整单回滚完成。回滚事件已继续写入审计链。");
}

function restoreEvent(button) {
  const row = button.closest(".event-row");
  if (!row) return;
  row.classList.add("is-reverted");
  button.disabled = true;
  button.textContent = "✓";
  button.classList.add("is-restored");
  const badge = $(".tag", row);
  if (badge) {
    badge.textContent = "已恢复";
    badge.className = "tag tag--amber";
  }
  showToast(`${button.dataset.restoreEvent} 已恢复，审计链仍保留`);
}

function updateTodoSummary() {
  const checkboxes = $$("#todoList input[type='checkbox']");
  const remaining = checkboxes.filter((checkbox) => !checkbox.checked).length;
  elements.todoSummary.textContent = `${remaining} 项待完成`;
  elements.todoNavCount.textContent = String(remaining);
}

function applyTodoFilter(filter) {
  state.todoFilter = filter;
  $$(".todo-filter").forEach((button) => {
    const isActive = button.dataset.todoFilter === filter;
    button.classList.toggle("is-active", isActive);
    button.setAttribute("aria-selected", String(isActive));
  });
  $$("#todoList .todo-row").forEach((row) => {
    row.hidden = filter !== "all" && row.dataset.todoPeriod !== filter;
  });
}

function addTodo() {
  const row = document.createElement("label");
  row.className = "todo-row";
  row.dataset.todoPeriod = "today";
  const checkbox = document.createElement("input");
  checkbox.type = "checkbox";
  const visualCheckbox = document.createElement("span");
  visualCheckbox.className = "checkbox";
  const main = document.createElement("span");
  main.className = "todo-main";
  const title = document.createElement("strong");
  title.textContent = "新待办事项";
  const meta = document.createElement("span");
  meta.className = "todo-meta";
  const category = document.createElement("span");
  category.textContent = "交互演示";
  const date = document.createElement("span");
  date.textContent = "今天";
  meta.append(category, date);
  main.append(title, meta);
  const priority = document.createElement("span");
  priority.className = "priority priority--medium";
  priority.textContent = "中优先级";
  row.append(checkbox, visualCheckbox, main, priority);
  $("#todoList").append(row);
  applyTodoFilter(state.todoFilter);
  updateTodoSummary();
  showToast("已添加一条演示待办。");
}

function handleGlobalClick(event) {
  const demoAction = event.target.closest("[data-demo-action]");
  if (demoAction) {
    runDemoAction(demoAction.dataset.demoAction);
    return;
  }

  const viewButton = event.target.closest("[data-view]");
  if (viewButton) {
    setView(viewButton.dataset.view);
    return;
  }

  const example = event.target.closest("[data-example]");
  if (example) {
    elements.goalInput.value = example.dataset.example;
    const exampleKind = example.dataset.example.includes("重复文件")
      ? "dedupe"
      : example.dataset.example.includes("提取") || example.dataset.example.includes("安排")
        ? "schedule"
        : "archive";
    openPlan(example.dataset.example, exampleKind);
    return;
  }

  const recent = event.target.closest("[data-open-plan]");
  if (recent) {
    const kind = recent.dataset.openPlan;
    const goals = {
      archive: "把下载文件夹整理好，按类型和年份归档，重复文件单独放到一个文件夹里。",
      dedupe: "把这 60 个重复文件删掉，每组只留最新的一个。",
      schedule: "从课程资料中提取未完成的作业，并安排到下一周。",
    };
    elements.goalInput.value = goals[kind];
    openPlan(goals[kind], kind);
    return;
  }

  if (event.target.closest("[data-jump-audit]")) {
    showAuditCompleted();
    setView("audit");
    return;
  }

  const stepToggle = event.target.closest("[data-step-toggle]");
  if (stepToggle) {
    const step = state.steps.find((item) => item.id === stepToggle.dataset.stepToggle);
    if (step) {
      step.selected = !step.selected;
      renderPlanSteps();
    }
    return;
  }

  const stepEdit = event.target.closest("[data-step-edit]");
  if (stepEdit) {
    beginStepEdit(stepEdit.dataset.stepEdit);
    return;
  }

  const restore = event.target.closest("[data-restore-event]");
  if (restore) {
    restoreEvent(restore);
    return;
  }

  const restoreFile = event.target.closest("[data-restore-file]");
  if (restoreFile) {
    const row = restoreFile.closest(".file-row");
    row.style.opacity = "0.45";
    restoreFile.disabled = true;
    restoreFile.textContent = "已恢复";
    showToast("演示文件已恢复到原始路径。");
    return;
  }

  const eventChip = event.target.closest("[data-event]");
  if (eventChip) {
    eventChip.classList.toggle("is-selected");
    showToast(eventChip.classList.contains("is-selected") ? "日程已选中。" : "已取消选择日程。");
    return;
  }

  const streamTab = event.target.closest(".stream-tab");
  if (streamTab) {
    $$(".stream-tab").forEach((tab) => {
      const isActive = tab === streamTab;
      tab.classList.toggle("is-active", isActive);
      tab.setAttribute("aria-selected", String(isActive));
    });
    const currentText = $(".event-log b");
    if (currentText) {
      const labels = {
        "事件": "事件流按时间顺序显示",
        "输入 / 输出": "当前视图将展示每一步的输入与输出摘要",
        "错误": "当前没有错误事件",
      };
      currentText.textContent = labels[streamTab.textContent.trim()] || "事件流已更新";
    }
    return;
  }

  const todoFilter = event.target.closest("[data-todo-filter]");
  if (todoFilter) {
    applyTodoFilter(todoFilter.dataset.todoFilter);
    return;
  }

  const suggestion = event.target.closest("[data-ai-prompt]");
  if (suggestion) {
    sendAiMessage(suggestion.dataset.aiPrompt);
    return;
  }

  const action = event.target.closest("[data-action]")?.dataset.action;
  if (action === "search") {
    showToast("搜索入口为交互占位，原型不读取真实文件索引。");
    return;
  }
  if (action === "notifications") {
    showToast("暂无新的系统通知。");
  }
}

function bindEvents() {
  document.addEventListener("click", handleGlobalClick);

  elements.generatePlanButton.addEventListener("click", () => openPlan(elements.goalInput.value));
  elements.goalInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      openPlan(elements.goalInput.value);
    }
  });
  elements.goalInput.addEventListener("input", () => {
    resizeGoalInput();
    if (elements.goalInput.value.trim()) {
      elements.goalHint.textContent =
        "AI 将先检索目录、读取元信息，再生成计划。未确认前不会改动任何文件。";
      elements.goalHint.classList.remove("is-error");
    }
  });

  $("#regeneratePlan").addEventListener("click", () => {
    state.steps = structuredClone(defaultSteps);
    state.riskAuthorized = false;
    renderPlanSteps();
    showToast("已重新生成计划，仍然不会改动任何文件。");
  });

  $("#saveDraft").addEventListener("click", () => {
    showToast("计划草稿已保存在当前交互会话中。");
  });

  $("#addStepButton").addEventListener("click", () => {
    const step = {
      id: `custom-${Date.now()}`,
      title: "自定义执行步骤",
      detail: "点击右侧编辑按钮修改名称",
      tool: "custom_tool",
      risk: "write",
      selected: true,
    };
    state.steps.push(step);
    renderPlanSteps();
    beginStepEdit(step.id);
  });

  elements.confirmPlanButton.addEventListener("click", () => {
    const hasDelete = selectedSteps().some((step) => step.risk === "delete");
    if (hasDelete && !state.riskAuthorized) {
      openRiskModal();
      return;
    }
    startExecution();
  });

  elements.cancelRiskButton.addEventListener("click", () => {
    closeRiskModal();
    showToast("已取消删除授权，尚未执行任何文件操作。");
  });

  elements.deleteConfirmInput.addEventListener("input", () => {
    const isExact = elements.deleteConfirmInput.value.trim() === "删除";
    elements.authorizeDeleteButton.disabled = !isExact;
    elements.deleteConfirmError.textContent =
      elements.deleteConfirmInput.value && !isExact ? "确认词不正确，请输入“删除”。" : "";
  });

  elements.deleteConfirmInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !elements.authorizeDeleteButton.disabled) {
      state.riskAuthorized = true;
      startExecution();
    }
  });

  elements.authorizeDeleteButton.addEventListener("click", () => {
    state.riskAuthorized = true;
    startExecution();
  });

  elements.pauseButton.addEventListener("click", togglePause);
  elements.abortButton.addEventListener("click", abortExecution);

  elements.rollbackAllButton.addEventListener("click", rollbackAll);
  $$("[data-rollback-mode]").forEach((button) => {
    button.addEventListener("click", () => {
      $$("[data-rollback-mode]").forEach((item) => item.classList.toggle("is-selected", item === button));
      if (button.dataset.rollbackMode === "all") {
        rollbackAll();
      } else {
        showToast("请选择事件表中的 ↺ 按钮来恢复单个步骤。");
      }
    });
  });

  $("#todoList").addEventListener("change", updateTodoSummary);
  $("#addTodoButton").addEventListener("click", addTodo);

  $("#emptyTrashButton").addEventListener("click", () => {
    const rows = $$("#trashList .file-row:not(.file-row--head)");
    rows.forEach((row) => {
      row.style.opacity = "0.35";
      const button = $("button", row);
      if (button) {
        button.disabled = true;
        button.textContent = "已清理";
      }
    });
    showToast("演示回收站记录已清空，真实文件不会受到影响。");
  });

  $$(".switch input").forEach((input) => {
    input.addEventListener("change", () => {
      const label = input.closest(".permission-row")?.querySelector("strong")?.textContent || "工具权限";
      showToast(`${label}：${input.checked ? "已启用" : "已暂停"}（仅原型设置）`);
    });
  });

  elements.fullscreenButton.addEventListener("click", async () => {
    try {
      if (!document.fullscreenElement) {
        await document.documentElement.requestFullscreen();
        showToast("已进入全屏模式，获得更大的工作区。");
      } else {
        await document.exitFullscreen();
      }
    } catch {
      showToast("当前浏览器未允许全屏，可继续使用响应式放大布局。");
    }
  });
  document.addEventListener("fullscreenchange", () => {
    elements.fullscreenButton.setAttribute(
      "aria-label",
      document.fullscreenElement ? "退出全屏显示" : "全屏显示界面",
    );
  });

  elements.aiAssistantTrigger.addEventListener("click", () => openAssistant());
  elements.aiAssistantClose.addEventListener("click", closeAssistant);
  elements.aiHistoryButton.addEventListener("click", () => {
    toggleMotionDemo(false);
    if (elements.aiAssistantPanel.hidden) {
      openAssistant(false);
    } else {
      closeAssistant();
    }
  });
  elements.aiQuickbar.addEventListener("submit", (event) => {
    event.preventDefault();
    if (!elements.aiQuickInput.value.trim()) {
      elements.aiQuickInput.focus();
      return;
    }
    sendAiMessage(elements.aiQuickInput.value);
    elements.aiQuickInput.value = "";
  });
  elements.aiComposer.addEventListener("submit", (event) => {
    event.preventDefault();
    sendAiMessage(elements.aiInput.value);
  });
  elements.aiInput.addEventListener("input", resizeAiInput);
  elements.aiInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      sendAiMessage(elements.aiInput.value);
    }
  });
  document.addEventListener("keydown", (event) => {
    if ((event.ctrlKey || event.metaKey) && event.key === "/") {
      event.preventDefault();
      elements.aiQuickInput.focus();
    }
    if (event.key === "Escape" && !elements.aiAssistantPanel.hidden) {
      closeAssistant();
    }
  });

  elements.motionDemoTrigger.addEventListener("click", () => {
    if (elements.aiAssistantPanel.hidden === false) closeAssistant();
    toggleMotionDemo();
  });
  elements.motionDemoClose.addEventListener("click", () => toggleMotionDemo(false));
}

renderPlanSteps();
updateTodoSummary();
applyTodoFilter("all");
bindEvents();
resizeGoalInput();
