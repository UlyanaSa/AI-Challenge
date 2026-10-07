// Страница дня 22: два агента на десяти контрольных вопросах.
//
// Данных у страницы два источника, и оба приходят с сервера целиком. Набор вопросов — это setup:
// он не меняется и содержит ожидания, факты и место в книге. Состояние прогона — это state:
// его страница опрашивает и рисует из него таблицу, метрики и конвейеры.
//
// Страница ничего не считает сама: оценки, попадание источника и итоговые метрики приходят
// посчитанными. Второй набор формул в браузере разошёлся бы с отчётом прогона — а сверять
// страницу с отчётом тогда было бы нечем. Здесь только раскладка: что показать и в каком порядке.

let setup = null;
let state = null;
let selected = null;
let signature = "";

// Раскрытые блоки: перерисовка не должна схлопывать то, что человек открыл, чтобы прочитать.
const openKeys = new Set();

document.addEventListener(
    "toggle",
    (event) => {
        const details = event.target.closest("details[data-key]");
        if (!details) return;
        if (details.open) openKeys.add(details.dataset.key);
        else openKeys.delete(details.dataset.key);
    },
    true
);

async function api(path, options) {
    const response = await fetch(path, options);
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.message || `Запрос не удался: ${response.status}`);
    return body;
}

function esc(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;");
}

function seconds(millis) {
    return `${(millis / 1000).toFixed(1).replace(".", ",")} с`;
}

function tokens(count) {
    return count.toLocaleString("ru-RU");
}

// Оценка показывается по шкале задания — «N из 2», а не «N из числа фактов»: у вопроса
// без ответа в базе факт один, и «2 / 1» читалось бы как ошибка счёта, хотя это полный балл.
function scoreBadge(score) {
    return `<span class="badge score-${score}">${score} / 2</span>`;
}

function outcomeLabel(outcome) {
    return outcome ? `<span class="outcome">${esc(outcome)}</span>` : "";
}

function fold(key, summary, bodyHtml) {
    const open = openKeys.has(key) ? " open" : "";
    return `<details class="fold" data-key="${esc(key)}"${open}><summary>${esc(summary)}</summary>${bodyHtml}</details>`;
}

function stage(title, bodyHtml, time) {
    return `<div class="stage">
        <div class="stage-title"><span>${esc(title)}</span>${time ? `<span class="time">${esc(time)}</span>` : ""}</div>
        <div class="stage-body">${bodyHtml}</div>
    </div>`;
}

function questionRow(question, result) {
    const without = result?.without;
    const withRag = result?.with;
    const absent = question.absent;
    const expectedClass = absent ? "expected absent" : "expected";
    const place = absent
        ? "ответа в базе нет — правильным считается признать нехватку сведений"
        : `${esc(question.section)}, страницы ${question.pages.join(", ")}`;
    return `<tr class="question-row${selected === question.id ? " selected" : ""}" data-id="${question.id}">
        <td class="mono">${esc(question.id)}</td>
        <td>
            <span class="question-text">${esc(question.question)}</span>
            <span class="question-meta">${place} · ${esc(question.note)}</span>
            <div class="${expectedClass}"><b>Ожидание:</b> ${esc(question.expected)}</div>
        </td>
        <td>${without ? scoreBadge(without.score) + outcomeLabel(without.outcome) : "—"}</td>
        <td>${withRag ? scoreBadge(withRag.score) + outcomeLabel(withRag.outcome) : "—"}</td>
        <td>${absent || !withRag ? "—" : withRag.hit ? "да" : "нет"}</td>
        <td>${withRag ? esc(withRag.outcome) : "не прогонялся"}</td>
        <td><button class="mini secondary run-one" data-id="${question.id}" type="button"${setup.keyNote ? " disabled" : ""}>Прогнать</button></td>
    </tr>`;
}

function factList(mode) {
    const items = mode.facts
        .map(
            (fact) =>
                `<li class="${fact.matched ? "yes" : "no"}">${fact.matched ? "✓" : "✗"} ${esc(fact.text)}</li>`
        )
        .join("");
    return `<ul class="fact-list">${items}</ul>`;
}

