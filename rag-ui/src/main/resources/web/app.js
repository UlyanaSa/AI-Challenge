// Страница дня 23 и дня 24: три режима ответа, разбор этапов конвейера и grounded-ответ
// с источниками, цитатами и их проверкой на десяти контрольных вопросах.
//
// Данных у страницы два источника, и оба приходят с сервера целиком. Набор вопросов — это setup:
// он не меняется и содержит ожидания, факты, место в книге и начальные настройки этапов. Состояние
// прогона — это state: его страница опрашивает и рисует из него таблицу, метрики, конвейеры, трейс
// и блок дня 24 у каждого вопроса.
//
// Страница ничего не считает сама: оценки, попадания, метрики этапов, классификацию потерь и признаки
// дня 24 (подтверждён ли ответ, охвачен ли источником и цитатой, уместен ли отказ) считает `:rag`,
// а сюда они приходят посчитанными. Второй набор формул в браузере разошёлся бы с отчётом прогона —
// а сверять страницу с отчётом тогда было бы нечем. Здесь только раскладка.

let setup = null;
let state = null;
let chat = null;
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

// Мутирующие маршруты чата принимают тело JSON: `api` для них тот же — ответ и ошибку страница
// разбирает одинаково, и второго способа читать ответ сервера на странице быть не должно.
async function postJson(path, body) {
    return api(path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
    });
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
    const row = `<tr class="question-row${selected === question.id ? " selected" : ""}" data-id="${question.id}">
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
    // Блок дня 24 идёт отдельной строкой под вопросом во всю ширину таблицы: ответ, источники,
    // цитаты и проверка в колонки сравнения не помещаются, а ломать разметку трёх режимов нельзя —
    // она остаётся ровно такой, какой была.
    return `${row}<tr class="grounded-row" data-id="${question.id}">
        <td colspan="9">${groundedBlock(question, result)}</td>
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

// День 24: ответ grounded-режима, его источники, цитаты и результат проверки. Разметка та же, что
// у конвейеров дня 23, — те же классы и те же блоки-этапы: это четвёртый блок вопроса, а не вторая
// страница, и отдельного вида у него быть не должно.

// Доля словами и числом, как в отчёте дня 24. Ноль в знаменателе — не «0 %», а отсутствие данных:
// у прогона без обычных ответов охват источников считать не по чему.
function rate(part, total) {
    return total ? `${part} из ${total} (${Math.round((part * 100) / total)} %)` : "нет данных";
}

function groundedSourceTable(grounded) {
    if (!grounded.sources.length) {
        return `<p class="missing">Источников нет: отказ не сопровождается источниками (§10).</p>`;
    }
    const rows = grounded.sources
        .map(
            (source) => `<tr>
            <td class="mono">[${source.fragment}]</td>
            <td><span class="mono">${esc(source.source)}</span>${source.section ? `<br><span class="question-meta">section: ${esc(source.section)}</span>` : ""}</td>
            <td class="mono">${esc(source.chunkId)}</td>
            <td>${source.pages.length ? esc(source.pages.join(", ")) : "—"}</td>
            <td class="similarity">${ru(source.similarity, 3)}</td>
            <td class="similarity">${source.rerankScore == null ? "—" : ru(source.rerankScore, 3)}</td>
        </tr>`
        )
        .join("");
    return `<table class="sources">
        <thead><tr>
            <th>фрагм.</th><th>источник и раздел</th><th>chunk_id</th><th>страницы</th>
            <th>близость</th><th>оценка этапа</th>
        </tr></thead>
        <tbody>${rows}</tbody></table>`;
}

function groundedQuotes(grounded) {
    // Показываются подтверждённые цитаты. Отброшенные не прячутся: они стоят в таблице проверки
    // ниже с пометкой «нет» и причиной — там им и место, потому что цитатой ответа они не стали.
    const confirmed = grounded.quotes.filter((quote) => quote.found);
    if (!confirmed.length) {
        return `<p class="missing">Цитат нет — подтверждать нечего.</p>`;
    }
    const items = confirmed
        .map(
            (quote) => `<li>«${esc(quote.quote)}»
            <span class="question-meta"> — утверждение: ${esc(quote.claim)}; фрагмент [${quote.fragment}]</span></li>`
        )
        .join("");
    return `<ul class="rank-list">${items}</ul>`;
}

