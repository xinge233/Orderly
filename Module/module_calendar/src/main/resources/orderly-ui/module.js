/**
 * 日历工作区视图。
 *
 * 职责：展示模块自有日程、日历待办和自定义空间，并把用户明确发起的变更交给宿主执行。
 * 依赖：orderly-ui/module.json 提供显示与轮播设置；
 * 查询依赖 calendar.list、calendar.todo.list、calendar.space.list；
 * 宿主 mount context 提供 settings、element、icon、query、executeAction、moduleDataUrl、toast 等能力。
 * 隔离：查询或动作失败只降级当前日历视图；订阅宿主终态事件合并刷新，视图移除时停止
 * 定时器、取消订阅并断开生命周期观察器。
 * 天气缓存和顶部文本仅是当前视图的本地辅助 UI 状态，不写入模块业务集合。
 */

const WEATHER_CACHE_KEY = "orderly.calendar.weather.v1";
const FOCUS_TITLE_KEY = "orderly.calendar.focus-title.v1";
const WEATHER_TTL = 15 * 60 * 1000;
const MANUAL_SPACE_PAUSE = 60 * 1000;
const CALENDAR_EVENT_REFRESH_DELAY = 180;

/**
 * 判断宿主执行事件是否可能改变日程模块数据。
 *
 * @param {object} event 宿主执行事件
 * @param {string} moduleId 当前模块 ID
 * @returns {boolean} 需要重新读取权威数据时返回 true
 */
export function calendarDataEvent(event, moduleId) {
    if (!event || event.moduleId !== moduleId) {
        return false;
    }
    const type = String(event.type ?? "").toUpperCase();
    const status = String(event.status ?? "").toUpperCase();
    if (type === "STEP") {
        return status === "COMPLETED" || status === "FAILED";
    }
    if (type === "ROLLBACK_STEP") {
        return status === "ROLLED_BACK" || status === "FAILED";
    }
    return false;
}

const WEATHER_LABELS = new Map([
    [0, "晴朗"],
    [1, "大部晴朗"],
    [2, "多云"],
    [3, "阴天"],
    [45, "有雾"],
    [48, "雾凇"],
    [51, "小毛毛雨"],
    [53, "毛毛雨"],
    [55, "强毛毛雨"],
    [61, "小雨"],
    [63, "中雨"],
    [65, "大雨"],
    [71, "小雪"],
    [73, "中雪"],
    [75, "大雪"],
    [80, "阵雨"],
    [81, "较强阵雨"],
    [82, "强阵雨"],
    [95, "雷雨"],
    [96, "雷雨伴冰雹"],
    [99, "强雷雨伴冰雹"]
]);

/**
 * 挂载日历工作区。
 *
 * @param {HTMLElement} root 框架分配的模块视图根容器；脚本只替换其子节点。
 * @param {object} context 宿主 mount context，所有模块数据访问和动作执行都由此注入。
 * @returns {Promise<void>} 首次查询与局部渲染完成后结束，后续交互由监听器处理。
 */
