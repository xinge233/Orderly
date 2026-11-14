function text(value, fallback = "") {
    return typeof value === "string" && value.trim() ? value : fallback;
}

function normalize(value) {
    return String(value ?? "").normalize("NFKC").toLocaleLowerCase("zh-CN");
}

function statusTone(status) {
    if (status === "已整理") return "safe";
    if (status === "待复核") return "warning";
    return "reference";
}

export default async function mount(root, context) {
    const make = context.element;
    const state = {
        payload: null,
        courses: [],
        materials: [],
        courseById: new Map(),
        sourceById: new Map(),
        activeCourse: "all",
        search: "",
        selectedId: null
    };

    function iconNode(name, size = 18, className = "") {
        const node = make("span", className);
        node.innerHTML = context.icon(name, size);
        return node;
    }

    function notifyPending(feature) {
        context.toast?.(`${feature}尚未接入，未产生任何变更`);
    }

    function createButton(label, iconName, variant = "") {
        const button = make(
            "button",
            ["fs-ui-button", variant].filter(Boolean).join(" ")
        );
        button.type = "button";
        button.append(iconNode(iconName, 18), make("span", "", label));
        return button;
    }

    function createPendingButton(label, iconName) {
        const button = make("button", "fs-ui-button fs-ui-button--secondary");
        button.type = "button";
        const copy = make("span", "fs-ui-button__copy");
        copy.append(
            make("span", "", label),
            make("small", "fs-ui-button__note", "尚未接入")
        );
        button.append(iconNode(iconName, 18), copy);
        button.addEventListener("click", () => notifyPending(label));
        return button;
    }

    const panel = make("section", "fs-ui");
    const titleId = "fs-ui-materials-title";
    panel.setAttribute("aria-labelledby", titleId);

    const header = make("header", "fs-ui-header");
    const heading = make("div", "fs-ui-heading");
    const headingIcon = iconNode("file", 26, "fs-ui-heading__icon");
    const headingCopy = make("div");
    const title = make("h1", "", "资料管理");
    title.id = titleId;
    headingCopy.append(
        make("p", "fs-ui-eyebrow", "MATERIALS LIBRARY"),
        title,
        make(
            "p",
            "fs-ui-muted",
            "按课程浏览、检索和阅读模块自带的只读学习资料。"
        )
    );
    heading.append(headingIcon, headingCopy);

    const headerActions = make("div", "fs-ui-header__actions");
    const uploadButton = createPendingButton("上传文件", "file");
    const historyButton = createPendingButton("历史任务", "history");
    const commandButton = createButton(
        "输入整理指令",
        "spark",
        "fs-ui-button--primary"
    );
    commandButton.addEventListener("click", () => {
        context.fillCommand(
            context.ui.examples?.[0]?.text || "创建目录 课程归档"
        );
    });
    headerActions.append(uploadButton, historyButton, commandButton);
    header.append(heading, headerActions);

    const notice = make("p", "fs-ui-notice");
    notice.append(
        iconNode("info", 18, "fs-ui-notice__icon"),
        make(
            "span",
            "",
            "资料数据只来自 fs.materials；当前仅连接本地规则计划器，未连接外部模型。"
        )
    );

    const controls = make("section", "fs-ui-controls");
    controls.setAttribute("aria-label", "资料检索与课程筛选");
    const searchLabel = make("label", "fs-ui-search");
    const searchInput = make("input");
    searchInput.type = "search";
    searchInput.placeholder = "搜索资料、章节、格式或课程";
    searchInput.autocomplete = "off";
    searchInput.setAttribute("aria-label", "搜索资料");
    searchLabel.append(
        iconNode("search", 19, "fs-ui-search__icon"),
        searchInput
    );
    const chips = make("div", "fs-ui-chips");
    chips.setAttribute("aria-label", "课程筛选");
    controls.append(searchLabel, chips);

    const main = make("div", "fs-ui-main");
    const catalog = make("section", "fs-ui-catalog");
    const catalogTitleId = "fs-ui-catalog-title";
    catalog.setAttribute("aria-labelledby", catalogTitleId);
    const catalogHeader = make("header", "fs-ui-catalog__header");
    const catalogTitle = make("h2", "", "资料目录");
    catalogTitle.id = catalogTitleId;
    const resultCount = make("span", "fs-ui-result-count", "正在读取");
    resultCount.setAttribute("role", "status");
    resultCount.setAttribute("aria-live", "polite");
    catalogHeader.append(catalogTitle, resultCount);

    const tableWrap = make("div", "fs-ui-table-wrap");
    const table = make("table", "fs-ui-table");
    const tableHead = make("thead");
    const headRow = make("tr");
    ["资料名称", "课程", "类型", "状态"].forEach((label) => {
        const cell = make("th", "", label);
        cell.scope = "col";
        headRow.append(cell);
    });
    tableHead.append(headRow);
    const tableBody = make("tbody");
    table.append(tableHead, tableBody);
    tableWrap.append(table);
    catalog.append(catalogHeader, tableWrap);

    const detail = make("aside", "fs-ui-detail");
    const detailTitleId = "fs-ui-detail-title";
    detail.setAttribute("aria-labelledby", detailTitleId);
    const detailHeader = make("header", "fs-ui-detail__header");
    const detailTitle = make("h2", "", "资料详情");
    detailTitle.id = detailTitleId;
    const detailStatus = make("span", "fs-ui-result-count", "只读");
    detailHeader.append(detailTitle, detailStatus);
    const detailBody = make("div", "fs-ui-detail__body");
    detail.append(detailHeader, detailBody);
    main.append(catalog, detail);

    const footer = make("footer", "fs-ui-footer");
    const sourceSummary = make("span", "", "数据源：fs.materials");
    footer.append(
        sourceSummary,
        make(
            "span",
            "",
            "文件变更仍需经过计划确认；删除只进入项目回收站。"
        )
    );
    panel.append(header, notice, controls, main, footer);
    root.replaceChildren(panel);

    function loadingRow(label) {
        const row = make("tr");
        const cell = make("td", "fs-ui-table__empty", label);
        cell.colSpan = 4;
        row.append(cell);
        return row;
    }

    function renderEmptyDetail(message) {
        detailStatus.textContent = "只读";
        detailBody.replaceChildren();
        const empty = make("div", "fs-ui-detail__empty");
        empty.append(
            iconNode("file", 20, "fs-ui-detail__empty-icon"),
            make("strong", "", "尚未选择资料"),
            make("p", "", message)
        );
        detailBody.append(empty);
    }

    function renderError(error) {
        resultCount.textContent = "读取失败";
        tableBody.replaceChildren();
        const row = make("tr");
        const cell = make(
            "td",
            "fs-ui-table__error",
            error?.message || "资料数据读取失败"
        );
        cell.colSpan = 4;
        row.append(cell);
        tableBody.append(row);
        detailStatus.textContent = "不可用";
        detailBody.replaceChildren();
        const message = make(
            "p",
            "fs-ui-detail__error",
            "资料详情暂不可用，请检查模块数据后重试。"
        );
        detailBody.append(message);
    }

    function courseFor(material) {
        return state.courseById.get(material.courseId) || null;
    }

    function sourceFor(material) {
        return state.sourceById.get(material.sourceId) || null;
    }

    function searchText(material) {
        const course = courseFor(material);
        const source = sourceFor(material);
        const cards = Array.isArray(material.cards) ? material.cards : [];
        return normalize([
            material.title,
            material.chapter,
            material.format,
            material.status,
            material.excerpt,
            course?.title,
            course?.code,
            course?.description,
            source?.label,
            ...cards.flatMap((card) => [card?.title, card?.summary])
        ].join(" "));
    }

    function filteredMaterials() {
        const needle = normalize(state.search).trim();
        return state.materials.filter((material) => {
            const matchesCourse = state.activeCourse === "all"
                || material.courseId === state.activeCourse;
            const matchesSearch = !needle || searchText(material).includes(needle);
            return matchesCourse && matchesSearch;
        });
    }

    function renderCourseChips() {
        chips.replaceChildren();
        const entries = [
            {
                id: "all",
                title: "全部课程",
                count: state.materials.length
            },
            ...state.courses.map((course) => ({
                id: course.id,
                title: course.title,
                count: state.materials.filter(
                    (material) => material.courseId === course.id
                ).length
            }))
        ];
        entries.forEach((entry) => {
            const active = state.activeCourse === entry.id;
            const chip = make(
                "button",
                `fs-ui-chip${active ? " is-active" : ""}`
            );
            chip.type = "button";
            chip.setAttribute("aria-pressed", String(active));
            chip.append(
                make("span", "", entry.title),
                make("span", "fs-ui-chip__count", String(entry.count))
            );
            chip.addEventListener("click", () => {
                state.activeCourse = entry.id;
                renderCourseChips();
                renderResults();
            });
            chips.append(chip);
        });
    }

    function statusBadge(material) {
        const status = text(material.status, "只读示例");
        return make(
            "span",
            `fs-ui-status fs-ui-status--${statusTone(status)}`,
            status
        );
    }

    function selectMaterial(id) {
        state.selectedId = id;
        tableBody.querySelectorAll("tr[data-material-id]").forEach((row) => {
            const selected = row.dataset.materialId === id;
            row.classList.toggle("is-selected", selected);
            row.setAttribute("aria-selected", String(selected));
        });
        const material = state.materials.find((item) => item.id === id) || null;
        renderDetail(material);
    }

    function materialRow(material) {
        const row = make("tr");
        row.dataset.materialId = material.id;
        row.setAttribute("aria-selected", String(material.id === state.selectedId));
        row.classList.toggle("is-selected", material.id === state.selectedId);
        row.addEventListener("click", () => selectMaterial(material.id));

        const nameCell = make("td");
        const nameButton = make("button", "fs-ui-material-link");
        nameButton.type = "button";
        nameButton.append(
            make("strong", "", text(material.title, "未命名资料")),
            make(
                "small",
                "",
                `${text(material.chapter, "未分章")} · ${text(material.excerpt, "暂无摘要")}`
            )
        );
        nameButton.addEventListener("click", (event) => {
            event.stopPropagation();
            selectMaterial(material.id);
        });
        nameCell.append(nameButton);

        const course = courseFor(material);
        const courseCell = make("td");
        const courseCopy = make("div", "fs-ui-course");
        courseCopy.append(
            make("strong", "", text(course?.title, "未分类课程")),
            make("small", "", text(course?.code, "NO-CODE"))
        );
        courseCell.append(courseCopy);

        const typeCell = make("td");
        const type = make("div", "fs-ui-type");
        type.append(make("span", "fs-ui-format", text(material.format, "文件")));
        typeCell.append(type);

        const statusCell = make("td");
        statusCell.append(statusBadge(material));

        row.append(nameCell, courseCell, typeCell, statusCell);
        return row;
    }

    function metaItem(label, value) {
        const item = make("div", "fs-ui-detail__meta-item");
        item.append(
            make("span", "fs-ui-detail__meta-label", label),
            make("span", "fs-ui-detail__meta-value", text(value, "未提供"))
        );
        return item;
    }

    function renderDetail(material) {
        detailBody.replaceChildren();
        if (!material) {
            renderEmptyDetail("从左侧资料表中选择一项以查看摘要与知识卡片。");
            return;
        }
        const course = courseFor(material);
        const source = sourceFor(material);
        detailStatus.replaceChildren(statusBadge(material));

        const headingSection = make("div", "fs-ui-detail__heading");
        headingSection.append(
            make(
                "p",
                "fs-ui-detail__kicker",
                `${text(course?.code, "NO-CODE")} · ${text(material.chapter, "未分章")}`
            ),
            make(
                "h3",
                "fs-ui-detail__title",
                text(material.title, "未命名资料")
            ),
            make(
                "p",
                "fs-ui-detail__excerpt",
                text(material.excerpt, "暂无摘要")
            )
        );

        const meta = make("div", "fs-ui-detail__meta");
        meta.append(
            metaItem("课程", course?.title),
            metaItem("资料格式", material.format),
            metaItem("来源", source?.label),
            metaItem("记录 ID", material.id)
        );

        const cardSection = make("section", "fs-ui-detail__section");
        cardSection.append(make("h3", "", "知识卡片"));
        const cards = Array.isArray(material.cards) ? material.cards : [];
        if (cards.length) {
            const list = make("div", "fs-ui-card-list");
            cards.forEach((card) => {
                const item = make("article", "fs-ui-card");
                item.append(
                    make("strong", "", text(card?.title, "未命名卡片")),
                    make("p", "", text(card?.summary, "暂无摘要"))
                );
                list.append(item);
            });
            cardSection.append(list);
        } else {
            cardSection.append(make("p", "fs-ui-muted", "这份资料暂无知识卡片。"));
        }

        const readonlyNote = make("div", "fs-ui-readonly-note");
        readonlyNote.append(
            iconNode("shield", 17, "fs-ui-readonly-note__icon"),
            make(
                "span",
                "",
                "当前视图为模块只读数据，不会执行整理、上传或文件变更。"
            )
        );

        const sourceLine = make(
            "p",
            "fs-ui-detail__source",
            text(source?.location, material.sourceId)
        );

        const organizeButton = createPendingButton("AI 整理", "spark");
        organizeButton.classList.add("fs-ui-detail__action");

        detailBody.append(
            headingSection,
            meta,
            cardSection,
            readonlyNote,
            sourceLine,
            organizeButton
        );
    }

    function renderResults() {
        const materials = filteredMaterials();
        resultCount.textContent = `显示 ${materials.length} / ${state.materials.length} 份`;
        tableBody.replaceChildren();
        if (!materials.length) {
            tableBody.append(loadingRow("没有符合当前搜索和课程筛选的资料。"));
            state.selectedId = null;
            renderEmptyDetail("调整搜索词或课程筛选后再试。");
            return;
        }
        if (!materials.some((material) => material.id === state.selectedId)) {
            state.selectedId = materials[0].id;
        }
        materials.forEach((material) => {
            tableBody.append(materialRow(material));
        });
        const selected = materials.find(
            (material) => material.id === state.selectedId
        );
        renderDetail(selected || null);
    }

    searchInput.addEventListener("input", () => {
        state.search = searchInput.value;
        renderResults();
    });

    tableBody.append(loadingRow("正在读取模块资料…"));
    renderEmptyDetail("正在读取 fs.materials…");

    try {
        const payload = await context.query("fs.materials");
        if (
            payload?.kind !== "materials"
            || payload?.ownerModule !== context.module.moduleId
            || payload?.readOnly !== true
        ) {
            throw new TypeError("fs.materials 返回了不符合 materials 契约的数据");
        }
        const courses = payload?.payload?.courses;
        const materials = payload?.payload?.materials;
        if (!Array.isArray(courses) || !Array.isArray(materials)) {
            throw new TypeError("fs.materials 缺少 courses 或 materials 数组");
        }
        if (Number(payload.count) !== materials.length) {
            throw new TypeError("fs.materials 的 count 与资料数量不一致");
        }
        state.payload = payload;
        state.courses = courses;
        state.materials = materials;
        state.courseById = new Map(
            courses.map((course) => [course.id, course])
        );
        state.sourceById = new Map(
            (Array.isArray(payload.sources) ? payload.sources : [])
                .map((source) => [source.id, source])
        );
        state.selectedId = materials[0]?.id || null;
        sourceSummary.textContent = `数据源：fs.materials · ${text(payload.title, "资料库")}`;
        renderCourseChips();
        renderResults();
    } catch (error) {
        renderError(error);
    }
}