function groundedCheckTable(grounded) {
    if (!grounded.quotes.length) {
        return `<p class="missing">Проверять нечего: модель не привела ни одной цитаты.</p>`;
    }
    const rows = grounded.quotes
        .map(
            (quote) => `<tr>
            <td class="mono">[${quote.fragment}]</td>
            <td>${esc(quote.claim)}<br><span class="question-meta">«${esc(quote.quote)}»</span></td>
            <td class="mono">${quote.chunkId == null ? "—" : esc(quote.chunkId)}</td>
            <td>${quote.found ? "да" : "нет"}</td>
            <td>${quote.reason == null ? "—" : esc(quote.reason)}</td>
        </tr>`
        )
        .join("");
    return `<table class="sources">
        <thead><tr>
            <th>фрагм.</th><th>утверждение и цитата</th><th>chunk_id</th><th>цитата в чанке</th><th>причина</th>
        </tr></thead>
        <tbody>${rows}</tbody></table>`;
}

function groundedDecision(grounded) {
    // Проверка на `== null`, а не `=== null`: сервер не пишет пустые поля в JSON, и «поля нет»
    // здесь означает то же, что «поле пустое», иначе прочерк превращался бы в ноль.
    const confidence = grounded.confidence;
    const parts = [
        `решение: ${confidence.decision || "—"}`,
        `фрагментов в контексте: ${confidence.considered}`,
        `лучшая близость: ${confidence.bestSimilarity == null ? "—" : ru(confidence.bestSimilarity, 3)}`,
        `порог: ${ru(confidence.threshold, 2)}`,
    ];
    const refusal = grounded.refusalTitle
        ? `<p class="missing">Отказ: ${esc(grounded.refusalTitle)}${grounded.note ? ` — ${esc(grounded.note)}` : ""}</p>`
        : grounded.note
          ? `<p class="question-meta">Замечание этапа: ${esc(grounded.note)}</p>`
          : "";
    return `<p class="cited">${parts.join(" · ")}</p>${refusal}`;
}

// Итог словами, а не пересчётом: признаки приходят посчитанными, страница только выбирает
// формулировку — «подтверждён», «не подтверждён» или отказ с его уместностью.
function groundedVerdict(grounded) {
    let badge;
    if (grounded.abstained) {
        badge = grounded.validAbstention
            ? `<span class="loss loss-warn">отказ (уместный)</span>`
            : `<span class="loss loss-bad">отказ (лишний)</span>`;
    } else if (grounded.grounded) {
        badge = `<span class="loss loss-good">подтверждён</span>`;
    } else {
        badge = `<span class="loss loss-bad">не подтверждён</span>`;
    }
    const extra = [];
    if (grounded.fabricatedSources > 0) extra.push(`выдуманных ссылок: ${grounded.fabricatedSources}`);
    if (grounded.invalidQuotes > 0) extra.push(`невалидных цитат: ${grounded.invalidQuotes}`);
    const note = extra.length ? ` <span class="question-meta">${esc(extra.join(", "))}</span>` : "";
    return `<p>${badge}${note}</p>`;
}

