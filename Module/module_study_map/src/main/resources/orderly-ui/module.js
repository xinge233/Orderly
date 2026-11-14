const SVG_NS = "http://www.w3.org/2000/svg";
const COURSE_LABELS = Object.freeze({
    calculus: "高等数学（上）",
    "calculus-2": "高等数学（下）"
});
const CATEGORY_CLASSES = Object.freeze({
    模块: "module",
    概念: "concept",
    方法: "method",
    公式: "formula",
    练习: "practice"
});
const NODE_WIDTH = 184;
const NODE_HEIGHT = 64;
const COLUMN_GAP = 248;
const ROW_GAP = 88;
const CANVAS_PADDING = 40;
const collator = new Intl.Collator("zh-CN");

function text(value, fallback = "") {
    return typeof value === "string" && value.trim() ? value.trim() : fallback;
}

function normalizeGraph(raw) {
    const nodes = Array.isArray(raw?.payload?.nodes)
        ? raw.payload.nodes.filter((node) =>
            text(node?.id) &&
            text(node?.label) &&
            text(node?.courseId) &&
            Number.isInteger(node?.layer) &&
            Number.isInteger(node?.order)
        )
        : [];
    const nodeIds = new Set(nodes.map((node) => node.id));
    const edges = Array.isArray(raw?.payload?.edges)
        ? raw.payload.edges.filter((edge) =>
            text(edge?.id) &&
            nodeIds.has(edge?.source) &&
            nodeIds.has(edge?.target)
        )
        : [];
    return {
        title: text(raw?.title, "高等数学知识脉络"),
        description: text(raw?.description, "只读浏览高等数学知识节点与关系。"),
        sources: Array.isArray(raw?.sources) ? raw.sources : [],
        nodes,
        edges
    };
}

function courseLabel(courseId) {
    return COURSE_LABELS[courseId] || courseId;
}

function createSvgElement(name, attributes = {}) {
    const element = document.createElementNS(SVG_NS, name);
    Object.entries(attributes).forEach(([key, value]) => {
        element.setAttribute(key, String(value));
    });
    return element;
}

function shortLabel(value) {
    const label = text(value);
    return label.length > 12 ? `${label.slice(0, 12)}…` : label;
}

function computeLayout(nodes) {
    const layers = new Map();
    nodes.forEach((node) => {
        if (!layers.has(node.layer)) {
            layers.set(node.layer, []);
        }
        layers.get(node.layer).push(node);
    });

    const layerNumbers = [...layers.keys()].sort((left, right) => left - right);
    const maxLayer = layerNumbers.length ? layerNumbers[layerNumbers.length - 1] : 0;
    const maxRows = Math.max(
        1,
        ...layerNumbers.map((layer) => layers.get(layer).length)
    );
    const width = CANVAS_PADDING * 2 + maxLayer * COLUMN_GAP + NODE_WIDTH;
    const height = CANVAS_PADDING * 2 + (maxRows - 1) * ROW_GAP + NODE_HEIGHT;
    const positions = new Map();

    layerNumbers.forEach((layer) => {
        const layerNodes = layers.get(layer).sort((left, right) =>
            left.order - right.order ||
            collator.compare(left.label, right.label)
        );
        const layerHeight = (layerNodes.length - 1) * ROW_GAP + NODE_HEIGHT;
        const top = CANVAS_PADDING + (height - CANVAS_PADDING * 2 - layerHeight) / 2;
        layerNodes.forEach((node, index) => {
            positions.set(node.id, {
                x: CANVAS_PADDING + layer * COLUMN_GAP,
                y: top + index * ROW_GAP
            });
        });
    });

    return { positions, width, height };
}

function edgePath(source, target) {
    const leftToRight = target.x >= source.x;
    const startX = leftToRight ? source.x + NODE_WIDTH : source.x;
    const endX = leftToRight ? target.x : target.x + NODE_WIDTH;
    const startY = source.y + NODE_HEIGHT / 2;
    const endY = target.y + NODE_HEIGHT / 2;
    const direction = leftToRight ? 1 : -1;
    const bend = Math.max(60, Math.abs(endX - startX) * 0.45);
    return [
        `M ${startX} ${startY}`,
        `C ${startX + direction * bend} ${startY}`,
        `${endX - direction * bend} ${endY}`,
        `${endX} ${endY}`
    ].join(" ");
}