export default async function mount(root, context) {
    const make = context.element;
    const settings = context.settings ?? {};
    const hourFormat = settings.hourFormat === "12" ? "12" : "24";
    const showEndTime = settings.showEndTime !== false;
    const rotationEnabled = settings.spaceRotationEnabled !== false;
    const rotationSeconds = clamp(Number(settings.spaceRotationSeconds) || 15, 5, 120);
    // 视图状态只保存当前渲染所需的只读快照、选择位置、天气缓存和轮播暂停时刻。
    const state = {
        events: [],
        todos: [],
        spaces: [],
        activeSpace: 0,
        selectedDate: new Date(),
        weather: readWeatherCache(),
        manualSpacePauseUntil: 0
    };
    let dataRefreshTimer = null;
    let dataRefreshInFlight = null;
    let dataRefreshRequested = false;
    let lastCalendarEventSeq = 0;
    let unsubscribeEvents = null;

    const workspace = make("section", "calendar-workspace");
    const hero = make("div", "calendar-hero");
    const miniCalendar = make("section", "calendar-mini");
    const focus = make("section", "calendar-focus");
    const grid = make("div", "calendar-grid");
    const todoPanel = make("section", "calendar-panel calendar-todo-panel");
    const schedulePanel = make("section", "calendar-panel calendar-schedule-panel");
    hero.append(miniCalendar, focus);
    grid.append(todoPanel, schedulePanel);
    workspace.append(hero, grid);
    root.replaceChildren(workspace);

    const calendarTitle = make("strong", "calendar-mini-title");
    const calendarGrid = make("div", "calendar-mini-grid");
    const calendarHead = make("header", "calendar-mini-head");
    const previousMonth = iconButton(context, "arrow", "上个月", "calendar-mini-prev");
    previousMonth.querySelector("svg")?.setAttribute("transform", "rotate(180)");
    const nextMonth = iconButton(context, "arrow", "下个月", "calendar-mini-next");
    calendarHead.append(previousMonth, calendarTitle, nextMonth);
    miniCalendar.append(calendarHead, calendarGrid);

    const clockBlock = make("div", "calendar-clock-block");
    const clock = make("div", "calendar-clock");
    const todayLine = make("div", "calendar-today");
    const dateLine = make("div", "calendar-date-line");
    const weatherLine = make("div", "calendar-weather");
    const weatherText = make("span", "calendar-weather-text", "正在读取天气…");
    const weatherForm = make("form", "calendar-weather-form");
    const weatherInput = make("input", "calendar-weather-input");
    weatherInput.type = "search";
    weatherInput.placeholder = "城市";
    weatherInput.setAttribute("aria-label", "手动选择天气城市");
    const weatherSubmit = iconButton(context, "refresh", "查询城市天气", "");
    weatherSubmit.type = "submit";
    weatherForm.append(weatherInput, weatherSubmit);
    weatherLine.append(weatherText, weatherForm);
    clockBlock.append(clock, todayLine);
    dateLine.append(clockBlock, weatherLine);

    const focusTitle = make("input", "calendar-focus-title");
    focusTitle.type = "text";
    focusTitle.value = readStoredValue(FOCUS_TITLE_KEY);
    focusTitle.placeholder = "Attention Is All You Need";
    focusTitle.setAttribute("aria-label", "顶部文本");
    focusTitle.addEventListener("input", () => {
        writeStoredValue(FOCUS_TITLE_KEY, focusTitle.value);
    });

    // 空间导航按钮都有可见文字或 aria-label；图片和视频也提供替代文本，图标不是唯一可访问名称。
    const spacePanel = make("section", "calendar-space");
    const spaceHead = make("header", "calendar-space-head");
    const spaceTitle = make("div", "calendar-space-title", "自定义空间");
    const spaceActions = make("div", "calendar-space-actions");
    const addSpaceButton = iconButton(context, "modules", "创建空间", "");
    const deleteSpaceButton = iconButton(context, "trash", "删除当前空间", "");
    const previousSpace = iconButton(context, "arrow", "上一个空间", "calendar-space-prev");
    previousSpace.querySelector("svg")?.setAttribute("transform", "rotate(180)");
    const nextSpace = iconButton(context, "arrow", "下一个空间", "calendar-space-next");
    spaceActions.append(previousSpace, nextSpace, addSpaceButton, deleteSpaceButton);
    const spaceBody = make("div", "calendar-space-body");
    const spaceMeta = make("div", "calendar-space-meta");
    spaceHead.append(spaceTitle, spaceActions);
    spacePanel.append(spaceHead, spaceBody, spaceMeta);
    focus.append(dateLine, focusTitle, spacePanel);

    const todoHead = make("header", "calendar-panel-head");
    const todoTitle = make("h2", "calendar-panel-title", "待办事项");
    const todoCount = make("span", "calendar-panel-count");
    todoTitle.append(todoCount);
    const addTodoButton = iconButton(context, "check", "新增待办", "");
    todoHead.append(todoTitle, addTodoButton);
    const todoList = make("div", "calendar-list");
    todoPanel.append(todoHead, todoList);

    const scheduleHead = make("header", "calendar-panel-head");
    const scheduleTitle = make("h2", "calendar-panel-title", "日程表");
    const addEventButton = iconButton(context, "calendar", "新增日程", "");
    scheduleHead.append(scheduleTitle, addEventButton);
    const dateStrip = make("div", "calendar-date-strip");
    const agenda = make("div", "calendar-list calendar-agenda");
    schedulePanel.append(scheduleHead, dateStrip, agenda);

    const spaceDialog = createSpaceDialog();
    const todoDialog = createTodoDialog();
    const eventDialog = createEventDialog();
    workspace.append(spaceDialog.dialog, todoDialog.dialog, eventDialog.dialog);

    previousMonth.addEventListener("click", () => changeMonth(-1));
    nextMonth.addEventListener("click", () => changeMonth(1));
    previousSpace.addEventListener("click", () => rotateSpace(-1, true));
    nextSpace.addEventListener("click", () => rotateSpace(1, true));
    addSpaceButton.addEventListener("click", () => openSpaceDialog());
    deleteSpaceButton.addEventListener("click", deleteActiveSpace);
    addTodoButton.addEventListener("click", () => openTodoDialog());
    addEventButton.addEventListener("click", () => openEventDialog());
    weatherForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        const city = weatherInput.value.trim();
        if (city) {
            await loadWeatherByCity(city);
        }
    });

    renderCalendar();
    renderClock();
    renderDateStrip();
    renderSpaces();
    renderTodos();
    renderAgenda();
    void loadWeather();

    const clockTimer = window.setInterval(renderClock, 30_000);
    const rotationTimer = rotationEnabled
        ? window.setInterval(() => {
            if (Date.now() >= state.manualSpacePauseUntil) {
                rotateSpace(1, false, true);
            }
        }, rotationSeconds * 1000)
        : null;
    // 时钟和空间轮播可能长期运行；容器离开文档后必须同时清定时器、断开观察器，避免闭包泄漏。
    const disconnectObserver = new MutationObserver(() => {
        if (!workspace.isConnected) {
            window.clearInterval(clockTimer);
            if (rotationTimer) {
                window.clearInterval(rotationTimer);
            }
            cleanupDataRefresh();
            disconnectObserver.disconnect();
        }
    });
    disconnectObserver.observe(document.body, { childList: true, subtree: true });

    if (typeof context.subscribeEvents === "function") {
        try {
            unsubscribeEvents = context.subscribeEvents(handleFrameworkEvent);
        } catch (error) {
            showError(error);
        }
    }

    await refreshData();

    function handleFrameworkEvent(event) {
        if (!workspace.isConnected) {
            cleanupDataRefresh();
            return;
        }
        if (!calendarDataEvent(event, context.module.moduleId)) {
            return;
        }
        const sequence = Number(event.seq);
        if (Number.isFinite(sequence)) {
            if (sequence <= lastCalendarEventSeq) {
                return;
            }
            lastCalendarEventSeq = sequence;
        }
        scheduleDataRefresh();
    }

    function scheduleDataRefresh() {
        if (!workspace.isConnected) {
            return;
        }
        if (dataRefreshTimer !== null) {
            window.clearTimeout(dataRefreshTimer);
        }
        dataRefreshTimer = window.setTimeout(() => {
            dataRefreshTimer = null;
            if (!workspace.isConnected) {
                cleanupDataRefresh();
                return;
            }
            void refreshData();
        }, CALENDAR_EVENT_REFRESH_DELAY);
    }

    async function refreshData() {
        if (dataRefreshInFlight) {
            dataRefreshRequested = true;
            await dataRefreshInFlight;
            return;
        }
        dataRefreshInFlight = reloadData();
        try {
            await dataRefreshInFlight;
        } finally {
            dataRefreshInFlight = null;
            if (dataRefreshRequested && workspace.isConnected) {
                dataRefreshRequested = false;
                scheduleDataRefresh();
            }
        }
    }

    function cleanupDataRefresh() {
        if (dataRefreshTimer !== null) {
            window.clearTimeout(dataRefreshTimer);
            dataRefreshTimer = null;
        }
        dataRefreshRequested = false;
        unsubscribeEvents?.();
        unsubscribeEvents = null;
    }

    async function reloadData() {
        // 三类集合共同组成一个视图快照；全部成功后再替换，避免局部成功造成跨代数据混排。
        try {
            const [events, todos, spaces] = await Promise.all([
                context.query("calendar.list"),
                context.query("calendar.todo.list"),
                context.query("calendar.space.list")
            ]);
            if (!workspace.isConnected) {
                return;
            }
            state.events = Array.isArray(events?.items) ? events.items : [];
            state.todos = Array.isArray(todos?.items) ? todos.items : [];
            state.spaces = Array.isArray(spaces?.items) ? spaces.items : [];
            state.activeSpace = state.spaces.length
                ? randomIndex(state.spaces.length, state.activeSpace)
                : 0;
            renderSpaces();
            renderTodos();
            renderAgenda();
            renderCalendar();
        } catch (error) {
            showError(error);
        }
    }

    function renderCalendar() {
        const selected = state.selectedDate;
        calendarTitle.textContent = `${selected.getFullYear()} 年 ${selected.getMonth() + 1} 月`;
        calendarGrid.replaceChildren();
        ["一", "二", "三", "四", "五", "六", "日"].forEach((label) => {
            calendarGrid.append(make("span", "calendar-weekday", label));
        });
        const first = new Date(selected.getFullYear(), selected.getMonth(), 1);
        const offset = (first.getDay() + 6) % 7;
        const start = new Date(first);
        start.setDate(1 - offset);
        const todayKey = dateKey(new Date());
        const selectedKey = dateKey(selected);
        // 固定 42 格并按周一对齐，保证六周月份也稳定布局；日期 key 驱动今天、选中和内容标记。
        for (let index = 0; index < 42; index += 1) {
            const date = new Date(start);
            date.setDate(start.getDate() + index);
            const button = make("button", "calendar-day", String(date.getDate()));
            button.type = "button";
            button.dataset.date = dateKey(date);
            button.setAttribute("aria-label", fullDate(date));
            if (date.getMonth() !== selected.getMonth()) {
                button.classList.add("is-muted");
            }
            if (dateKey(date) === todayKey) {
                button.classList.add("is-today");
                button.setAttribute("aria-current", "date");
            }
            if (dateKey(date) === selectedKey) {
                button.classList.add("is-selected");
            }
            if (hasAgendaItem(date)) {
                button.classList.add("has-item");
            }
            button.addEventListener("click", () => {
                state.selectedDate = date;
                renderCalendar();
                renderDateStrip();
                renderAgenda();
            });
            calendarGrid.append(button);
        }
    }

    function changeMonth(offset) {
        state.selectedDate = new Date(
            state.selectedDate.getFullYear(),
            state.selectedDate.getMonth() + offset,
            1
        );
        renderCalendar();
        renderDateStrip();
        renderAgenda();
    }

    function renderClock() {
        const now = new Date();
        clock.textContent = formatTime(now, true);
        todayLine.textContent = `${fullDate(now)} · ${weekday(now)}`;
    }

    function renderDateStrip() {
        dateStrip.replaceChildren();
        const selected = state.selectedDate;
        const day = selected.getDay();
        const mondayOffset = day === 0 ? -6 : 1 - day;
        const monday = new Date(selected);
        monday.setDate(selected.getDate() + mondayOffset);
        for (let index = 0; index < 7; index += 1) {
            const date = new Date(monday);
            date.setDate(monday.getDate() + index);
            const button = make("button", "calendar-date-chip");
            button.type = "button";
            button.append(
                make("span", "", "一二三四五六日"[index]),
                make("strong", "", String(date.getDate()))
            );
            if (dateKey(date) === dateKey(selected)) {
                button.classList.add("is-active");
            }
            button.addEventListener("click", () => {
                state.selectedDate = date;
                renderCalendar();
                renderDateStrip();
                renderAgenda();
            });
            dateStrip.append(button);
        }
    }

    function renderSpaces() {
        spaceBody.replaceChildren();
        const space = state.spaces[state.activeSpace];
        const hasSpace = Boolean(space);
        previousSpace.disabled = state.spaces.length < 2;
        nextSpace.disabled = state.spaces.length < 2;
        deleteSpaceButton.disabled = !hasSpace;
        spaceMeta.textContent = state.spaces.length
            ? `${state.activeSpace + 1} / ${state.spaces.length} · 每 ${rotationSeconds} 秒随机轮播`
            : "创建文本、图片或视频空间";
        if (!hasSpace) {
            spaceBody.append(make("p", "calendar-empty", "还没有自定义空间。"));
            return;
        }
        // 非文本媒体只使用宿主签发的模块数据 URL，不拼接文件系统路径。
        if (space.type === "text") {
            spaceBody.append(make("p", "calendar-space-text", space.text || "空白文本空间"));
        } else if (space.type === "image") {
            const image = make("img", "calendar-space-media");
            image.src = context.moduleDataUrl(space.mediaPath);
            image.alt = "自定义图片空间";
            spaceBody.append(image);
        } else if (space.type === "video") {
            const video = make("video", "calendar-space-media");
            video.src = context.moduleDataUrl(space.mediaPath);
            video.muted = true;
            video.autoplay = true;
            video.loop = true;
            video.playsInline = true;
            video.setAttribute("aria-label", "自定义视频空间，永久静音");
            spaceBody.append(video);
        }
    }

    function rotateSpace(offset, manual, random = false) {
        // 自动轮播和手动导航共享索引；随机轮播保证与当前项不同，手动操作后短时暂停自动切换。
        if (state.spaces.length < 2) {
            return;
        }
        if (random) {
            state.activeSpace = randomIndex(state.spaces.length, state.activeSpace);
        } else {
            state.activeSpace = (state.activeSpace + offset + state.spaces.length) % state.spaces.length;
        }
        if (manual) {
            state.manualSpacePauseUntil = Date.now() + MANUAL_SPACE_PAUSE;
        }
        renderSpaces();
    }

    function renderTodos() {
        todoList.replaceChildren();
        const open = state.todos.filter((item) => item.status !== "COMPLETED");
        todoCount.textContent = `${open.length} 项未完成`;
        if (!state.todos.length) {
            todoList.append(make("p", "calendar-empty", "还没有日历待办。"));
            return;
        }
        [...state.todos]
            .sort((left, right) => String(left.dueAt || "9999").localeCompare(String(right.dueAt || "9999")))
            .forEach((todo) => {
                const row = make("article", "calendar-todo");
                const checkbox = make("input", "calendar-check");
                checkbox.type = "checkbox";
                checkbox.checked = todo.status === "COMPLETED";
                checkbox.setAttribute("aria-label", checkbox.checked ? "重开待办" : "完成待办");
                checkbox.addEventListener("change", async () => {
                    // 动作失败时恢复复选框视觉状态，成功或失败都等待宿主结果后再刷新权威快照。
                    try {
                        const actionId = checkbox.checked
                            ? "calendar.todo.complete"
                            : "calendar.todo.reopen";
                        await runAction(actionId, { id: todo.id });
                        await refreshData();
                    } catch (error) {
                        showError(error);
                        checkbox.checked = !checkbox.checked;
                    }
                });
                const copy = make("button", "calendar-todo-copy");
                copy.type = "button";
                copy.addEventListener("click", () => openTodoDialog(todo));
                const title = make("strong", "calendar-todo-title", todo.title || "未命名待办");
                if (todo.status === "COMPLETED") {
                    title.classList.add("is-completed");
                }
                const meta = [
                    todo.dueAt ? formatDateTime(todo.dueAt) : "未设置截止时间",
                    todo.category || "未分类"
                ].join(" · ");
                copy.append(title, make("span", "calendar-todo-meta", meta));
                if (todo.annotation) {
                    copy.append(make("span", "calendar-todo-note", todo.annotation));
                }
                row.append(checkbox, copy);
                todoList.append(row);
            });
    }

    function renderAgenda() {
        agenda.replaceChildren();
        const selectedKey = dateKey(state.selectedDate);
        const items = [];
        state.events
            .filter((event) => event.status !== "CANCELLED" && dateKey(new Date(event.startAt)) === selectedKey)
            .forEach((event) => items.push({ kind: "event", at: event.startAt, data: event }));
        state.todos
            .filter((todo) => todo.dueAt && todo.status !== "COMPLETED"
                && dateKey(new Date(todo.dueAt)) === selectedKey)
            .forEach((todo) => items.push({ kind: "todo", at: todo.dueAt, data: todo }));
        items.sort((left, right) => String(left.at).localeCompare(String(right.at)));
        if (!items.length) {
            agenda.append(make("p", "calendar-empty", "这一天没有日程或待办。"));
            return;
        }
        items.forEach((item) => {
            const row = make("button", `calendar-agenda-item is-${item.kind}`);
            row.type = "button";
            const time = make("time", "calendar-agenda-time", formatTime(new Date(item.at)));
            const copy = make("span", "calendar-agenda-copy");
            copy.append(
                make("strong", "", item.data.title || (item.kind === "todo" ? "未命名待办" : "未命名日程")),
                make("span", "", item.kind === "todo"
                    ? `待办 · ${item.data.category || "未分类"}`
                    : agendaEventMeta(item.data))
            );
            row.append(time, copy);
            if (item.kind === "event") {
                row.addEventListener("click", () => openEventDialog(item.data));
            } else {
                row.addEventListener("click", () => openTodoDialog(item.data));
            }
            agenda.append(row);
        });
    }

    function agendaEventMeta(event) {
        const start = formatTime(new Date(event.startAt));
        const end = showEndTime && event.endAt ? ` - ${formatTime(new Date(event.endAt))}` : "";
        return `日程 · ${start}${end}`;
    }

    function hasAgendaItem(date) {
        const key = dateKey(date);
        return state.events.some((event) => event.status !== "CANCELLED"
                && dateKey(new Date(event.startAt)) === key)
            || state.todos.some((todo) => todo.dueAt && todo.status !== "COMPLETED"
                && dateKey(new Date(todo.dueAt)) === key);
    }

    function openSpaceDialog() {
        spaceDialog.form.reset();
        spaceDialog.type.value = "text";
        spaceDialog.text.value = "";
        spaceDialog.file.value = "";
        toggleSpaceFields();
        spaceDialog.dialog.showModal();
    }

    function toggleSpaceFields() {
        const media = spaceDialog.type.value !== "text";
        spaceDialog.textField.hidden = media;
        spaceDialog.fileField.hidden = !media;
        spaceDialog.file.accept = spaceDialog.type.value === "image"
            ? "image/jpeg,image/png,image/webp"
            : "video/mp4,video/webm";
    }

    async function deleteActiveSpace() {
        const space = state.spaces[state.activeSpace];
        if (!space) {
            return;
        }
        if (!window.confirm("将这个空间整体移入项目回收站？")) {
            return;
        }
        try {
            await runAction("calendar.space.delete", { spaceId: space.id });
            await refreshData();
        } catch (error) {
            showError(error);
        }
    }

    function openTodoDialog(todo = null) {
        todoDialog.dialog.dataset.id = todo?.id || "";
        todoDialog.heading.textContent = todo ? "编辑待办" : "新增待办";
        todoDialog.title.value = todo?.title || "";
        todoDialog.dueAt.value = todo?.dueAt ? toDateTimeLocal(todo.dueAt) : "";
        todoDialog.category.value = todo?.category || "";
        todoDialog.annotation.value = todo?.annotation || "";
        todoDialog.dialog.showModal();
    }

    function openEventDialog(event = null) {
        eventDialog.dialog.dataset.id = event?.id || "";
        eventDialog.heading.textContent = event ? "编辑日程" : "新增日程";
        eventDialog.title.value = event?.title || "";
        eventDialog.title.disabled = Boolean(event);
        eventDialog.startAt.value = event?.startAt ? toDateTimeLocal(event.startAt) : toDateTimeLocal(new Date());
        eventDialog.endAt.value = event?.endAt ? toDateTimeLocal(event.endAt) : "";
        eventDialog.cancel.hidden = !event;
        eventDialog.dialog.showModal();
    }

    function createSpaceDialog() {
        const dialog = make("dialog", "calendar-dialog calendar-space-dialog");
        const form = make("form", "calendar-dialog-form");
        form.method = "dialog";
        const heading = make("h2", "calendar-dialog-title", "创建空间");
        const type = make("select", "calendar-field");
        type.setAttribute("aria-label", "空间类型");
        [
            ["text", "纯文本"],
            ["image", "图片"],
            ["video", "视频"]
        ].forEach(([value, label]) => {
            const option = make("option", "", label);
            option.value = value;
            type.append(option);
        });
        const textField = field("文本内容", "textarea");
        const text = textField.control;
        text.maxLength = 10_000;
        const fileField = field("选择媒体", "input");
        const file = fileField.control;
        file.type = "file";
        const footer = make("footer", "calendar-dialog-footer");
        const cancel = make("button", "calendar-secondary-button", "取消");
        cancel.type = "button";
        const submit = make("button", "calendar-primary-button", "创建");
        submit.type = "submit";
        footer.append(cancel, submit);
        form.append(heading, labeled("空间类型", type), textField.field, fileField.field, footer);
        dialog.append(form);
        cancel.addEventListener("click", () => dialog.close());
        type.addEventListener("change", toggleSpaceFields);
        // 表单只收集参数；文本和媒体都经宿主动作入口提交，上传文件路径由宿主受限处理。
        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            submit.disabled = true;
            try {
                if (type.value === "text") {
                    await runAction("calendar.space.create", {
                        type: "text",
                        text: text.value.trim()
                    });
                } else {
                    const media = file.files?.[0];
                    if (!media) {
                        throw new Error("请选择图片或视频文件");
                    }
                    const extension = media.name.split(".").pop()?.toLowerCase() || "";
                    const uploadPath = `uploads/${crypto.randomUUID()}.${extension}`;
                    await context.executeActionWithFile(
                        "calendar.space.create",
                        {
                            type: type.value,
                            uploadPath,
                            contentType: media.type
                        },
                        media,
                        true
                    );
                }
                dialog.close();
                await refreshData();
            } catch (error) {
                showError(error);
            } finally {
                submit.disabled = false;
            }
        });
        return { dialog, form, type, textField, text, fileField, file };
    }

    function createTodoDialog() {
        const dialog = make("dialog", "calendar-dialog");
        const form = make("form", "calendar-dialog-form");
        form.method = "dialog";
        const heading = make("h2", "calendar-dialog-title", "新增待办");
        const title = field("标题", "input").control;
        title.required = true;
        const dueAt = field("截止时间", "input").control;
        dueAt.type = "datetime-local";
        const category = field("分类", "input").control;
        const annotation = field("批注", "textarea").control;
        annotation.maxLength = 500;
        const footer = make("footer", "calendar-dialog-footer");
        const cancel = make("button", "calendar-secondary-button", "取消");
        cancel.type = "button";
        const submit = make("button", "calendar-primary-button", "保存");
        submit.type = "submit";
        footer.append(cancel, submit);
        form.append(
            heading,
            labeled("标题", title),
            labeled("截止时间", dueAt),
            labeled("分类", category),
            labeled("批注", annotation),
            footer
        );
        dialog.append(form);
        cancel.addEventListener("click", () => dialog.close());
        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            submit.disabled = true;
            try {
                const id = dialog.dataset.id;
                const argumentsValue = {
                    title: title.value.trim(),
                    dueAt: dueAt.value ? new Date(dueAt.value).toISOString() : "",
                    category: category.value.trim(),
                    annotation: annotation.value.trim()
                };
                if (id) {
                    await runAction("calendar.todo.update", { id, ...argumentsValue });
                } else {
                    await runAction("calendar.todo.create", argumentsValue);
                }
                dialog.close();
                await refreshData();
            } catch (error) {
                showError(error);
            } finally {
                submit.disabled = false;
            }
        });
        return { dialog, heading, title, dueAt, category, annotation };
    }

    function createEventDialog() {
        const dialog = make("dialog", "calendar-dialog");
        const form = make("form", "calendar-dialog-form");
        form.method = "dialog";
        const heading = make("h2", "calendar-dialog-title", "新增日程");
        const title = field("标题", "input").control;
        title.required = true;
        const startAt = field("开始时间", "input").control;
        startAt.type = "datetime-local";
        startAt.required = true;
        const endAt = field("结束时间", "input").control;
        endAt.type = "datetime-local";
        const footer = make("footer", "calendar-dialog-footer");
        const cancelEvent = make("button", "calendar-danger-button", "取消日程");
        cancelEvent.type = "button";
        const cancel = make("button", "calendar-secondary-button", "关闭");
        cancel.type = "button";
        const submit = make("button", "calendar-primary-button", "保存");
        submit.type = "submit";
        footer.append(cancelEvent, cancel, submit);
        form.append(
            heading,
            labeled("标题", title),
            labeled("开始时间", startAt),
            labeled("结束时间", endAt),
            footer
        );
        dialog.append(form);
        cancel.addEventListener("click", () => dialog.close());
        cancelEvent.addEventListener("click", async () => {
            const id = dialog.dataset.id;
            if (!id || !window.confirm("取消这条日程？")) {
                return;
            }
            try {
                await runAction("calendar.event.cancel", { eventId: id });
                dialog.close();
                await refreshData();
            } catch (error) {
                showError(error);
            }
        });
        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            submit.disabled = true;
            try {
                const id = dialog.dataset.id;
                if (id) {
                    await runAction("calendar.event.reschedule", {
                        eventId: id,
                        newStartAt: new Date(startAt.value).toISOString(),
                        ...(endAt.value ? { newEndAt: new Date(endAt.value).toISOString() } : {})
                    });
                } else {
                    await runAction("calendar.event.create", {
                        title: title.value.trim(),
                        startAt: new Date(startAt.value).toISOString(),
                        ...(endAt.value ? { endAt: new Date(endAt.value).toISOString() } : {})
                    });
                }
                dialog.close();
                await refreshData();
            } catch (error) {
                showError(error);
            } finally {
                submit.disabled = false;
            }
        });
        return { dialog, heading, title, startAt, endAt, cancel: cancelEvent };
    }

    async function loadWeather() {
        // 新鲜缓存优先；定位不可用或拒绝时保留上次天气，并始终开放手动城市查询作为降级路径。
        if (state.weather && Date.now() - state.weather.savedAt < WEATHER_TTL) {
            renderWeather(state.weather);
            return;
        }
        if (!navigator.geolocation) {
            renderWeatherStatus(state.weather ? "定位不可用，显示上次天气" : "定位不可用，可手动输入城市");
            return;
        }
        renderWeatherStatus("正在获取位置…");
        navigator.geolocation.getCurrentPosition(
            async (position) => {
                try {
                    await fetchWeather(
                        position.coords.latitude,
                        position.coords.longitude,
                        "当前位置"
                    );
                } catch (error) {
                    renderWeatherStatus("天气读取失败，可手动输入城市");
                }
            },
            () => renderWeatherStatus(state.weather
                ? "定位未授权，显示上次天气"
                : "定位未授权，可手动输入城市"),
            { enableHighAccuracy: false, timeout: 8000, maximumAge: 15 * 60 * 1000 }
        );
    }

    async function loadWeatherByCity(city) {
        renderWeatherStatus(`正在查询 ${city}…`);
        try {
            const response = await fetch(
                `https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(city)}&count=1&language=zh&format=json`
            );
            if (!response.ok) {
                throw new Error("城市查询失败");
            }
            const payload = await response.json();
            const result = payload?.results?.[0];
            if (!result) {
                throw new Error("未找到城市");
            }
            await fetchWeather(result.latitude, result.longitude, result.name || city);
        } catch (error) {
            renderWeatherStatus(error?.message || "城市天气读取失败");
        }
    }

    async function fetchWeather(latitude, longitude, label) {
        const response = await fetch(
            `https://api.open-meteo.com/v1/forecast?latitude=${latitude}&longitude=${longitude}&current=temperature_2m,weather_code&timezone=auto`
        );
        if (!response.ok) {
            throw new Error("天气服务不可用");
        }
        const payload = await response.json();
        const current = payload?.current;
        if (!current) {
            throw new Error("天气数据为空");
        }
        const weather = {
            label,
            temperature: Math.round(Number(current.temperature_2m)),
            code: Number(current.weather_code),
            savedAt: Date.now()
        };
        state.weather = weather;
        try {
            localStorage.setItem(WEATHER_CACHE_KEY, JSON.stringify(weather));
        } catch {
            // 缓存失败不影响当前天气显示。
        }
        renderWeather(weather);
    }

    function renderWeather(weather) {
        const label = WEATHER_LABELS.get(Number(weather.code)) || "天气";
        weatherText.textContent = `${weather.label} · ${label} ${weather.temperature}°C`;
        weatherText.title = `更新于 ${formatTime(new Date(weather.savedAt))}`;
    }

    function renderWeatherStatus(message) {
        weatherText.textContent = message;
    }

    function readWeatherCache() {
        try {
            const value = JSON.parse(localStorage.getItem(WEATHER_CACHE_KEY) || "null");
            return value && Number.isFinite(Number(value.savedAt)) ? value : null;
        } catch {
            return null;
        }
    }

    async function runAction(actionId, argumentsValue) {
        // UI 不自行判定授权，只把动作交给宿主；非完成状态转换为当前视图可展示的错误消息。
        const result = await context.executeAction(actionId, argumentsValue, true);
        if (result?.status && !String(result.status).startsWith("COMPLETED")) {
            throw new Error(result.steps?.at(-1)?.message || "动作执行失败");
        }
        return result;
    }

    function showError(error) {
        context.toast(error?.message || "日历操作失败", { error: true });
    }

    function formatTime(value, includeSeconds = false) {
        const date = value instanceof Date ? value : new Date(value);
        if (Number.isNaN(date.getTime())) {
            return "—";
        }
        const minutes = String(date.getMinutes()).padStart(2, "0");
        const seconds = String(date.getSeconds()).padStart(2, "0");
        if (hourFormat === "12") {
            const suffix = date.getHours() >= 12 ? "下午" : "上午";
            const hour = date.getHours() % 12 || 12;
            return `${suffix} ${hour}:${minutes}${includeSeconds ? `:${seconds}` : ""}`;
        }
        return `${String(date.getHours()).padStart(2, "0")}:${minutes}`
            + (includeSeconds ? `:${seconds}` : "");
    }

    function formatDateTime(value) {
        const date = new Date(value);
        return Number.isNaN(date.getTime())
            ? "时间未设置"
            : `${date.getMonth() + 1} 月 ${date.getDate()} 日 ${formatTime(date)}`;
    }

    function fullDate(date) {
        return `${date.getFullYear()} 年 ${date.getMonth() + 1} 月 ${date.getDate()} 日`;
    }

    function weekday(date) {
        return `星期${"日一二三四五六"[date.getDay()]}`;
    }

    function toDateTimeLocal(value) {
        const date = value instanceof Date ? value : new Date(value);
        if (Number.isNaN(date.getTime())) {
            return "";
        }
        const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
        return local.toISOString().slice(0, 16);
    }

    function dateKey(date) {
        return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
    }

    function randomIndex(length, current) {
        if (length <= 1) {
            return 0;
        }
        let index = current;
        while (index === current) {
            index = Math.floor(Math.random() * length);
        }
        return index;
    }

    function readStoredValue(key) {
        try {
            return localStorage.getItem(key) || "";
        } catch {
            return "";
        }
    }

    function writeStoredValue(key, value) {
        try {
            localStorage.setItem(key, value);
        } catch {
            // 顶部文本仍可在当前会话中输入，本地存储失败不阻塞其他功能。
        }
    }

    function iconButton(context, iconName, label, id) {
        const button = make("button", "calendar-icon-button");
        button.type = "button";
        button.setAttribute("aria-label", label);
        button.title = label;
        if (id) {
            button.id = id;
        }
        button.innerHTML = context.icon(iconName, 18);
        return button;
    }

    function labeled(label, control) {
        const wrapper = make("label", "calendar-field-label");
        wrapper.append(make("span", "", label), control);
        return wrapper;
    }

    function field(label, tag) {
        const control = make(tag, "calendar-field");
        const wrapper = labeled(label, control);
        return { field: wrapper, control };
    }
}

function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
}
