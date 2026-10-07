// Страница дня 23: три режима ответа и разбор этапов конвейера на десяти контрольных вопросах.
//
// Данных у страницы два источника, и оба приходят с сервера целиком. Набор вопросов — это setup:
// он не меняется и содержит ожидания, факты, место в книге и начальные настройки этапов. Состояние
// прогона — это state: его страница опрашивает и рисует из него таблицу, метрики, конвейеры и трейс.
//
// Страница ничего не считает сама: оценки, попадания, метрики этапов и классификацию потерь считает
// `:rag`, а сюда они приходят посчитанными. Второй набор формул в браузере разошёлся бы с отчётом
// прогона — а сверять страницу с отчётом тогда было бы нечем. Здесь только раскладка.

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

function ru(value, digits) {
    return value.toFixed(digits).replace(".", ",");
}

function thresholdLabel(value) {
    return value <= 0 ? "без порога" : `similarity >= ${ru(value, 2)}`;
}

// Оценка показывается по шкале задания — «N из 2», а не «N из числа фактов»: у вопроса
// без ответа в базе факт один, и «2 / 1» читалось бы как ошибка счёта, хотя это полный балл.
function scoreBadge(score) {
    return `<span class="badge score-${score}">${score} / 2</span>`;
}

function outcomeLabel(outcome) {
    return outcome ? `<span class="outcome">${esc(outcome)}</span>` : "";
}

