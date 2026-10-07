'use strict';

// Страница не знает о конвейере ничего, кроме пяти маршрутов: setup — корпус, настройки и эталонный
// вопрос, status — ход индексации, index — её запуск с настройками, search — разбор вопроса.
// Логика поиска и нарезки живёт на сервере; здесь только показ, поэтому тексты экранирует DOM,
// а не склейка строк HTML.

// Опрос частый: корпус дня индексируется за доли секунды, и редкий опрос показал бы только итог.
const POLL_INTERVAL_MS = 100;

const dom = {
    corpusTitle: document.getElementById('corpus-title'),
    strategyHint: document.getElementById('strategy-hint'),
    corpusFiles: document.getElementById('corpus-files'),
    chunkSize: document.getElementById('chunk-size'),
    overlap: document.getElementById('overlap'),
    maxChunkSize: document.getElementById('max-chunk-size'),
    resetButton: document.getElementById('reset-settings'),
    indexButton: document.getElementById('index-button'),
    pdfUrl: document.getElementById('pdf-url'),
    loadButton: document.getElementById('load-button'),
    pdfFile: document.getElementById('pdf-file'),
    uploadButton: document.getElementById('upload-button'),
    downloadStatus: document.getElementById('download-status'),
    status: document.getElementById('status'),
    settingsHint: document.getElementById('settings-hint'),
    progressWrap: document.getElementById('progress-wrap'),
    progressBar: document.getElementById('progress-bar'),
    phaseLog: document.getElementById('phase-log'),
    questionText: document.getElementById('question-text'),
    topk: document.getElementById('topk'),
    searchButton: document.getElementById('search-button'),
    reference: document.getElementById('reference'),
    providerHint: document.getElementById('provider-hint'),
    strategies: document.getElementById('strategies')
};

let polling = null;
let defaults = null;
let builtSettings = null;
/** Сколько документов загружено: по нему решается, доступна ли индексация. */
let documentCount = 0;
/** Состояние индекса из последнего статуса: по нему решается, доступен ли поиск. */
let indexState = 'idle';

/**
 * Русское склонение по числу: «1 чанк», «2 чанка», «5 чанков». Нужно там, где число вставляется
 * в текст для человека: «44 чанков» читается как ошибка, а не как счёт.
 */
function plural(count, one, few, many) {
    const tail = count % 100;
    const last = count % 10;
    if (tail >= 11 && tail <= 14) return `${count} ${many}`;
    if (last === 1) return `${count} ${one}`;
    if (last >= 2 && last <= 4) return `${count} ${few}`;
    return `${count} ${many}`;
}

function number(value) {
    return value.toLocaleString('ru-RU');
}

/** Элемент с текстом: данные попадают в DOM как текст, а не как разметка. */
function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = text;
    return node;
}

async function api(path, options) {
    const response = await fetch(path, options);
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(payload.message || `HTTP ${response.status}`);
    return payload;
}

function setStatus(text, kind) {
    dom.status.textContent = text;
    dom.status.className = 'status' + (kind ? ' ' + kind : '');
}

/** Настройки из полей страницы. */
function currentSettings() {
    return {
        chunkSize: Number(dom.chunkSize.value),
        overlap: Number(dom.overlap.value),
        maxChunkSize: Number(dom.maxChunkSize.value)
    };
}

function sameSettings(left, right) {
    return !!left && !!right && left.chunkSize === right.chunkSize &&
        left.overlap === right.overlap && left.maxChunkSize === right.maxChunkSize;
}

function formatSettings(settings) {
    return `${number(settings.chunkSize)} / ${number(settings.overlap)} / ${number(settings.maxChunkSize)}`;
}