function groundedBlock(question, result) {
    const grounded = result?.grounded;
    const key = `grounded-${question.id}`;
    if (!grounded) {
        return fold(
            key,
            "Grounded-ответ (день 24) — не прогонялся",
            `<p class="missing">Нажмите «Прогнать» у вопроса — появятся ответ, источники, цитаты и проверка.</p>`
        );
    }
    // Сбой этапа — не отказ системы: показывается причина, а не пустые поля проверок, которые
    // выглядели бы как измеренный ноль.
    if (grounded.state === "failed") {
        return fold(
            key,
            "Grounded-ответ (день 24) — этап отказал",
            `<div class="error-box">${esc(grounded.error || "этап отказал")}</div>`
        );
    }
    // У отказа ответа нет: показывается его текст жирным — та самая формулировка §8, — а вслед
    // идут пустые источники и цитаты, чтобы отсутствие подтверждений было видно, а не подразумевалось.
    const answer = grounded.refusal
        ? `<p><b>${esc(grounded.refusal)}</b></p>`
        : grounded.answer && grounded.answer.trim()
          ? `<pre>${esc(grounded.answer)}</pre>`
          : `<p class="missing">Ответ пустой: модель не сказала ничего.</p>`;
    const body = [
        stage("Ответ", answer),
        stage("Источники", groundedSourceTable(grounded)),
        stage("Цитаты", groundedQuotes(grounded)),
        stage("Достаточность контекста", groundedDecision(grounded)),
        stage("Проверка цитат", groundedCheckTable(grounded)),
        stage("Итог по вопросу", groundedVerdict(grounded)),
    ].join("");
    return fold(key, "Grounded-ответ (день 24)", body);
}

function renderGrounding() {
    const panel = document.getElementById("grounding-panel");
    const grounding = state.grounding;
    if (!grounding) {
        panel.hidden = true;
        return;
    }
    panel.hidden = false;
    // Порог достаточности берётся из настроек прогона, а не пересчитывается: это тот же порог,
    // что у фильтра, и в шапке сводки он называется явно.
    const threshold = state.config?.threshold ?? setup.groundingThreshold;
    document.getElementById("grounding-hint").textContent =
        `Grounded-режим дня 24 идёт на той же выдаче, что улучшенный: вопросов ${grounding.questions}, ` +
        `обычных ответов ${grounding.answered}, отказов ${grounding.abstained} ` +
        `(уместных ${grounding.validAbstentions}, лишних ${grounding.wrongAbstentions}). ` +
        `Порог достаточности — тот же, что у фильтра: ${thresholdLabel(threshold)}.`;
    document.getElementById("grounding-metrics").innerHTML = [
        metric(
            "Answer Accuracy",
            rate(grounding.rightAnswers, grounding.questions),
            `верных ответов ${grounding.correct} и уместных отказов ${grounding.validAbstentions}`
        ),
        metric(
            "Source Coverage",
            rate(grounding.withSource, grounding.answered),
            "ответы с источником из обычных ответов"
        ),
        metric(
            "Quote Coverage",
            rate(grounding.withQuote, grounding.answered),
            "ответы с цитатой из обычных ответов"
        ),
        metric(
            "Grounded Answer Rate",
            rate(grounding.grounded, grounding.answered),
            "ответы, подтверждённые цитатами из процитированных чанков"
        ),
        metric(
            "Correct Abstention Rate",
            rate(grounding.validAbstentions, grounding.abstainNeeded),
            `лишних отказов: ${grounding.wrongAbstentions}`
        ),
        metric(
            "Факты в ответах",
            `${grounding.factsGrounded} из ${grounding.factsTotal}`,
            `grounded против ${grounding.factsPrevious} у предыдущего режима`
        ),
        metric(
            "Выдуманные ссылки",
            `${grounding.fabricatedSources}`,
            "ссылки на фрагменты, которых модель не получала"
        ),
        metric(
            "Невалидные цитаты",
            `${grounding.invalidQuotes}`,
            "цитаты, которых нет в процитированном чанке"
        ),
    ].join("");
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
    } else if (state.state === "stopped") {
        // Данные остановленного прогона остаются на странице, поэтому в подписи есть числа:
        // видно, сколько успело посчитаться, а не только «остановлен».
        parts.push(`Прогон остановлен через ${seconds(state.elapsedMs)}: ${state.done} из ${state.total} вопросов`);
    } else if (state.state === "done") {
        parts.push(`Прогон закончен за ${seconds(state.elapsedMs)}: ${state.done} из ${state.total} вопросов`);
    } else {
        parts.push("Прогон не запускался");
    }
    status.innerHTML = parts.join(" · ");
    status.className =
        "status" +
        (state.error
            ? " error"
            : state.state === "done"
              ? " done"
              : state.state === "stopped"
                ? " stopped"
                : "");

    const running = state.state === "running" || index.state === "building";
    const bar = document.getElementById("progress-wrap");
    bar.hidden = !running;
    if (running) {
        const percent = index.state === "building"
            ? (index.total ? (index.chunks * 100) / index.total : 0)
            : (state.total ? (state.done * 100) / state.total : 0);
        document.getElementById("progress-bar").style.width = `${Math.round(percent)}%`;
    }
    // Останавливать можно только то, что идёт: иначе кнопка обещала бы действие, которого нет.
    document.getElementById("stop").disabled = !running;

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

