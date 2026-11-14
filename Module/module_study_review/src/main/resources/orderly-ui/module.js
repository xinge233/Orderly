const COURSE_LABELS = Object.freeze({
    calculus: "高等数学",
    "linear-algebra": "线性代数",
    "data-structures": "数据结构"
});

export default async function mount(root, context) {
    const make = context.element;
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
        selectedCourse = courseSelect.value;
        ensureSelection();
        renderList();
        renderDetail();
        renderProgress();
    });

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
        progressFill.style.width = `${percentage}%`;
        progressValue.textContent = `${completed} / ${total}`;
        progressTrack.setAttribute("aria-valuemax", String(total));
        progressTrack.setAttribute("aria-valuenow", String(completed));
        progressMeta.textContent = total
            ? `已复习 ${percentage}% · 掌握 ${mastered} · 薄弱 ${weak} · 仅本次预览`
            : "当前课程范围没有题目。";
    }
}