/** Проверка до отправки: те же правила, что у чанкеров, — чтобы ошибку было видно сразу. */
function settingsProblem(settings) {
    if (!Number.isInteger(settings.chunkSize) || settings.chunkSize <= 0) {
        return 'chunk должен быть целым положительным числом';
    }
    if (!Number.isInteger(settings.overlap) || settings.overlap < 0) {
        return 'overlap не может быть отрицательным';
    }
    if (settings.overlap >= settings.chunkSize) {
        return `overlap (${settings.overlap}) должен быть меньше chunk (${settings.chunkSize}), иначе окно не двигается вперёд`;
    }
    if (!Number.isInteger(settings.maxChunkSize) || settings.maxChunkSize <= 0) {
        return 'max_chunk должен быть целым положительным числом';
    }
    return null;
}

/**
 * Подсказка о том, что поля разошлись с настройками построенного индекса.
 *
 * Состоянием кнопки индексации распоряжается не эта функция, а [applyGates]: доступность кнопки
 * зависит сразу от шага (загружен ли документ) и от настроек (годятся ли они), и решать это в двух
 * местах значило бы возвращать кнопку в строй мимо шага — что и случалось, пока здесь стояло
 * безусловное `disabled = false`.
 */
function renderSettingsHint() {
    const settings = currentSettings();
    const problem = settingsProblem(settings);
    if (problem) {
        dom.settingsHint.hidden = false;
        dom.settingsHint.textContent = problem;
    } else if (builtSettings && !sameSettings(settings, builtSettings)) {
        dom.settingsHint.hidden = false;
        dom.settingsHint.textContent = `Настройки изменены: индекс построен с ${formatSettings(builtSettings)} — ` +
            'постройте его заново, чтобы новая нарезка вступила в силу.';
    } else {
        dom.settingsHint.hidden = true;
        dom.settingsHint.textContent = '';
    }
    // Шлюзы пересчитываются здесь же: и годность настроек, и шаг решают одну и ту же кнопку.
    applyGates(indexState);
}

function fillSettings(settings) {
    dom.chunkSize.value = settings.chunkSize;
    dom.overlap.value = settings.overlap;
    dom.maxChunkSize.value = settings.maxChunkSize;
}

function renderSetup(setup) {
    dom.corpusTitle.textContent = `${setup.title} — документов: ${setup.documents}, ` +
        `файлов: ${setup.files.length}, символов: ${number(setup.chars)}, ` +
        `токенов: ${number(setup.tokens)}, страниц с текстом: ${setup.pages}` +
        // Своего корпуса у страницы нет: пока PDF не загружен, индексировать и искать нечего.
        (setup.documents === 0
            ? '. Загрузите PDF по ссылке или файлом: текст, страницы и разделы берутся из него'
            : '');
    dom.strategyHint.textContent = 'Настройки нарезки относятся к прогону на странице: ' +
        'chunk и overlap — окно Fixed Size, max_chunk — предел структурного чанка.';
    dom.corpusFiles.replaceChildren(...fileGroups(setup));
    // Вопрос дня только показывается: он задан заранее, и поле ввода здесь ничего бы не решало.
    dom.questionText.textContent = setup.referenceQuestion;
    renderProvider(setup.provider);
    documentCount = setup.documents;
    defaults = setup.defaults;
    fillSettings(defaults);
    dom.topk.replaceChildren(...Array.from({ length: setup.maxTopK }, (_, index) => {
        const value = String(index + 1);
        const option = el('option', null, value);
        option.value = value;
        if (index + 1 === setup.defaultTopK) option.selected = true;
        return option;
    }));
    renderSettingsHint();
}

/**
 * Кнопки по шагам: индексация — когда загружен документ, поиск — когда индекс построен.
 *
 * Порядок шагов выражен ими же: пока PDF не загружен, индексировать нечего, а пока индекс
 * не построен, искать не по чему. Так человек видит путь «документ → индекс → поиск» не из
 * инструкции, а из того, что доступно на странице.
 */
function applyGates(state) {
    const running = state === 'running';
    const settingsBad = settingsProblem(currentSettings()) !== null;
    dom.indexButton.disabled = running || documentCount === 0 || settingsBad;
    dom.searchButton.disabled = state !== 'ready';
}