function sourceTable(mode) {
    if (!mode.sources.length) {
        return `<p class="missing">Поиск не вернул ни одного фрагмента.</p>`;
    }
    const rows = mode.sources
        .map((source) => {
            const place = [source.source, source.section, source.pages.length ? `стр. ${source.pages.join(", ")}` : null]
                .filter(Boolean)
                .join(" · ");
            return `<tr>
                <td class="mono">[${source.rank}]</td>
                <td><span class="mono">${esc(source.id)}</span><br><span class="question-meta">${esc(place)}</span></td>
                <td class="similarity">${source.similarity.toFixed(3).replace(".", ",")}</td>
                <td>${tokens(source.tokens)} токенов</td>
            </tr>`;
        })
        .join("");
    return `<table class="sources">
        <thead><tr><th>#</th><th>чанк и место в книге</th><th>близость</th><th>размер</th></tr></thead>
        <tbody>${rows}</tbody></table>
        ${mode.sources
            .map((source) =>
                fold(
                    `source-${mode.mode}-${source.rank}`,
                    `текст фрагмента [${source.rank}] — ${source.chars} символов`,
                    `<pre>${esc(source.text)}</pre>`
                )
            )
            .join("")}`;
}

function answerStage(mode) {
    const meta = [
        // Проверка на `== null`, а не `=== null`: сервер не пишет пустые поля в JSON, и «поля нет»
        // здесь означает то же, что «поле пустое», — иначе прочерк превращался бы в ноль.
        mode.promptTokens == null ? null : `запрос ${tokens(mode.promptTokens)} токенов`,
        mode.completionTokens == null ? null : `ответ ${tokens(mode.completionTokens)} токенов`,
        mode.finishReason ? `остановка ${mode.finishReason}` : null,
        seconds(mode.elapsedMillis),
    ]
        .filter(Boolean)
        .join(" · ");
    const text = mode.text.trim()
        ? `<pre>${esc(mode.text)}</pre>`
        : `<p class="missing">Ответ пустой: модель не сказала ничего.</p>`;
    return stage("Ответ модели", text, meta);
}

function checkStage(mode) {
    const cited = [
        mode.citedFragments.length ? `названы фрагменты ${mode.citedFragments.map((n) => `[${n}]`).join(", ")}` : "фрагменты не названы",
        mode.citedPages.length ? `страницы ${mode.citedPages.join(", ")}` : "страницы не названы",
    ].join(" · ");
    // Попадание источника считается только там, где поиск был: у режима без базы его нет,
    // а у вопроса без ответа в базе искать нечего — и там и там это «не считается», не «нет».
    const hit =
        mode.mode === "without"
            ? "поиска не было — попадание источника не считается"
            : mode.hit == null
              ? "вопрос без ответа в базе — попадание источника не считается"
              : mode.hit
                ? "ожидаемый текст был в выдаче поиска"
                : "ожидаемого текста в выдаче поиска не было";
    const matched = mode.facts.filter((fact) => fact.matched).length;
    return stage(
        "Сверка с ожиданием",
        `${factList(mode)}<p class="cited">${esc(hit)}<br>${esc(cited)}</p>`,
        `оценка ${mode.score} из 2 · фактов ${matched} из ${mode.total}`
    );
}

function agentColumn(mode, question) {
    if (!mode) {
        return `<div class="agent"><div class="agent-head"><h3>Режим не прогонялся</h3></div>
            <p class="missing">Нажмите «Прогнать» у вопроса — появятся оба конвейера.</p></div>`;
    }
    const head = `<div class="agent-head"><h3>${esc(mode.title)}</h3>
        <span>${scoreBadge(mode.score)}${outcomeLabel(mode.outcome)}</span></div>`;
    if (mode.state === "failed") {
        const stages =
            mode.mode === "with"
                ? stage("Поиск", `<p class="missing">Отказ случился до выдачи: поиск не выполнялся.</p>`)
                : stage("Поиск", `<p class="missing">Режим без RAG к поиску не обращается.</p>`);
        return `<div class="agent${mode.mode === "with" ? " with" : ""}">${head}${stages}
            <div class="stage"><div class="error-box">${esc(mode.error || "режим отказал")}</div></div></div>`;
    }

    const stages = [stage("Вопрос", esc(question.question))];
    if (mode.mode === "with") {
        stages.push(
            stage(
                "Вектор вопроса",
                `<p>${mode.queryDimension} чисел — считает ${esc(setup.provider.name)}</p>
                 <p class="question-meta">Время вектора входит в общее время поиска ниже: это одно звено.</p>`
            )
        );
        stages.push(
            stage(
                "Поиск по индексу",
                `<p class="mono">${esc(state.index.file)} — ${tokens(state.index.chunks)} чанков, Top-K = ${mode.sources.length}</p>
                 <p class="question-meta">Индекс взят ${state.index.state === "ready" ? "готовым" : "из сборки"} — переиндексации при запросе нет.</p>`,
                seconds(mode.retrievalMillis)
            )
        );
        stages.push(stage("Top-K", sourceTable(mode)));
    } else {
        stages.push(
            stage(
                "Поиска не было",
                `<p class="missing">Режим без базы не обращается к индексу: модель отвечает по памяти.</p>`
            )
        );
    }
    const user = mode.messages.find((message) => message.role === "user");
    const system = mode.messages.find((message) => message.role === "system");
    if (user) {
        stages.push(
            stage(
                mode.mode === "with" ? "Контекст, ушедший в модель" : "Запрос к модели",
                fold(
                    `context-${mode.mode}`,
                    `показать текст запроса — ${user.content.length} символов`,
                    `<pre>${esc(user.content)}</pre>`
                )
            )
        );
    }
    if (system) {
        stages.push(stage("Системное правило", `<pre>${esc(system.content)}</pre>`));
    }
    stages.push(answerStage(mode));
    stages.push(checkStage(mode));
    return `<div class="agent${mode.mode === "with" ? " with" : ""}">${head}${stages.join("")}</div>`;
}