// Класс потери: не найден поиском — ошибка поиска (красный), отсечён порогом или вторым этапом —
// цена этапа (жёлтый), дошёл — зелёный. Это то, что задание просит называть точно.
function lossBadge(check) {
    if (!check) return `<span class="question-meta">—</span>`;
    const kind = { NONE: "good", RETRIEVAL: "bad", FILTER: "warn", RERANK: "warn" }[check.lossCode] || "";
    return `<span class="loss loss-${kind}">${esc(check.loss)}</span>`;
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
    const baseline = result?.baseline;
    const improved = result?.improved;
    const check = result?.stageCheck;
    const absent = question.absent;
    const expectedClass = absent ? "expected absent" : "expected";
    const place = absent
        ? "ответа в базе нет — правильным считается признать нехватку сведений"
        : `${esc(question.section)}, страницы ${question.pages.join(", ")}`;
    // «Потеря» показывается только у вопроса с ответом в базе: у вопроса без ответа терять нечего.
    const loss = !check || absent ? `<span class="question-meta">—</span>` : lossBadge(check);
    const hit = !check || absent ? "—" : check.inFinal ? "да" : "нет";
    return `<tr class="question-row${selected === question.id ? " selected" : ""}" data-id="${question.id}">
        <td class="mono">${esc(question.id)}</td>
        <td>
            <span class="question-text">${esc(question.question)}</span>
            <span class="question-meta">${place} · ${esc(question.note)}</span>
            <div class="${expectedClass}"><b>Ожидание:</b> ${esc(question.expected)}</div>
        </td>
        <td>${without ? scoreBadge(without.score) + outcomeLabel(without.outcome) : "—"}</td>
        <td>${baseline ? scoreBadge(baseline.score) + outcomeLabel(baseline.outcome) : "—"}</td>
        <td>${improved ? scoreBadge(improved.score) + outcomeLabel(improved.outcome) : "—"}</td>
        <td>${absent || !baseline ? "—" : baseline.hit ? "да" : "нет"}</td>
        <td>${hit}</td>
        <td>${loss}</td>
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
                <td class="similarity">${ru(source.similarity, 3)}</td>
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

// Таблица кандидатов поиска: место в выдаче, отметка фильтра, оценка и позиция после второго этапа,
// номер в контексте. По этой таблице видно, почему в контекст ушёл или не ушёл каждый фрагмент.
function candidateTable(trace) {
    if (!trace.candidates.length) {
        return `<p class="missing">Поиск не вернул ни одного кандидата.</p>`;
    }
    const rows = trace.candidates
        .map((candidate) => {
            const filter = candidate.accepted
                ? `<span class="loss loss-good">прошёл</span>`
                : `<span class="loss loss-bad">отсечён</span>`;
            const score = candidate.rerankScore == null ? "—" : ru(candidate.rerankScore, 3);
            const moved = candidate.rerankRank != null && candidate.rerankRank !== candidate.rank;
            const position = candidate.rerankRank == null
                ? "—"
                : `${candidate.rank} → <span class="${moved ? "moved" : ""}">${candidate.rerankRank}</span>`;
            const context = candidate.finalRank == null ? "—" : `<b>[${candidate.finalRank}]</b>`;
            return `<tr>
                <td class="mono">${candidate.rank}</td>
                <td><span class="mono">${esc(candidate.id)}</span><br><span class="question-meta">${esc(candidate.label)}</span></td>
                <td class="similarity">${ru(candidate.similarity, 3)}</td>
                <td>${filter}</td>
                <td class="similarity">${score}</td>
                <td class="similarity">${position}</td>
                <td>${context}</td>
            </tr>`;
        })
        .join("");
    return `<table class="sources">
        <thead><tr>
            <th># поиска</th><th>фрагмент</th><th>близость</th><th>фильтр</th>
            <th>оценка этапа</th><th>позиция</th><th>контекст</th>
        </tr></thead>
        <tbody>${rows}</tbody></table>`;
}

function rewriteStage(trace) {
    const query = trace.rewritten ?? trace.original;
    const changed = trace.rewritten != null && trace.rewritten.trim() !== trace.original.trim();
    const note = trace.rewritten == null
        ? "Переписывания нет: в поиск ушёл исходный вопрос."
        : changed
          ? "Модель изменила запрос: он ушёл только в поиск, а модель отвечает на исходный вопрос."
          : "Модель вернула тот же запрос: переписывание ничего не поменяло.";
    const body = `
        <p><span class="question-meta">Исходный вопрос</span><br>${esc(trace.original)}</p>
        <p><span class="question-meta">Запрос в поиск</span><br>${esc(query)}</p>
        <p class="question-meta">${esc(note)}</p>`;
    return stage("1. Query Rewrite", body, trace.rewriteMillis ? seconds(trace.rewriteMillis) : null);
}

function retrievalStage(trace) {
    const note = trace.note ? `<p class="question-meta">Замечание этапа: ${esc(trace.note)}</p>` : "";
    return stage(
        `2. Retrieval (Top-${trace.retrievalTopK})`,
        candidateTable(trace) + note,
        seconds(trace.retrievalMillis)
    );
}

function filterStage(trace) {
    const body = `<p>Прошло порог ${trace.accepted} из ${trace.candidates.length}, отсеяно ${trace.removed}.</p>` +
        (trace.empty ? `<p class="missing">Кандидатов не осталось: контекста нет.</p>` : "");
    return stage(`3. Similarity Filter (${thresholdLabel(trace.threshold)})`, body);
}

function rerankStage(trace) {
    const moved = trace.candidates.filter((candidate) => candidate.rerankRank != null && candidate.rerankRank !== candidate.rank);
    const name = state.config?.rerank || "без второго этапа";
    const body = `<p>В контекст после отсечения прошло ${trace.passed} из ${trace.accepted}. ` +
        `Порядок до → после второго этапа: ${moved.length ? `изменился у ${moved.length}` : "не менялся"}.</p>` +
        (trace.rerankScoredByModel > 0
            ? `<p class="question-meta">Оценено моделью: ${trace.rerankScoredByModel} из ${trace.accepted}.</p>`
            : "");
    return stage(`4. Reranking (${esc(name)})`, body, trace.rerankMillis ? seconds(trace.rerankMillis) : null);
}

function finalStage(trace) {
    const final = trace.candidates
        .filter((candidate) => candidate.finalRank != null)
        .sort((left, right) => left.finalRank - right.finalRank);
    if (!final.length) {
        return stage("5. Финальный Top-K", `<p class="missing">В контекст не ушло ни одного фрагмента.</p>`);
    }
    const items = final
        .map(
            (candidate) =>
                `<li><b>[${candidate.finalRank}]</b> <span class="mono">${esc(candidate.id)}</span> — ` +
                `был #${candidate.rank} по близости ${ru(candidate.similarity, 3)}</li>`
        )
        .join("");
    return stage(`5. Финальный Top-K (${final.length})`, `<ul class="rank-list">${items}</ul>`);
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

function stageCheckStage(result) {
    const check = result.stageCheck;
    if (!check) return "";
    const mark = (value) => (value ? "да" : "нет");
    const body = `
        <p>Правильный фрагмент после поиска: ${mark(check.inRetrieval)}; после фильтра: ${mark(check.inFiltered)}; ` +
        `в финальном контексте: ${mark(check.inFinal)}.</p>
        <p class="question-meta">Кандидатов ${check.candidates}, прошло порог ${check.accepted}, в контексте ${check.passed}; ` +
        `отсеяно ${check.removed}, из них правильных ${check.wronglyFiltered}; порядок изменён: ${check.reordered ? "да" : "нет"}` +
        `${check.scored ? "" : " (ответа в базе нет — попадание не считается)"}.</p>
        <p>${lossBadge(check)}</p>`;
    return stage("Классификация потерь", body);
}