/**
 * Файлы корпуса, сгруппированные по документу.
 *
 * Имя документа — заголовком группы, а не префиксом файла: у книги дня один документ, но файлов
 * может быть и несколько, и общее имя в каждой строке заслоняло бы сами имена. При одном документе
 * группы нет — там документ и набор совпадают.
 *
 * Документ дня помечен словом: он лежит в корпусе с самого запуска, и человек должен видеть, что
 * это ресурс страницы, а не его загрузка.
 */
function fileGroups(setup) {
    const order = [];
    const groups = new Map();
    setup.files.forEach((file) => {
        if (!groups.has(file.document)) {
            groups.set(file.document, []);
            order.push(file.document);
        }
        groups.get(file.document).push(file);
    });
    const single = setup.documents === 1;

    return order.map((document_) => {
        const item = el('li');
        if (!single) item.append(el('span', 'document', document_));
        const list = single ? item : el('ul', 'document-files');
        list.append(...groups.get(document_).map((file) => {
            const row = el('li');
            row.append(el('code', null, file.name));
            if (file.builtIn) row.append(el('span', 'badge built-in-badge', 'ресурс дня'));
            row.append(document.createTextNode(` — ${number(file.chars)} символов, ` +
                `${number(file.tokens)} токенов` + (file.pageLabel ? `, ${file.pageLabel}` : '')));
            return row;
        }));
        if (!single) item.append(list);
        return item;
    });
}

function renderPhaseLog(phases) {
    dom.phaseLog.hidden = phases.length === 0;
    dom.phaseLog.replaceChildren(...phases.map((phase) => el('li', null,
        `${phase.strategy} — ${plural(phase.chunks, 'кусочек', 'кусочка', 'кусочков')} ` +
        `(${number(phase.tokens)} токенов) за ${phase.elapsedMs} мс`)));
}

/** «Документ поделён на 44 кусочка (Fixed Size) и 44 (Structural)» — по числам состояния. */
function splitSummary(status) {
    const chunks = status.chunksByStrategy || {};
    const tokens = status.tokensByStrategy || {};
    const parts = Object.entries(chunks).map(([name, count]) =>
        `${plural(count, 'кусочек', 'кусочка', 'кусочков')} (${name}, ${number(tokens[name] || 0)} токенов)`);
    return parts.length === 0 ? '' : `документ поделён на ${parts.join(' и ')}`;
}

function renderStatus(status) {
    const running = status.state === 'running';
    indexState = status.state;
    dom.progressWrap.hidden = status.state === 'idle';
    dom.progressBar.style.width = `${status.percent}%`;
    applyGates(status.state);
    // Загрузку и настройки во время индексации менять нечем: второй прогон писал бы тот же файл.
    dom.loadButton.disabled = running;
    dom.uploadButton.disabled = running;
    dom.pdfUrl.disabled = running;
    dom.pdfFile.disabled = running;
    renderPhaseLog(status.phaseLog);

    if (status.state === 'ready' && status.settings) builtSettings = status.settings;
    renderSettingsHint();

    if (status.state === 'idle') {
        setStatus(documentCount === 0
            ? 'Индекс не построен. Сначала загрузите PDF (шаг 1).'
            : 'Корпус изменился — индекс не построен. Запустите индексацию (шаг 2).');
    } else if (running) {
        const chunks = status.total > 0 ? `${status.done} / ${status.total} чанков` : 'нарезка на чанки';
        const phase = status.phase || 'подготовка';
        setStatus(`Индексация (фаза ${status.phaseIndex + 1} из ${status.phaseCount}): ${phase} — ${chunks} — ${status.percent}%`);
    } else if (status.state === 'ready') {
        setStatus(`Индекс готов за ${status.elapsedMs} мс: ${splitSummary(status)}. ` +
            `Настройки: ${formatSettings(status.settings)}. Дальше — шаг 3: запустите поиск вопроса дня.`,
        'done');
    } else {
        setStatus(`Индексация не удалась: ${status.error}`, 'error');
    }
    return status.state;
}