function renderPipeline() {
    const element = document.getElementById("pipeline");
    const hint = document.getElementById("pipeline-hint");
    const question = setup.questions.find((item) => item.id === selected);
    if (!question) {
        hint.hidden = false;
        element.innerHTML = "";
        return;
    }
    const result = state.results.find((item) => item.id === selected);
    hint.hidden = true;
    element.innerHTML = agentColumn(result?.without, question) + agentColumn(result?.with, question);
}

function renderMetrics() {
    const panel = document.getElementById("metrics-panel");
    const summary = state.summary;
    if (!summary) {
        panel.hidden = true;
        return;
    }
    panel.hidden = false;
    const run = state.done;
    document.getElementById("metrics-hint").textContent =
        `Метрики посчитаны по прогнанным вопросам: ${run} из ${setup.questions.length}. ` +
        `Вопросы без ответа в базе (${setup.questions.length - summary.scored}) в Source Hit Rate не входят.`;
    const metric = (title, value, note) =>
        `<div class="metric"><dt>${esc(title)}</dt><dd>${value}${note ? `<small>${esc(note)}</small>` : ""}</dd></div>`;
    document.getElementById("metrics").innerHTML = [
        metric(
            "Средняя оценка без RAG",
            `${summary.averageWithout.toFixed(1).replace(".", ",")} из 2`,
            `${summary.factsWithout} из ${summary.factsTotal} фактов`
        ),
        metric(
            "Средняя оценка с RAG",
            `${summary.averageWith.toFixed(1).replace(".", ",")} из 2`,
            `${summary.factsWith} из ${summary.factsTotal} фактов`
        ),
        metric(
            `Source Hit Rate (Top-${document.getElementById("topk").value})`,
            `${summary.hits} из ${summary.scored}`,
            `${Math.round(summary.hitRate * 100)}% — ожидаемый источник попал в выдачу`
        ),
        metric(
            "По вопросам",
            `+${summary.better} / =${summary.same} / −${summary.worse}`,
            "с RAG выше, столько же, ниже"
        ),
        metric(
            "Итог",
            `${summary.answered} верно`,
            `ошибок поиска ${summary.retrievalErrors}, ошибок генерации ${summary.generationErrors}`
        ),
        metric(
            "Токены",
            `${tokens(summary.without.prompt + summary.without.completion)} / ${tokens(summary.with.prompt + summary.with.completion)}`,
            "без RAG / с RAG, запрос + ответ"
        ),
        metric(
            "Время",
            `${seconds(summary.millisWithout)} / ${seconds(summary.millisWith)}`,
            `без RAG / с RAG; модель и поиск, без RAG поиска не было`
        ),
    ].join("");
}