export default async function mount(root, context) {
    const make = context.element;
    const panel = make("section", "study-map-ui");
    const header = make("header", "study-map-ui-header");
    const heading = make("div", "study-map-ui-heading");
    const eyebrow = make("p", "study-map-ui-eyebrow");
    const eyebrowIcon = make("span", "study-map-ui-eyebrow-icon");
    eyebrowIcon.innerHTML = context.icon("route", 16);
    eyebrow.append(eyebrowIcon, make("span", "", "KNOWLEDGE MAP"));
    const title = make("h1", "", context.module.title || "高数知识图谱");
    const description = make(
        "p",
        "study-map-ui-description",
        "正在读取模块自有的高等数学知识节点…"
    );
    heading.append(eyebrow, title, description);
    const readonly = make("div", "study-map-ui-readonly");
    const readonlyIcon = make("span", "study-map-ui-readonly-icon");
    readonlyIcon.innerHTML = context.icon("shield", 17);
    readonly.append(readonlyIcon, make("span", "", "只读模块数据"));
    header.append(heading, readonly);

    const controls = make("div", "study-map-ui-controls");
    const courseField = make("label", "study-map-ui-field");
    courseField.append(make("span", "study-map-ui-field-label", "课程"));
    const courseSelect = make("select", "study-map-ui-select");
    courseSelect.disabled = true;
    courseField.append(courseSelect);

    const searchField = make("label", "study-map-ui-field study-map-ui-field--search");
    searchField.append(make("span", "study-map-ui-field-label", "搜索"));
    const searchControl = make("span", "study-map-ui-search");
    const searchIcon = make("span", "study-map-ui-search-icon");
    searchIcon.innerHTML = context.icon("search", 16);
    const searchInput = make("input", "study-map-ui-input");
    searchInput.type = "search";
    searchInput.placeholder = "搜索节点、概念或说明";
    searchInput.autocomplete = "off";
    searchControl.append(searchIcon, searchInput);
    searchField.append(searchControl);
    controls.append(courseField, searchField);

    const workspace = make("div", "study-map-ui-workspace");
    const graphPanel = make("section", "study-map-ui-graph-panel");
    const graphHeader = make("header", "study-map-ui-panel-header");
    const graphHeading = make("div");
    graphHeading.append(
        make("p", "study-map-ui-panel-eyebrow", "知识关系"),
        make("h2", "study-map-ui-panel-title", "知识关系图")
    );
    const graphCount = make("span", "study-map-ui-count", "正在加载");
    graphHeader.append(graphHeading, graphCount);
    const graphViewport = make("div", "study-map-ui-canvas");
    graphViewport.append(make("p", "study-map-ui-loading", "正在读取知识图谱…"));
    graphPanel.append(graphHeader, graphViewport);

    const detailsPanel = make("aside", "study-map-ui-details");
    detailsPanel.append(make("p", "study-map-ui-details-loading", "选择节点后显示详情。"));
    workspace.append(graphPanel, detailsPanel);
    panel.append(header, controls, workspace);
    root.replaceChildren(panel);

    const state = {
        graph: null,
        visibleNodes: [],
        visibleEdges: [],
        selectedId: null
    };
    const nodeElements = new Map();
    const edgeElements = [];

    const applyFilters = () => {
        if (!state.graph) {
            return;
        }
        const courseId = courseSelect.value;
        const term = searchInput.value.trim().toLocaleLowerCase("zh-CN");
        state.visibleNodes = state.graph.nodes
            .filter((node) => !courseId || node.courseId === courseId)
            .filter((node) => {
                if (!term) {
                    return true;
                }
                return [
                    node.label,
                    node.summary,
                    node.category,
                    courseLabel(node.courseId),
                    node.id
                ].some((value) => text(value).toLocaleLowerCase("zh-CN").includes(term));
            })
            .sort((left, right) =>
                left.layer - right.layer ||
                left.order - right.order ||
                collator.compare(left.label, right.label)
            );
        const visibleIds = new Set(state.visibleNodes.map((node) => node.id));
        state.visibleEdges = state.graph.edges.filter((edge) =>
            visibleIds.has(edge.source) && visibleIds.has(edge.target)
        );
        if (!visibleIds.has(state.selectedId)) {
            state.selectedId = state.visibleNodes[0]?.id ?? null;
        }
        graphCount.textContent = state.visibleNodes.length === state.graph.nodes.length
            ? `${state.graph.nodes.length} 个节点 · ${state.graph.edges.length} 条关系`
            : `${state.visibleNodes.length} / ${state.graph.nodes.length} 个节点`;
        renderGraph();
    };

    const selectNode = (nodeId, moveFocus = false) => {
        state.selectedId = nodeId;
        const connectedIds = new Set(nodeId ? [nodeId] : []);
        state.visibleEdges.forEach((edge) => {
            if (edge.source === nodeId || edge.target === nodeId) {
                connectedIds.add(edge.source);
                connectedIds.add(edge.target);
            }
        });
        nodeElements.forEach((element, id) => {
            const selected = id === nodeId;
            const muted = Boolean(nodeId) && !connectedIds.has(id);
            element.classList.toggle("is-selected", selected);
            element.classList.toggle("is-muted", muted);
            element.setAttribute("aria-pressed", String(selected));
        });
        edgeElements.forEach(({ element, edge }) => {
            const related = edge.source === nodeId || edge.target === nodeId;
            element.classList.toggle("is-related", related);
            element.classList.toggle("is-muted", Boolean(nodeId) && !related);
        });
        renderDetails(nodeId, moveFocus);
    };

    const renderGraph = () => {
        nodeElements.clear();
        edgeElements.length = 0;
        graphViewport.replaceChildren();
        if (!state.visibleNodes.length) {
            graphViewport.append(make(
                "p",
                "study-map-ui-empty",
                "当前筛选没有匹配的知识节点。"
            ));
            renderDetails(null);
            return;
        }

        const { positions, width, height } = computeLayout(state.visibleNodes);
        const svg = createSvgElement("svg", {
            class: "study-map-ui-svg",
            viewBox: `0 0 ${width} ${height}`,
            width,
            height,
            role: "group",
            "aria-label": "高等数学知识关系图"
        });
        const defs = createSvgElement("defs");
        const marker = createSvgElement("marker", {
            id: "study-map-arrow",
            markerWidth: 8,
            markerHeight: 8,
            refX: 7,
            refY: 4,
            orient: "auto",
            markerUnits: "strokeWidth"
        });
        marker.append(createSvgElement("path", {
            class: "study-map-ui-arrow",
            d: "M 0 0 L 8 4 L 0 8 Z"
        }));
        defs.append(marker);
        svg.append(defs);

        const edgeLayer = createSvgElement("g", { class: "study-map-ui-edges" });
        state.visibleEdges.forEach((edge) => {
            const source = positions.get(edge.source);
            const target = positions.get(edge.target);
            if (!source || !target) {
                return;
            }
            const path = createSvgElement("path", {
                class: `study-map-ui-edge study-map-ui-edge--${edge.kind}`,
                d: edgePath(source, target),
                "marker-end": "url(#study-map-arrow)"
            });
            const edgeTitle = createSvgElement("title");
            edgeTitle.textContent = `${edge.label}：${edge.source} → ${edge.target}`;
            path.append(edgeTitle);
            edgeElements.push({ element: path, edge });
            edgeLayer.append(path);
        });

        const nodeLayer = createSvgElement("g", { class: "study-map-ui-nodes" });
        state.visibleNodes.forEach((node) => {
            const position = positions.get(node.id);
            const categoryClass = CATEGORY_CLASSES[node.category] || "concept";
            const group = createSvgElement("g", {
                class: `study-map-ui-node study-map-ui-node--${categoryClass}`,
                transform: `translate(${position.x} ${position.y})`,
                tabindex: 0,
                role: "button",
                "aria-pressed": "false",
                "aria-label": `${node.label}，${node.category}，${courseLabel(node.courseId)}`
            });
            const nodeTitle = createSvgElement("title");
            nodeTitle.textContent = `${node.label}\n${node.summary}`;
            group.append(
                nodeTitle,
                createSvgElement("rect", {
                    class: "study-map-ui-node-card",
                    width: NODE_WIDTH,
                    height: NODE_HEIGHT,
                    rx: 8
                }),
                createSvgElement("rect", {
                    class: "study-map-ui-node-accent",
                    width: 4,
                    height: NODE_HEIGHT - 12,
                    x: 0,
                    y: 6,
                    rx: 2
                })
            );
            const label = createSvgElement("text", {
                class: "study-map-ui-node-label",
                x: 16,
                y: 27
            });
            label.textContent = shortLabel(node.label);
            const meta = createSvgElement("text", {
                class: "study-map-ui-node-meta",
                x: 16,
                y: 47
            });
            meta.textContent = `${node.category} · ${courseLabel(node.courseId)}`;
            group.append(label, meta);
            group.addEventListener("click", () => selectNode(node.id));
            group.addEventListener("keydown", (event) => {
                if (event.key === "Enter" || event.key === " ") {
                    event.preventDefault();
                    selectNode(node.id, true);
                }
            });
            nodeElements.set(node.id, group);
            nodeLayer.append(group);
        });
        svg.append(edgeLayer, nodeLayer);
        graphViewport.append(svg);
        selectNode(state.selectedId);
    };

    const sourceNames = () => new Map(
        (state.graph?.sources || [])
            .filter((source) => text(source?.id))
            .map((source) => [source.id, text(source.label, source.id)])
    );

    const renderDetails = (nodeId, moveFocus = false) => {
        if (!state.graph) {
            return;
        }
        detailsPanel.replaceChildren();
        const detailsHeader = make("header", "study-map-ui-details-header");
        const detailsIcon = make("span", "study-map-ui-details-icon");
        detailsIcon.innerHTML = context.icon("info", 17);
        detailsHeader.append(
            detailsIcon,
            make("p", "study-map-ui-panel-eyebrow", "节点详情")
        );
        detailsPanel.append(detailsHeader);

        const node = state.graph.nodes.find((candidate) => candidate.id === nodeId);
        if (!node) {
            detailsPanel.append(make(
                "p",
                "study-map-ui-details-empty",
                "选择图中的节点以查看课程、摘要、来源和相邻关系。"
            ));
            return;
        }

        const detailsTitle = make("div", "study-map-ui-details-title");
        detailsTitle.append(
            make("span", `study-map-ui-category study-map-ui-category--${CATEGORY_CLASSES[node.category] || "concept"}`, node.category),
            make("h2", "", node.label)
        );
        detailsPanel.append(
            detailsTitle,
            make("p", "study-map-ui-summary", node.summary)
        );

        const facts = make("dl", "study-map-ui-facts");
        const appendFact = (label, value) => {
            facts.append(
                make("dt", "", label),
                make("dd", "", value)
            );
        };
        appendFact("课程", courseLabel(node.courseId));
        appendFact("层级", `第 ${node.layer + 1} 层`);
        appendFact("顺序", String(node.order + 1));
        detailsPanel.append(facts);

        const names = sourceNames();
        const sourceList = make("div", "study-map-ui-source-list");
        (Array.isArray(node.sourceIds) ? node.sourceIds : []).forEach((sourceId) => {
            sourceList.append(make(
                "span",
                "study-map-ui-source",
                names.get(sourceId) || sourceId
            ));
        });
        detailsPanel.append(
            make("p", "study-map-ui-section-label", "数据来源"),
            sourceList
        );

        const relatedEdges = state.visibleEdges.filter(
            (edge) => edge.source === node.id || edge.target === node.id
        );
        detailsPanel.append(make("p", "study-map-ui-section-label", "相邻知识点"));
        if (!relatedEdges.length) {
            detailsPanel.append(make("p", "study-map-ui-details-empty", "当前视图中没有相邻节点。"));
            return;
        }
        const relationList = make("div", "study-map-ui-relations");
        relatedEdges.forEach((edge) => {
            const outgoing = edge.source === node.id;
            const otherId = outgoing ? edge.target : edge.source;
            const other = state.graph.nodes.find((candidate) => candidate.id === otherId);
            if (!other) {
                return;
            }
            const button = make("button", "study-map-ui-relation");
            button.type = "button";
            button.append(
                make("span", "study-map-ui-relation-label", edge.label),
                make(
                    "span",
                    "study-map-ui-relation-target",
                    `${outgoing ? "指向" : "来自"} ${other.label}`
                )
            );
            button.addEventListener("click", () => {
                selectNode(other.id, true);
                nodeElements.get(other.id)?.focus();
            });
            relationList.append(button);
        });
        detailsPanel.append(relationList);

        if (moveFocus) {
            nodeElements.get(node.id)?.focus();
        }
    };

    courseSelect.addEventListener("change", applyFilters);
    searchInput.addEventListener("input", applyFilters);

    try {
        const graph = normalizeGraph(await context.query("study.map.read"));
        if (!graph.nodes.length) {
            throw new Error("模块没有返回可展示的知识节点。");
        }
        state.graph = graph;
        state.selectedId = graph.nodes[0].id;
        title.textContent = graph.title;
        description.textContent = graph.description;

        const courseIds = [...new Set(graph.nodes.map((node) => node.courseId))]
            .sort((left, right) => collator.compare(courseLabel(left), courseLabel(right)));
        const allCourses = make("option", "", "全部课程");
        allCourses.value = "";
        courseSelect.append(allCourses);
        courseIds.forEach((courseId) => {
            const option = make("option", "", courseLabel(courseId));
            option.value = courseId;
            courseSelect.append(option);
        });
        courseSelect.disabled = false;
        applyFilters();
    } catch (error) {
        graphViewport.replaceChildren(make(
            "p",
            "study-map-ui-error",
            error?.message || "知识图谱读取失败。"
        ));
        detailsPanel.replaceChildren(make(
            "p",
            "study-map-ui-error",
            "节点详情暂不可用。"
        ));
        graphCount.textContent = "读取失败";
    }
}