// День 25: мини-чат. Лента реплик, память задачи, сводка и итог сценария — всё из состояния
// `/api/chat`. Страница не считает ни одного числа сама: и строку сводки, и приговор сценария
// и признаки хода (есть ли источник, ответ по памяти или отказ) считает `:rag`, а сюда они
// приходят посчитанными — второй набор правил в браузере разошёлся бы с отчётом.

// Источники ответа: из метаданных чанка, как их собрал движок. Отказ и ответ по памяти фрагментов
// не имеют по построению — у них источником служит причина или сама память, и её называет sourcesLine.
function chatSources(turn) {
    if (turn.sources.length) {
        const items = turn.sources
            .map((source) => {
                const place = [
                    source.source,
                    source.section,
                    source.pages.length ? `стр. ${source.pages.join(", ")}` : null,
                    `чанк #${source.chunkIndex}`,
                ]
                    .filter(Boolean)
                    .join(" · ");
                const rerank = source.rerankScore == null ? "" : ` · этап ${ru(source.rerankScore, 3)}`;
                return `<li><span class="mono">[${source.fragment}]</span> ${esc(place)}` +
                    `<span class="question-meta"> · близость ${ru(source.similarity, 3)}${rerank}</span></li>`;
            })
            .join("");
        return `<ul class="chat-sources">${items}</ul>`;
    }
    const note = turn.refusalReason ?? turn.error ?? turn.sourcesLine;
    return `<p class="question-meta">Источник: ${esc(note)}</p>`;
}

// Один ход разговора: реплика человека и ответ ассистента. Вид ответа вынесен в пометку, чтобы
// отличить ответ по базе от ответа по памяти задачи и от честного отказа, не читая текст ответа.
function chatTurn(turn) {
    const kind = turn.kindTitle
        ? `<span class="loss loss-good">${esc(turn.kindTitle)}</span>`
        : turn.refusal
          ? `<span class="loss loss-bad">отказ</span>`
          : `<span class="loss loss-bad">сбой</span>`;
    const answer = turn.answer
        ? `<pre class="chat-answer">${esc(turn.answer)}</pre>`
        : turn.error
          ? `<div class="error-box">Ответ не получен: ${esc(turn.error)}</div>`
          : `<p class="missing">Отказ: ${esc(turn.refusalReason || "причина не записана")}</p>`;
    const query =
        turn.query !== turn.question
            ? `<p class="question-meta">Поиск: ${esc(turn.query)}${turn.queryNote ? ` (${esc(turn.queryNote)})` : ""}</p>`
            : "";
    const memory = turn.memoryNote ? `<p class="question-meta">Память: ${esc(turn.memoryNote)}</p>` : "";
    return `<div class="chat-turn">
        <div class="chat-q"><span class="chat-who">человек</span>${esc(turn.question)}</div>
        <div class="chat-a">
            <div class="chat-a-head">${kind}<span class="question-meta">ход ${turn.index} · ${seconds(turn.elapsedMillis)}</span></div>
            ${query}${answer}${chatSources(turn)}${memory}
        </div>
    </div>`;
}