function pollStatus() {
    api('/api/status').then((status) => {
        if (renderStatus(status) !== 'running') {
            stopPolling();
            return;
        }
        polling = window.setTimeout(pollStatus, POLL_INTERVAL_MS);
    }).catch((error) => {
        stopPolling();
        setStatus(error.message, 'error');
    });
}

function stopPolling() {
    if (polling !== null) {
        window.clearTimeout(polling);
        polling = null;
    }
}

function setDownloadStatus(text, kind) {
    dom.downloadStatus.textContent = text;
    dom.downloadStatus.className = 'status' + (kind ? ' ' + kind : '');
}

function startPolling() {
    stopPolling();
    polling = window.setTimeout(pollStatus, POLL_INTERVAL_MS);
}

/**
 * Эталон дня: вопрос задания, ответ на него и место в документе, где ответ лежит.
 *
 * Показаны оба текста, и это разные тексты. Ответ задания (поле `taskAnswer`) — то, что человек
 * должен найти: он сформулирован словами задания и в книге дословно не встречается. Страницы
 * документа (поле `answer`) — то, что о том же самом говорит корпус; они читаются из PDF
 * по номерам страниц, поэтому их может и не оказаться: чужой PDF не обязан содержать страницы
 * эталона, и тогда страница говорит об этом прямо, вместо того чтобы подставить чужой текст.
 * Между ними — абзацы документа, которые отвечают на вопрос: они ищутся по словам ответа задания,
 * а не вопроса, потому что показать нужно ответ, а не похожую на вопрос строку. Абзацев может быть
 * несколько: ответ-перечисление (пять принципов SOLID) в книге назван пятью абзацами, и первый
 * из них, показанный в одиночку, выдал бы себя за ответ целиком.
 */
function renderReference(reference) {
    dom.reference.hidden = false;
    const title = el('div', 'row');
    title.append(document.createTextNode('Эталон дня: ответ — '));
    title.append(el('code', null, reference.pageLabel ?? 'страницы не заданы'));
    title.append(document.createTextNode(' документа дня.'));
    const expected = el('p', 'expected', reference.taskAnswer);
    if (!reference.found) {
        dom.reference.replaceChildren(title, expected, el('p', 'fragment',
            'В корпусе этих страниц нет: эталон дня к нему не относится.'));
        return;
    }
    const fragments = reference.fragments ?? [];
    const where = el('div', 'row', fragments.length > 1
        ? 'Абзацы, которые отвечают на вопрос:'
        : 'Абзац, который отвечает на вопрос:');
    const details = el('details');
    details.append(el('summary', null, `Весь текст страниц ${reference.pageLabel ?? ''}`.trim()));
    details.append(el('p', 'fragment', reference.answer));
    dom.reference.replaceChildren(
        title,
        expected,
        where,
        ...fragments.map((text) => el('p', 'fragment', text)),
        details
    );
}

/** Совпадают ли два числа настолько, чтобы считать это одним и тем же результатом. */
function same(left, right) {
    return Math.abs(left - right) < 1e-9;
}

/**
 * Признаки измерения подписями: слова и триграммы у хешированного вектора, ничего у вектора модели.
 *
 * Пустой список — это не отсутствие данных, а свойство провайдера: у модели измерение не имеет
 * имени, и придумать ему подпись значило бы показать неправду. Поэтому пустая ячейка говорит
 * «без подписей», а не остаётся пустой.
 */
function featureSpans(features) {
    if (features.length === 0) return [el('span', 'feat-more', 'без подписей')];
    return features.map((feature) => el('span',
        'feat ' + (feature.kind === 'TRIGRAM' ? 'feat-trigram' : 'feat-word'), feature.text));
}

/**
 * Чем посчитаны векторы: имя провайдера, размерность и причина выбора.
 *
 * Близость — не универсальная величина: у хешированных признаков и у модели своя шкала, и читать
 * числа без имени провайдера нельзя. При откате на хеширование причина стоит здесь же — иначе
 * модель выглядела бы работающей, а выдача — обычной.
 */