function renderStatus() {
    const status = document.getElementById("state-status");
    const index = state.index;
    const parts = [];
    parts.push(
        index.state === "building"
            ? `Индекс собирается: ${tokens(index.chunks)} из ${tokens(index.total)} чанков`
            : index.state === "ready"
              ? `Индекс готов: ${tokens(index.chunks)} чанков (${esc(index.strategy)})`
              : "Индекс не собран: он соберётся при первом прогоне"
    );
    if (state.state === "running") {
        parts.push(`Прогон идёт: ${state.stageTitle ? esc(state.stageTitle) : "подготовка"} (${state.done} из ${state.total})`);
    } else if (state.state === "done") {
        parts.push(`Прогон закончен за ${seconds(state.elapsedMs)}: ${state.done} из ${state.total} вопросов`);
    } else {
        parts.push("Прогон не запускался");
    }
    status.innerHTML = parts.join(" · ");
    status.className = "status" + (state.error ? " error" : state.state === "done" ? " done" : "");

    const running = state.state === "running" || index.state === "building";
    const bar = document.getElementById("progress-wrap");
    bar.hidden = !running;
    if (running) {
        const percent = index.state === "building"
            ? (index.total ? (index.chunks * 100) / index.total : 0)
            : (state.total ? (state.done * 100) / state.total : 0);
        document.getElementById("progress-bar").style.width = `${Math.round(percent)}%`;
    }

    const reports = document.getElementById("reports");
    reports.hidden = state.reports.length === 0;
    reports.innerHTML = state.reports
        .map((report) => `<li>${esc(report.name)}: <span class="mono">${esc(report.file)}</span></li>`)
        .join("");

    const statusNote = document.getElementById("key-note");
    if (state.error) {
        statusNote.hidden = false;
        statusNote.textContent = state.error;
    } else if (setup.keyNote) {
        statusNote.hidden = false;
        statusNote.textContent = setup.keyNote;
    } else {
        statusNote.hidden = true;
    }
}

function renderQuestions() {
    document.getElementById("questions-body").innerHTML = setup.questions
        .map((question) =>
            questionRow(question, state.results.find((result) => result.id === question.id))
        )
        .join("");
}

function render() {
    renderStatus();
    renderQuestions();
    renderMetrics();
    renderPipeline();
}

function renderSetup() {
    document.getElementById("run-title").textContent =
        `Набор: ${setup.questions.length} контрольных вопросов. Модель: ${setup.model}. ` +
        `Векторы: ${setup.provider.name}. Каталог прогона: ${setup.dir}`;
    document.getElementById("topk").value = setup.topK;
    document.getElementById("topk").max = setup.maxTopK;
    document.getElementById("setup-facts").innerHTML = [
        ["База", `${setup.base.file} — ${tokens(setup.base.chars)} символов, ${tokens(setup.base.tokens)} токенов, ${setup.base.pages} страниц`],
        ["Нарезка", `${setup.index.strategy} — ${tokens(setup.index.chunks)} чанков`],
        ["Векторы", `${setup.provider.name} — ${setup.provider.dimension} измерений`],
        ["Провайдер", setup.provider.note],
        ["Поиск", `Top-K = ${setup.topK} (можно изменить перед прогоном)`],
    ]
        .map(([term, value]) => `<div><dt>${esc(term)}</dt><dd>${esc(value)}</dd></div>`)
        .join("");
    const canRun = !setup.keyNote;
    document.getElementById("run-all").disabled = !canRun;
    for (const button of document.querySelectorAll(".run-one")) button.disabled = !canRun;
}

async function refresh() {
    state = await api("/api/state");
    const next = JSON.stringify({ state, selected });
    if (next !== signature) {
        signature = next;
        render();
    }
}

async function run(ids) {
    try {
        const topK = Number(document.getElementById("topk").value) || setup.topK;
        state = await api(`/api/run?ids=${ids.join(",")}&topK=${topK}`, { method: "POST" });
        signature = "";
        render();
    } catch (cause) {
        alert(cause.message);
    }
}

document.getElementById("run-all").addEventListener("click", () => run([]));
document.getElementById("rebuild").addEventListener("click", async () => {
    try {
        state = await api("/api/index", { method: "POST" });
        signature = "";
        render();
    } catch (cause) {
        alert(cause.message);
    }
});
document.addEventListener("click", (event) => {
    const runButton = event.target.closest(".run-one");
    if (runButton) {
        event.stopPropagation();
        run([runButton.dataset.id]);
        return;
    }
    const row = event.target.closest("tr.question-row");
    if (row) {
        selected = row.dataset.id;
        signature = "";
        render();
    }
});

async function main() {
    setup = await api("/api/setup");
    renderSetup();
    await refresh();
    setInterval(() => refresh().catch(() => {}), 1200);
}

main().catch((cause) => {
    document.getElementById("state-status").className = "status error";
    document.getElementById("state-status").textContent = `Страница не загрузилась: ${cause.message}`;
});