// Панель памяти задачи: цель, уточнения, правила и термины. Пока память пуста, панель говорит это
// словами, а не рисует заголовки без строк — они читались бы как память, в которой ничего нет.
function renderChatMemory(memory) {
    const body = document.getElementById("chat-memory-body");
    if (memory.empty) {
        body.innerHTML = `<div><dt>память</dt><dd>пока пуста — разговор ещё не зафиксировал цель, правила и термины</dd></div>`;
        return;
    }
    const cells = [];
    if (memory.goal) cells.push(["цель", esc(memory.goal)]);
    if (memory.clarifications.length) cells.push(["уточнено", memory.clarifications.map(esc).join("; ")]);
    if (memory.constraints.length) cells.push(["ограничения", memory.constraints.map(esc).join("; ")]);
    if (memory.terms.length) {
        cells.push(["термины", memory.terms.map((term) => `«${esc(term.term)}» — ${esc(term.meaning)}`).join("; ")]);
    }
    body.innerHTML = cells.map(([term, value]) => `<div><dt>${term}</dt><dd>${value}</dd></div>`).join("");
}

// Сценарий показывается приговором и проверками по шагам. Приговор приходит строками от движка:
// страница их печатает, а не выводит из чисел заново.
function renderChatScenario(result) {
    const panel = document.getElementById("chat-result");
    if (!result) {
        panel.hidden = true;
        panel.innerHTML = "";
        return;
    }
    panel.hidden = false;
    const badge = result.accepted
        ? `<span class="loss loss-good">принято</span>`
        : `<span class="loss loss-bad">есть нарушения</span>`;
    const mark = (value) => (value == null ? "—" : value ? "да" : "нет");
    const rows = result.checks
        .map(
            (check) => `<tr>
            <td class="mono">${check.index}</td>
            <td>${esc(check.question)}${
                check.failures.length
                    ? `<br><span class="chat-fail">${check.failures.map(esc).join("<br>")}</span>`
                    : ""
            }</td>
            <td>${mark(check.kindOk)}</td>
            <td>${mark(check.goalOk)}</td>
            <td>${mark(check.refusalOk)}</td>
            <td>${check.factsMatched}/${check.factsTotal}</td>
            <td>${check.passed ? "да" : "нет"}</td>
        </tr>`
        )
        .join("");
    panel.innerHTML = `
        <h3>Сценарий ${esc(result.name)}: ${esc(result.title)} ${badge}</h3>
        <p class="question-meta">Сведений названо: ${result.factsMatched} из ${result.factsTotal} — мера качества ответов, а не приговор.</p>
        <ul class="rank-list">${result.verdict.map((line) => `<li>${esc(line)}</li>`).join("")}</ul>
        <table class="sources">
            <thead><tr>
                <th>#</th><th>реплика и нарушения</th><th>вид</th><th>цель</th><th>отказ</th><th>сведения</th><th>шаг</th>
            </tr></thead>
            <tbody>${rows}</tbody>
        </table>`;
}

// Кнопки сценариев берутся из состояния: имена и заголовки движок присылает сам, и второй список
// сценариев в разметке разошёлся бы с ним при первой правке.
function renderChatScenarios(chatState) {
    const enabled = chatState.available && !chatState.busy;
    document.getElementById("chat-scenarios").innerHTML = chatState.scenarios
        .map(
            (scenario) => `<button class="secondary chat-scenario" data-name="${esc(scenario.name)}" type="button"${
                enabled ? "" : " disabled"
            }>
            <span>Прогнать сценарий: ${esc(scenario.title)}</span>
            <small>${esc(scenario.goal)} · реплик ${scenario.steps}</small>
        </button>`
        )
        .join("");
}