function renderProvider(provider) {
    const scale = provider.featuresNamed
        ? 'Компоненты вектора объясняются словами и триграммами, которые в него попали.'
        : 'Компоненты вектора у модели безымянны: в разборе близости видны их номера и веса, ' +
          'а подписи отсутствуют.';
    dom.providerHint.textContent = `Векторы считает ${provider.name} — провайдер ` +
        `${provider.kind}, ${number(provider.dimension)} измерений (${provider.note}). ${scale}`;
}

/**
 * Вектор глазами человека: размерность, ненулевые, норма и старшие компоненты.
 *
 * Показать все измерения нечем — их 512 у хеширования и 1024 у модели дня, — а старшие
 * компоненты вместе с признаками говорят о векторе всё существенное: у хешированного вектора
 * измерение безымянно, и вес компоненты читается только по признакам, которые в неё попали,
 * а у модели признаков нет и остаётся один вес.
 */
function vectorCard(title, embedding) {
    const box = el('div', 'vec');
    box.append(el('h4', null, title));
    box.append(el('p', 'vec-meta', `${number(embedding.dimension)} измерений · ненулевых ` +
        `${number(embedding.nonZero)} · норма ${embedding.norm.toFixed(6)}`));

    const list = el('ul', 'dims');
    embedding.top.forEach((component) => {
        const item = el('li');
        item.append(el('span', 'dim', `#${component.dimension}`));
        item.append(el('span', 'weight', component.weight.toFixed(4)));
        const features = el('span', 'features');
        features.append(...featureSpans(component.features));
        if (component.hiddenFeatures > 0) {
            features.append(el('span', 'feat-more', `ещё ${component.hiddenFeatures}`));
        }
        item.append(features);
        list.append(item);
    });
    box.append(list);
    return box;
}

/** Слагаемые близости: измерение, веса обеих сторон, вклад и признаки, объясняющие измерение. */
function termsTable(breakdown) {
    const table = el('table', 'terms');
    const head = el('tr');
    ['измерение', 'вес вопроса', 'вес чанка', 'вклад', 'признаки вопроса', 'признаки чанка']
        .forEach((title) => head.append(el('th', null, title)));
    table.append(head);

    breakdown.terms.forEach((term) => {
        const row = el('tr');
        row.append(el('td', 'dim', `#${term.dimension}`));
        row.append(el('td', 'number', term.queryWeight.toFixed(4)));
        row.append(el('td', 'number', term.chunkWeight.toFixed(4)));
        row.append(el('td', 'number contribution', term.contribution.toFixed(4)));
        const queryFeatures = el('td', 'features');
        queryFeatures.append(...featureSpans(term.queryFeatures));
        const chunkFeatures = el('td', 'features');
        chunkFeatures.append(...featureSpans(term.chunkFeatures));
        row.append(queryFeatures, chunkFeatures);
        table.append(row);
    });

    const foot = el('tr', 'sum');
    foot.append(el('td', null, `показано ${breakdown.terms.length} из ${breakdown.shared}`));
    foot.append(el('td'), el('td'));
    foot.append(el('td', 'number', breakdown.shownSum.toFixed(4)));
    foot.append(el('td', 'features', 'сумма показанных слагаемых'), el('td'));
    table.append(foot);
    return table;
}

/**
 * Сверка чисел: similarity из выдачи, косинус по этим же векторам и сумма слагаемых.
 *
 * Векторы нормированы, поэтому все три числа обязаны совпасть; если нет — на странице видно
 * расхождение, а не подобранное вручную число.
 */