function agentColumn(mode, question, result) {
    const kind = mode?.mode;
    if (!mode) {
        return `<div class="agent"><div class="agent-head"><h3>Режим не прогонялся</h3></div>
            <p class="missing">Нажмите «Прогнать» у вопроса — появятся все три конвейера.</p></div>`;
    }
    const head = `<div class="agent-head"><h3>${esc(mode.title)}</h3>
        <span>${scoreBadge(mode.score)}${outcomeLabel(mode.outcome)}</span></div>`;
    if (mode.state === "failed") {
        const search = mode.mode === "without"
            ? stage("Поиск", `<p class="missing">Режим без RAG к поиску не обращается.</p>`)
            : stage("Поиск", `<p class="missing">Отказ случился до выдачи: поиск не выполнялся.</p>`);
        return `<div class="agent ${esc(kind)}">${head}${search}
            <div class="stage"><div class="error-box">${esc(mode.error || "режим отказал")}</div></div></div>`;
    }

    const stages = [stage("Вопрос", esc(question.question))];
    if (kind === "without") {
        stages.push(
            stage(
                "Поиска не было",
                `<p class="missing">Режим без базы не обращается к индексу: модель отвечает по памяти.</p>`
            )
        );
    } else {
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
        if (kind === "improved" && result?.trace) {
            const trace = result.trace;
            stages.push(rewriteStage(trace));
            stages.push(retrievalStage(trace));
            stages.push(filterStage(trace));
            stages.push(rerankStage(trace));
            stages.push(finalStage(trace));
        } else {
            stages.push(stage(`Top-K (${mode.sources.length})`, sourceTable(mode)));
        }
    }
    const user = mode.messages.find((message) => message.role === "user");
    const system = mode.messages.find((message) => message.role === "system");
    if (user) {
        stages.push(
            stage(
                kind === "without" ? "Запрос к модели" : "Контекст, ушедший в модель",
                fold(
                    `context-${kind}`,
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
    if (kind === "improved") stages.push(stageCheckStage(result));
    return `<div class="agent ${esc(kind)}">${head}${stages.join("")}</div>`;
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
    element.innerHTML =
        agentColumn(result?.without, question, result) +
        agentColumn(result?.baseline, question, result) +
        agentColumn(result?.improved, question, result);
}

function metric(title, value, note) {
    return `<div class="metric"><dt>${esc(title)}</dt><dd>${value}${note ? `<small>${esc(note)}</small>` : ""}</dd></div>`;
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
    document.getElementById("metrics").innerHTML = [
        metric(
            "Средняя оценка без RAG",
            `${ru(summary.averageWithout, 1)} из 2`,
            `${summary.factsWithout} из ${summary.factsTotal} фактов`
        ),
        metric(
            "Средняя оценка базового RAG",
            `${ru(summary.averageWith, 1)} из 2`,
            `${summary.factsWith} из ${summary.factsTotal} фактов`
        ),
        metric(
            `Source Hit Rate (Top-${state.config?.baselineTopK ?? setup.topK})`,
            `${summary.hits} из ${summary.scored}`,
            `${Math.round(summary.hitRate * 100)}% — ожидаемый источник попал в выдачу`
        ),
        metric(
            "По вопросам",
            `+${summary.better} / =${summary.same} / −${summary.worse}`,
            "базовый RAG выше, столько же, ниже"
        ),
        metric(
            "Итог",
            `${summary.answered} верно`,
            `ошибок поиска ${summary.retrievalErrors}, ошибок генерации ${summary.generationErrors}`
        ),
        metric(
            "Токены",
            `${tokens(summary.without.prompt + summary.without.completion)} / ${tokens(summary.with.prompt + summary.with.completion)}`,
            "без RAG / базовый RAG, запрос + ответ"
        ),
        metric(
            "Время",
            `${seconds(summary.millisWithout)} / ${seconds(summary.millisWith)}`,
            `без RAG / базовый RAG; модель и поиск, без RAG поиска не было`
        ),
    ].join("");
}

function renderStages() {
    const panel = document.getElementById("stages-panel");
    const stages = state.stages;
    if (!stages) {
        panel.hidden = true;
        return;
    }
    panel.hidden = false;
    const config = state.config || {};
    const retrievalTopK = config.retrievalTopK ?? setup.retrievalTopK;
    const finalTopK = config.finalTopK ?? setup.finalTopK;
    document.getElementById("stages-hint").textContent =
        `Вопросов с ответом в базе: ${stages.scored} из ${stages.questions}. ` +
        `Этапы: переписывание — ${config.rewrite || "нет"}, фильтр — ${thresholdLabel(config.threshold ?? setup.threshold)}, ` +
        `второй этап — ${config.rerank || "нет"}. Hit считается по вопросам с ответом в базе.`;
    document.getElementById("stages-metrics").innerHTML = [
        metric(
            "Средняя оценка трёх режимов",
            `${ru(stages.noBaseScore, 2)} / ${ru(stages.baselineScore, 2)} / ${ru(stages.improvedScore, 2)}`,
            "без базы / базовый / улучшенный, из 2"
        ),
        metric(
            "Факты в ответах",
            `${stages.baselineFacts} / ${stages.improvedFacts}`,
            `базовый / улучшенный, из ${stages.factsTotal}`
        ),
        metric(
            "Улучшенный против базового",
            `+${stages.better} / =${stages.same} / −${stages.worse}`,
            "выше, столько же, ниже по оценке"
        ),
        metric(
            "Source Hit Rate",
            `${stages.baselineHits} / ${stages.improvedHits}`,
            `базовый / улучшенный, из ${stages.scored}`
        ),
        metric(
            "Попадание по этапам",
            `${stages.retrievalHits} → ${stages.filterHits} → ${stages.finalHits}`,
            `после поиска Top-${retrievalTopK}, после фильтра, после второго этапа Top-${finalTopK}`
        ),
        metric(
            "Цена фильтра",
            `${stages.removed} отсеяно`,
            `из них правильных ${stages.wronglyFiltered}; пустых контекстов ${stages.emptyContext}`
        ),
        metric(
            "Второй этап",
            `${stages.reordered} вопросов`,
            "изменил порядок кандидатов"
        ),
        metric(
            "Где потерялся фрагмент",
            `${stages.lostInRetrieval} / ${stages.lostInFilter} / ${stages.lostInRerank}`,
            "не найден поиском / отсечён порогом / проиграл на втором этапе"
        ),
        metric(
            "Переписывание",
            `${stages.rewriteChanged} вопросов`,
            "запрос изменился и ушёл в поиск переписанным"
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
    renderStages();
    renderPipeline();
}

function renderSetup() {
    document.getElementById("run-title").textContent =
        `Набор: ${setup.questions.length} контрольных вопросов. Модель: ${setup.model}. ` +
        `Векторы: ${setup.provider.name}. Каталог прогона: ${setup.dir}`;
    document.getElementById("topk").value = setup.topK;
    document.getElementById("topk").max = setup.maxTopK;
    document.getElementById("retrieval-topk").value = setup.retrievalTopK;
    document.getElementById("final-topk").value = setup.finalTopK;
    document.getElementById("threshold").value = setup.threshold;
    document.getElementById("rewrite").value = setup.rewrite;
    document.getElementById("rerank").value = setup.rerank;
    document.getElementById("setup-facts").innerHTML = [
        ["База", `${setup.base.file} — ${tokens(setup.base.chars)} символов, ${tokens(setup.base.tokens)} токенов, ${setup.base.pages} страниц`],
        ["Нарезка", `${setup.index.strategy} — ${tokens(setup.index.chunks)} чанков`],
        ["Векторы", `${setup.provider.name} — ${setup.provider.dimension} измерений`],
        ["Провайдер", setup.provider.note],
        ["Этапы по умолчанию", `поиск Top-${setup.retrievalTopK}, в контекст Top-${setup.finalTopK}, порог ${setup.threshold}, переписывание ${setup.rewrite}, второй этап ${setup.rerank}`],
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
        const params = new URLSearchParams({
            ids: ids.join(","),
            topK: document.getElementById("topk").value,
            retrievalTopK: document.getElementById("retrieval-topk").value,
            finalTopK: document.getElementById("final-topk").value,
            threshold: document.getElementById("threshold").value,
            rewrite: document.getElementById("rewrite").value,
            rerank: document.getElementById("rerank").value,
        });
        state = await api(`/api/run?${params}`, { method: "POST" });
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