function renderChat() {
    if (!chat) return;
    renderChatScenarios(chat);
    renderChatMemory(chat.memory);
    renderChatScenario(chat.scenario);

    document.getElementById("chat-feed").innerHTML =
        chat.turns.map(chatTurn).join("") ||
        `<p class="missing">Разговор пуст: напишите первую реплику или прогоните сценарий.</p>`;

    const status = document.getElementById("chat-status");
    status.className = "status" + (chat.error ? " error" : chat.busy ? " stopped" : "");
    status.textContent = chat.available
        ? chat.busy
            ? `Идёт ${chat.activity || "ход"}…`
            : `Разговор доступен. Настройки поиска: ${chat.settings}`
        : "Чат недоступен.";

    // Причина недоступности и сбой хода идут одной строкой: и то и другое — то, что мешает говорить,
    // и человеку одинаково нужно прочитать, чем именно.
    const note = document.getElementById("chat-note");
    const message = chat.error || chat.note;
    note.hidden = !message;
    note.textContent = message || "";

    const summary = document.getElementById("chat-summary");
    if (chat.summary && chat.turns.length) {
        summary.hidden = false;
        summary.textContent =
            `${chat.summary.line} · источники у каждого ответа: ${chat.summary.sourcesEverywhere ? "да" : "нет"}` +
            ` · цель держится: ${chat.summary.goalKept ? "да" : "нет"}` +
            ` · подтверждённых утверждений ${chat.summary.claims}, отброшенных цитат ${chat.summary.quotesDropped}`;
    } else {
        summary.hidden = true;
    }

    // Пока идёт ход или сценарий, ввод и кнопки выключены: второй разговор не начинается поверх
    // первого, и обещание кнопки совпадает с тем, что действительно можно сделать.
    const blocked = !chat.available || chat.busy;
    document.getElementById("chat-send").disabled = blocked;
    document.getElementById("chat-clear").disabled = blocked;
    document.getElementById("chat-text").disabled = blocked;
}

function render() {
    renderChat();
    renderStatus();
    renderQuestions();
    renderMetrics();
    renderStages();
    renderGrounding();
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
        ["Grounded (день 24)", `порог достаточности ${ru(setup.groundingThreshold, 2)} — то же число, что у фильтра; ответ, источники и цитаты на той же выдаче`],
    ]
        .map(([term, value]) => `<div><dt>${esc(term)}</dt><dd>${esc(value)}</dd></div>`)
        .join("");
    const canRun = !setup.keyNote;
    document.getElementById("run-all").disabled = !canRun;
    for (const button of document.querySelectorAll(".run-one")) button.disabled = !canRun;
}

async function refresh() {
    state = await api("/api/state");
    // Разговор опрашивается тем же циклом, что и прогон: пока идёт сценарий, лента растёт по ходам,
    // и именно этот опрос показывает прогресс — страница на время сценария не блокируется.
    chat = await api("/api/chat");
    const next = JSON.stringify({ state, chat, selected });
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
document.getElementById("stop").addEventListener("click", async () => {
    try {
        state = await api("/api/stop", { method: "POST" });
        // Подпись и таблица перерисовываются сразу по ответу, а не по следующему опросу:
        // остановленный прогон — это результат, и ждать его секунду незачем.
        signature = "";
        render();
    } catch (cause) {
        alert(cause.message);
    }
});
document.getElementById("rebuild").addEventListener("click", async () => {
    try {
        state = await api("/api/index", { method: "POST" });
        signature = "";
        render();
    } catch (cause) {
        alert(cause.message);
    }
});

// Реплика чата: ответ приходит состоянием целиком, и страница рисует его сразу — ждать следующего
// опроса, чтобы показать ход, значит показывать паузу, которой не было.
async function chatSend() {
    const field = document.getElementById("chat-text");
    const text = field.value.trim();
    if (!text) return;
    try {
        field.value = "";
        chat = await postJson("/api/chat/message", { text });
        signature = "";
        render();
    } catch (cause) {
        alert(cause.message);
    }
}

// Сценарий идёт в фоне: ответ на запуск — состояние «занято», а прогресс приносят опросы `refresh`.
async function chatScenario(name) {
    try {
        chat = await postJson("/api/chat/scenario", { name });
        signature = "";
        render();
    } catch (cause) {
        alert(cause.message);
    }
}

document.getElementById("chat-send").addEventListener("click", chatSend);
document.getElementById("chat-text").addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
        event.preventDefault();
        chatSend();
    }
});
document.getElementById("chat-clear").addEventListener("click", async () => {
    try {
        chat = await api("/api/chat/reset", { method: "POST" });
        signature = "";
        render();
    } catch (cause) {
        alert(cause.message);
    }
});
document.addEventListener("click", (event) => {
    const scenario = event.target.closest(".chat-scenario");
    if (scenario) {
        chatScenario(scenario.dataset.name);
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