function checkLine(queryEmbedding, result) {
    const list = el('dl', 'check');
    const row = (term, value, kind) => {
        const wrap = el('div');
        wrap.append(el('dt', null, term));
        wrap.append(el('dd', kind || null, value));
        list.append(wrap);
    };
    row('similarity из выдачи', result.similarity.toFixed(6));
    row('косинус по этим векторам', result.breakdown.cosine.toFixed(6),
        same(result.similarity, result.breakdown.cosine) ? 'ok' : 'bad');
    row('Σ всех общих измерений', result.breakdown.dot.toFixed(6));
    row('нормы: вопрос / чанк', `${queryEmbedding.norm.toFixed(6)} / ${result.embedding.norm.toFixed(6)}`);
    return list;
}

/** Разбор одного ответа: эмбеддинг чанка, эмбеддинг вопроса и слагаемые близости. */
function embeddingDetails(queryEmbedding, result) {
    const details = el('details', 'why');
    details.append(el('summary', null, `Эмбеддинги и разбор similarity ` +
        `(у чанка ненулевых ${result.embedding.nonZero} из ${result.embedding.dimension}, ` +
        `общих с вопросом измерений ${result.breakdown.shared})`));
    const grid = el('div', 'vec-grid');
    grid.append(vectorCard('Вопрос', queryEmbedding));
    grid.append(vectorCard(`Чанк #${result.chunkIndex}`, result.embedding));
    details.append(grid);
    details.append(checkLine(queryEmbedding, result));
    details.append(termsTable(result.breakdown));
    return details;
}

function renderStrategy(strategy, queryEmbedding) {
    const box = el('div', 'strategy');
    box.append(el('h3', null, strategy.label));
    const meta = el('div', 'meta ' + (strategy.referenceRank ? 'found' : 'missed'),
        `${plural(strategy.chunks, 'кусочек', 'кусочка', 'кусочков')} в индексе ` +
        `(${number(strategy.tokens)} токенов) · ` + (strategy.referenceRank
            ? `эталонный ответ найден на ранге ${strategy.referenceRank}`
            : 'эталонного ответа нет в этой выдаче'));
    box.append(meta);

    if (strategy.results.length === 0) {
        box.append(el('p', 'hint', 'Ничего не найдено.'));
    }
    strategy.results.forEach((result) => {
        const item = el('div', 'result' + (result.reference ? ' reference-hit' : ''));
        const head = el('div', 'result-head');
        head.append(el('span', 'rank', `#${result.rank}`));
        head.append(el('span', 'similarity', `similarity ${result.similarity.toFixed(3)}`));
        head.append(el('span', 'badge', result.pageLabel || 'страницы неизвестны'));
        head.append(el('span', 'badge', result.section || 'раздел не размечен'));
        head.append(el('span', 'badge', `эмбеддинг: ${number(result.embedding.nonZero)}/${number(result.embedding.dimension)}`));
        if (result.reference) head.append(el('span', 'badge reference-badge', 'эталон'));
        item.append(head);

        const where = el('div', 'where');
        where.append(el('code', null, result.source));
        where.append(document.createTextNode(
            ` · чанк #${result.chunkIndex} · ${number(result.chars)} символов · ${number(result.tokens)} токенов`));
        item.append(where);
        item.append(el('p', 'fragment', result.fragment));
        item.append(embeddingDetails(queryEmbedding, result));
        box.append(item);
    });
    return box;
}

function renderSearch(data) {
    renderReference(data.reference);
    dom.strategies.replaceChildren(...data.strategies.map((strategy) =>
        renderStrategy(strategy, data.queryEmbedding)));
}

/**
 * Общий конец добавления: перерисовать корпус и показать, что дальше — индексация.
 *
 * Индексация на загрузке не запускается: документ и настройки нарезки выбираются по очереди,
 * и человек сам решает, когда считать. Индекс после добавления объявлен устаревшим, поэтому
 * поиск остаётся заблокированным до шага 2.
 */
function applyAdded(data, source) {
    renderSetup(data.setup);
    renderStatus(data.status);
    const file = data.file;
    setDownloadStatus(`Документ загружен (${source}): ${file.name} (${file.document}) — ` +
        `${number(file.chars)} символов, ${number(file.tokens)} токенов` +
        (file.pageLabel ? `, ${file.pageLabel}` : '') +
        '. Дальше — шаг 2: запустите индексацию.', 'done');
}

