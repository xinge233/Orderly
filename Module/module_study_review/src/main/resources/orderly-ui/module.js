/**
 * 复习自测只读视图。
 *
 * 职责：按课程筛选模块自带题目，展示题干、参考提纲和仅限当前页面的临时作答/自评状态。
 * 依赖：orderly-ui/module.json 声明视图与卡片；唯一数据查询为 study.review.read；
 * 宿主 mount context 提供 module、element、icon、query 与可选 toast。
 * 隔离：无效负载或查询失败只影响当前复习视图；作答与标记不写 localStorage、Query 或模块业务集合。
 */

const COURSE_LABELS = Object.freeze({
    calculus: "高等数学",
    "linear-algebra": "线性代数",
    "data-structures": "数据结构"
});

/**
 * 挂载复习自测视图。
 *
 * @param {HTMLElement} root 框架分配的模块视图根节点。
 * @param {object} context 宿主 mount context；module 提供标题，query 读取只读题目。
 * @returns {Promise<void>} 首次题目查询和局部渲染完成后结束。
 */
export default async function mount(root, context) {
    const make = context.element;
    // Map 只承载本次挂载期间的演示状态；框架替换视图后由浏览器随闭包一起释放。
    const answers = new Map();
    const marks = new Map();
    let questions = [];
    let selectedCourse = "all";
    let selectedId = null;

    const panel = make("section", "study-review-ui");
    const header = make("header", "study-review-ui-header");
    const heading = make("div", "study-review-ui-heading");
    heading.append(
        make("p", "study-review-ui-eyebrow", "MODULE STUDY REVIEW"),
        make("h1", "", context.module.title || "复习自测"),
        make("p", "study-review-ui-lead",
            "课程复习题与作答区。当前为只读预览，不自动阅卷。")
    );

    // 生成自测清单不在本模块能力内，用禁用状态和说明文字维持诚实性边界。
    const generate = make("button", "study-review-ui-button study-review-ui-generate");
    generate.type = "button";
    generate.disabled = true;
    generate.setAttribute("aria-disabled", "true");
    generate.title = "自测清单生成尚未接入";
    generate.append(
        icon("spark"),
        make("span", "", "生成自测清单"),
        make("small", "", "尚未接入")
    );
    header.append(heading, generate);

    const notice = make("div", "study-review-ui-notice");
    notice.append(icon("info"));
    const noticeCopy = make("p");
    noticeCopy.append(
        make("strong", "", "仅限本次页面演示："),
        make("span", "",
            "作答和“掌握/薄弱”标记只在当前页面生效，刷新即清空，不持久化、不写回。")
    );
    notice.append(noticeCopy);

    const controls = make("div", "study-review-ui-controls");
    const courseField = make("label", "study-review-ui-course");
    courseField.htmlFor = "study-review-course";
    courseField.append(make("span", "", "课程范围"));
    const courseSelect = make("select");
    courseSelect.id = "study-review-course";
    courseSelect.disabled = true;
    courseField.append(courseSelect);

    const progress = make("div", "study-review-ui-progress");
    const progressHeading = make("div", "study-review-ui-progress-heading");
    const progressTitle = make("strong", "", "复习进度");
    const progressValue = make("span", "", "0 / 0");
    progressHeading.append(progressTitle, progressValue);
    const progressTrack = make("div", "study-review-ui-progress-track");
    progressTrack.setAttribute("role", "progressbar");
    progressTrack.setAttribute("aria-label", "课程复习进度");
    progressTrack.setAttribute("aria-valuemin", "0");
    progressTrack.setAttribute("aria-valuemax", "0");
    progressTrack.setAttribute("aria-valuenow", "0");
    const progressFill = make("span", "study-review-ui-progress-fill");
    progressTrack.append(progressFill);
    const progressMeta = make("p", "study-review-ui-progress-meta", "正在读取题目…");
    progress.append(progressHeading, progressTrack, progressMeta);
    controls.append(courseField, progress);

    const workspace = make("div", "study-review-ui-workspace");
    const listPane = make("section", "study-review-ui-list-pane");
    const listHeader = make("div", "study-review-ui-pane-heading");
    const listTitle = make("h2", "", "题目列表");
    const listCount = make("span", "", "读取中");
    listHeader.append(listTitle, listCount);
    const questionList = make("div", "study-review-ui-question-list");
    questionList.append(make("p", "study-review-ui-empty", "正在读取模块数据…"));
    listPane.append(listHeader, questionList);

    const detailPane = make("section", "study-review-ui-detail-pane");
    detailPane.append(
        make("h2", "study-review-ui-detail-title", "题目详情"),
        make("p", "study-review-ui-empty", "选择题目后查看详情与作答区。")
    );
    workspace.append(listPane, detailPane);

    panel.append(header, notice, controls, workspace);
    root.replaceChildren(panel);

    courseSelect.addEventListener("change", () => {
        // 切换课程后先修正选中项，再统一刷新列表、详情和进度，避免三个区域引用不同题目。
        selectedCourse = courseSelect.value;
        ensureSelection();
        renderList();
        renderDetail();
        renderProgress();
    });

    // review 负载必须同时声明只读、无阅卷模式和题目数组，避免把其他查询结果误渲染为题目。
    try {
        const studyData = await context.query("study.review.read");
        if (studyData?.kind !== "review"
            || studyData?.readOnly !== true
            || studyData?.payload?.gradingMode !== "none"
            || !Array.isArray(studyData?.payload?.questions)) {
            throw new Error("复习数据不是有效的只读 review 负载");
        }

        questions = studyData.payload.questions;
        populateCourses();
        ensureSelection();
        courseSelect.disabled = false;
        renderList();
        renderDetail();
        renderProgress();
    } catch (error) {
        const message = error?.message || "复习题目读取失败";
        listCount.textContent = "读取失败";
        questionList.replaceChildren(make("p", "study-review-ui-error", message));
        detailPane.replaceChildren(
            make("h2", "study-review-ui-detail-title", "无法载入复习题"),
            make("p", "study-review-ui-error", message)
        );
        progressMeta.textContent = "复习数据不可用";
        context.toast?.(message, { error: true });
    }

    function icon(name) {
        const holder = make("span", "study-review-ui-icon");
        holder.innerHTML = context.icon(name, 17);
        return holder;
    }

    function courseLabel(courseId) {
        return COURSE_LABELS[courseId] || courseId;
    }

    function filteredQuestions() {
        return selectedCourse === "all"
            ? questions
            : questions.filter((question) => question.courseId === selectedCourse);
    }

    function populateCourses() {
        const counts = new Map();
        questions.forEach((question) => {
            counts.set(question.courseId, (counts.get(question.courseId) || 0) + 1);
        });
        const options = [make("option", "", `全部课程（${questions.length}）`)];
        options[0].value = "all";
        counts.forEach((count, courseId) => {
            const option = make("option", "", `${courseLabel(courseId)}（${count}）`);
            option.value = courseId;
            options.push(option);
        });
        courseSelect.replaceChildren(...options);
        courseSelect.value = "all";
    }

    function ensureSelection() {
        const visible = filteredQuestions();
        // 保留仍可见的选中项；仅在筛选将当前题隐藏时回退到首题或无选择。
        if (!visible.some((question) => question.id === selectedId)) {
            selectedId = visible[0]?.id ?? null;
        }
    }

    function renderList() {
        const visible = filteredQuestions();
        listCount.textContent = `${visible.length} 道`;
        questionList.replaceChildren();
        if (!visible.length) {
            questionList.append(make("p", "study-review-ui-empty", "当前课程范围没有题目。"));
            return;
        }

        visible.forEach((question) => {
            const mark = marks.get(question.id);
            const button = make("button", "study-review-ui-question");
            button.type = "button";
            button.dataset.questionId = question.id;
            button.classList.toggle("is-selected", question.id === selectedId);
            button.classList.toggle("is-mastered", mark === "mastered");
            button.classList.toggle("is-weak", mark === "weak");

            const marker = make("span", "study-review-ui-question-icon");
            marker.innerHTML = context.icon(
                mark === "mastered" ? "check" : mark === "weak" ? "info" : "file",
                18
            );
            const copy = make("span", "study-review-ui-question-copy");
            copy.append(
                make("strong", "", question.knowledgePoint || "未命名知识点"),
                make("small", "",
                    `${courseLabel(question.courseId)} · ${question.chapter}`)
            );
            const status = make(
                "span",
                "study-review-ui-question-status",
                mark === "mastered" ? "掌握" : mark === "weak" ? "薄弱" : question.difficulty
            );
            button.append(marker, copy, status);
            button.addEventListener("click", () => {
                selectedId = question.id;
                // 列表会整体重建，因此渲染后按稳定题目 ID 恢复键盘焦点。
                renderList();
                renderDetail();
                const nextButton = [...questionList.querySelectorAll("[data-question-id]")]
                    .find((node) => node.dataset.questionId === question.id);
                nextButton?.focus();
            });
            questionList.append(button);
        });
    }

    function renderDetail() {
        const question = questions.find((item) => item.id === selectedId);
        detailPane.replaceChildren();
        if (!question) {
            detailPane.append(
                make("h2", "study-review-ui-detail-title", "题目详情"),
                make("p", "study-review-ui-empty", "当前课程范围没有可查看的题目。")
            );
            return;
        }

        const mark = marks.get(question.id);
        const route = make("p", "study-review-ui-detail-route",
            `${courseLabel(question.courseId)} · ${question.chapter}`);
        const title = make("h2", "study-review-ui-detail-title",
            question.knowledgePoint || "复习题");
        const tags = make("div", "study-review-ui-tags");
        tags.append(
            make("span", "study-review-ui-tag", question.difficulty),
            make("span", "study-review-ui-tag", "只读题目"),
            make("span", `study-review-ui-tag ${mark ? `is-${mark}` : ""}`,
                mark === "mastered" ? "已标为掌握" : mark === "weak" ? "已标为薄弱" : "尚未标记")
        );
        const prompt = make("p", "study-review-ui-prompt", question.prompt);

        const answerField = make("label", "study-review-ui-answer-field");
        const answerHeading = make("span", "study-review-ui-answer-heading");
        answerHeading.append(
            make("strong", "", "我的作答"),
            make("small", "", "仅本地演示，不保存、不写回")
        );
        const textarea = make("textarea", "study-review-ui-answer");
        textarea.rows = 7;
        textarea.placeholder = "写下你的思路或答案…";
        textarea.value = answers.get(question.id) || "";
        // 输入只更新内存 Map，不触发 Query/Action，也不持久化到浏览器或模块集合。
        textarea.addEventListener("input", (event) => {
            answers.set(question.id, event.target.value);
            renderProgress();
        });
        answerField.append(answerHeading, textarea);

        const markRow = make("div", "study-review-ui-mark-row");
        const markCopy = make("div", "study-review-ui-mark-copy");
        markCopy.append(
            make("strong", "", "自评掌握程度"),
            make("small", "", "点击后立即更新本页进度，刷新即清空。")
        );
        const markActions = make("div", "study-review-ui-mark-actions");
        const mastered = markButton("标为掌握", "check", "mastered", question.id);
        const weak = markButton("标为薄弱", "info", "weak", question.id);
        markActions.append(mastered, weak);
        markRow.append(markCopy, markActions);

        const outline = make("details", "study-review-ui-outline");
        outline.append(
            make("summary", "", "查看参考提纲（不会自动阅卷）"),
            make("p", "", question.answerOutline || "本题暂无参考提纲。")
        );

        detailPane.append(route, title, tags, prompt, answerField, markRow, outline);
    }

    function markButton(label, iconName, mark, questionId) {
        // aria-pressed 与视觉状态共用当前临时标记，再次点击同一标记即取消。
        const button = make("button", `study-review-ui-mark is-${mark}`);
        button.type = "button";
        button.dataset.mark = mark;
        button.classList.toggle("is-active", marks.get(questionId) === mark);
        button.setAttribute("aria-pressed", String(marks.get(questionId) === mark));
        button.append(icon(iconName), make("span", "", label));
        button.addEventListener("click", () => {
            if (marks.get(questionId) === mark) {
                marks.delete(questionId);
                context.toast?.("已取消本次临时标记，未持久化。");
            } else {
                marks.set(questionId, mark);
                context.toast?.(`已临时标记为${mark === "mastered" ? "掌握" : "薄弱"}，未持久化。`);
            }
            renderList();
            renderDetail();
            renderProgress();
            // 详情重建后恢复原按钮焦点，保证键盘用户不会丢失操作位置。
            const nextButton = [...detailPane.querySelectorAll("[data-mark]")]
                .find((node) => node.dataset.mark === mark);
            nextButton?.focus();
        });
        return button;
    }

    function renderProgress() {
        const visible = filteredQuestions();
        const mastered = visible.filter((question) => marks.get(question.id) === "mastered").length;
        const weak = visible.filter((question) => marks.get(question.id) === "weak").length;
        const completed = visible.filter((question) => {
            const answer = (answers.get(question.id) || "").trim();
            return answer.length > 0 || marks.has(question.id);
        }).length;
        const total = visible.length;
        const percentage = total ? Math.round((completed / total) * 100) : 0;
        // 进度条同时更新可见填充宽度与 ARIA 数值，二者都以当前筛选范围为准。
        progressFill.style.width = `${percentage}%`;
        progressValue.textContent = `${completed} / ${total}`;
        progressTrack.setAttribute("aria-valuemax", String(total));
        progressTrack.setAttribute("aria-valuenow", String(completed));
        progressMeta.textContent = total
            ? `已复习 ${percentage}% · 掌握 ${mastered} · 薄弱 ${weak} · 仅本次预览`
            : "当前课程范围没有题目。";
    }
}