/** Загрузка по ссылке: ответ несёт весь корпус, страница перерисовывает список файлов. */
function loadPdf() {
    const url = dom.pdfUrl.value.trim();
    if (!url) {
        setDownloadStatus('Введите ссылку на PDF.', 'error');
        return;
    }
    dom.loadButton.disabled = true;
    setDownloadStatus('Скачиваю и разбираю PDF…');
    api(`/api/documents?url=${encodeURIComponent(url)}`, { method: 'POST' })
        .then((data) => {
            dom.pdfUrl.value = '';
            applyAdded(data, 'по ссылке');
        })
        .catch((error) => {
            setDownloadStatus(error.message, 'error');
            dom.loadButton.disabled = false;
            dom.pdfUrl.disabled = false;
        });
}

/**
 * Загрузка локального файла: файл уходит телом запроса, имя берётся у браузера.
 *
 * Файл читается целиком, а не по частям: PDF разбирается по всему содержимому сразу (нужны
 * таблица ссылок и шрифты), и потоковая передача ничего бы не сэкономила.
 */
function uploadPdf() {
    const file = dom.pdfFile.files[0];
    if (!file) {
        setDownloadStatus('Выберите файл PDF.', 'error');
        return;
    }
    dom.uploadButton.disabled = true;
    setDownloadStatus(`Читаю файл ${file.name}…`);
    const body = new FormData();
    body.append('file', file, file.name);
    api(`/api/documents/upload?name=${encodeURIComponent(file.name)}`, { method: 'POST', body })
        .then((data) => {
            dom.pdfFile.value = '';
            applyAdded(data, 'файл');
        })
        .catch((error) => {
            setDownloadStatus(error.message, 'error');
            dom.uploadButton.disabled = false;
            dom.pdfFile.disabled = false;
        });
}

/** Поиск вопроса дня: вопрос не отправляется — его знает сервер, страница выбирает только `top_k`. */
function search() {
    dom.searchButton.disabled = true;
    api(`/api/search?topK=${dom.topk.value}`)
        .then((data) => {
            renderSearch(data);
            setStatus(`Поиск выполнен: top_k = ${data.topK}, настройки ${formatSettings(data.settings)}, ` +
                `вектор вопроса: ненулевых ${number(data.queryEmbedding.nonZero)} из ${number(data.queryEmbedding.dimension)}.`);
        })
        .catch((error) => setStatus(error.message, 'error'))
        .finally(() => applyGates(indexState));
}

dom.indexButton.addEventListener('click', () => {
    const settings = currentSettings();
    const problem = settingsProblem(settings);
    if (problem) {
        setStatus(problem, 'error');
        return;
    }
    dom.indexButton.disabled = true;
    // Полоса от прошлого прогона осталась бы на 100%, пока не придёт первый статус нового.
    dom.progressBar.style.width = '0%';
    setStatus('Индексация запущена…');
    const query = `chunk=${settings.chunkSize}&overlap=${settings.overlap}&maxChunk=${settings.maxChunkSize}`;
    api(`/api/index?${query}`, { method: 'POST' })
        .then(renderStatus)
        .then(() => startPolling())
        .catch((error) => {
            setStatus(error.message, 'error');
            renderSettingsHint();
        });
});

dom.searchButton.addEventListener('click', search);

dom.loadButton.addEventListener('click', loadPdf);

dom.uploadButton.addEventListener('click', uploadPdf);

dom.resetButton.addEventListener('click', () => {
    if (defaults) fillSettings(defaults);
    renderSettingsHint();
});

[dom.chunkSize, dom.overlap, dom.maxChunkSize].forEach((input) => {
    input.addEventListener('input', renderSettingsHint);
});

api('/api/setup')
    .then(renderSetup)
    .catch((error) => setStatus(error.message, 'error'));
api('/api/status')
    .then(renderStatus)
    .catch((error) => setStatus(error.message, 'error'));
