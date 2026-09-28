#!/usr/bin/env bash
#
# Локальный MCP-сервер проекта как служба: запуск, остановка, состояние, логи, кадры.
#
# Зачем это нужно, если клиент MCP поднимает сервер сам. MCP на stdio — это процесс,
# которым владеет подключившийся: сервер живёт ровно столько, сколько живёт клиент,
# и «оставить его работать» некому. Проверять сервер поэтому неудобно: он появляется
# на время демонстрации, и посмотреть на него со стороны нельзя.
#
# Этот скрипт владеет сервером сам. Сервер поднимается фоном, а его стандартный ввод
# подключён к именованному каналу (FIFO), который скрипт не закрывает: для сервера это
# значит «клиент ещё здесь», поэтому он не видит конец ввода и не выходит. Живёт он до
# `stop` (или до перезагрузки — тогда канал закрывается и сервер выходит сам).
#
# Что это даёт: сервер запущен, его кадры протокола и логи пишутся в файлы, состояние
# видно (`status` стучится в протокол), а в FIFO можно положить кадр руками — проверить
# сервер без клиента и без SDK.
#
# Чего это не даёт: сторонний клиент к такому серверу не подключится. Клиенты MCP сами
# поднимают процесс сервера и говорят с ним по своим каналам, а не по чужим (§5.2 в
# docs/task-16-mcp.md). Для клиента команда запуска та же, что была: `java -cp
# mcp-1.0.0-all.jar com.osvin.aichallenge.mcp.ProjectMcpServerKt` (без аргументов — сервер
# на stdio, `--list-tools` — инструменты и выход). Запускается fat JAR, а не обычный jar
# модуля: обычный — тонкий, и его берут те, кто зависит от модуля при компиляции.
#
# Серверов теперь два, и обслуживает их один скрипт: флаг `--server` выбирает, чей процесс
# поднимать (`project` — данные проекта, `github` — инструмент get_repositories к GitHub API).
# Механика у них одна — канал ввода, кадры, логи, остановка, — поэтому второй сервер здесь
# не отдельный скрипт, а выбор модуля, точки входа и каталога состояния.
#
# Запускать сервер через Gradle нельзя, и это не забывчивость: Gradle пишет в стандартный
# вывод процесса свои строки, а там кадры протокола. Поэтому инструмент — скрипт.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

# Какой сервер обслуживаем. У каждого своя точка входа, свой JAR и своё состояние, а
# механика одна: процесс, канал ввода, кадры, логи. Второй сервер (GitHub) появился
# после первого, и копия скрипта разошлась бы с этой — правки в запуске и остановке
# пришлось бы повторять дважды.
SERVER="project"

# Что зависит от выбранного сервера: модуль сборки, JAR, класс точки входа и каталог
# состояния. Состояние — рядом со сборкой своего модуля: каталог `build` уже не попадает
# в git, поэтому pid, канал и логи не надо никуда добавлять в .gitignore.
select_server() {
    case "$SERVER" in
        project)
            MODULE=":mcp"
            JAR="$HERE/build/libs/mcp-1.0.0-all.jar"
            MAIN="com.osvin.aichallenge.mcp.ProjectMcpServerKt"
            STATE="$HERE/build/mcp-server"
            ;;
        github)
            MODULE=":mcp-github"
            GITHUB_DIR="$ROOT/mcp-github"
            JAR="$GITHUB_DIR/build/libs/mcp-github-1.0.0-all.jar"
            MAIN="com.osvin.aichallenge.mcp.github.GitHubMcpServerKt"
            STATE="$GITHUB_DIR/build/mcp-server"
            ;;
        *)
            fail "неизвестный сервер: $SERVER (ожидается project или github)"
            ;;
    esac
    # Собирается именно fat JAR: для запуска процессом нужен один файл со всеми
    # зависимостями, а обычный jar модуля остаётся тонким — его берут те, кто зависит
    # от модуля при компиляции (см. комментарий у fatJar в build.gradle.kts модуля).
    BUILD_TASK="$MODULE:fatJar"
    PID_FILE="$STATE/server.pid"
    KEEPER_FILE="$STATE/keeper.pid"
    FIFO="$STATE/stdin.fifo"
    LOG_FILE="$STATE/server.log"
    FRAMES_FILE="$STATE/frames.log"
}

# Сколько ждать остановки сервера, прежде чем снимать силой.
STOP_TIMEOUT="${MCP_STOP_TIMEOUT:-10}"
# Срок жизни «держателя» канала ввода: он и есть срок службы сервера (год).
KEEPER_LIFETIME="${MCP_KEEPER_LIFETIME:-31536000}"
# Сколько ждать ответа протокола в `status`.
PROBE_TIMEOUT="${MCP_PROBE_TIMEOUT:-5}"
# Сколько дать JVM на старт: за это время видно, что процесс не упал сразу.
STARTUP_GRACE="${MCP_STARTUP_GRACE:-1}"
# Сколько строк логов и кадров печатать.
TAIL_LINES="${MCP_TAIL_LINES:-40}"

# Кадры рукопожатия: ими `status` проверяет, что сервер не просто жив, а отвечает.
# Протокол тот же, что у любого клиента: `initialize` → уведомление о готовности → `tools/list`.
INITIALIZE_FRAME='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"mcp-server.sh","version":"1"}}}'
INITIALIZED_FRAME='{"jsonrpc":"2.0","method":"notifications/initialized"}'
TOOLS_LIST_FRAME='{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

usage() {
    cat <<'USAGE'
Локальные MCP-серверы проекта: запуск и остановка.

  ./mcp/mcp-server.sh start [--server project|github] [--no-build] [--data-dir DIR]
  ./mcp/mcp-server.sh stop [--server project|github]
  ./mcp/mcp-server.sh restart [--server project|github] [--no-build] [--data-dir DIR]
  ./mcp/mcp-server.sh status [--server project|github]
  ./mcp/mcp-server.sh tools [--server project|github]
  ./mcp/mcp-server.sh logs [--server project|github]
  ./mcp/mcp-server.sh frames [--server project|github]

  start    поднять сервер фоном: pid, канал, кадры и логи — в build/mcp-server модуля
  stop     остановить: SIGTERM, ожидание выхода, при упорстве SIGKILL
  restart  остановить и поднять снова
  status   работает ли и отвечает ли на tools/list (код возврата 1, если не запущен)
  tools    что сервер объявляет клиенту (для project — то же, что ./gradlew :mcp:mcpTools,
           для github — :mcp-github:mcpTools)
  logs     логи сервера — поток ошибок текущего запуска
  frames   кадры протокола — поток вывода текущего запуска

Какой сервер обслуживается (по умолчанию project):

  project  данные проекта: инварианты и профиль (:mcp)
  github   инструмент get_repositories к GitHub API (:mcp-github). Токен и адрес API
           берутся из окружения самого скрипта (GITHUB_TOKEN, GITHUB_API_BASE) и
           передаются процессу сервера как есть; без токена сервер отвечает отказом
           на вызов инструмента, а не падает при запуске

Опции start/restart:
  --no-build       не собирать fat JAR перед запуском (по умолчанию собирается)
  --data-dir DIR   каталог данных с invariants.json и profile.json — только для сервера
                   project (по умолчанию server/data в корне репозитория — те же файлы,
                   что читает сервер приложения)

Состояние службы: <модуль>/build/mcp-server (server.pid, server.log, frames.log, stdin.fifo).
Кадр в сервер можно положить руками: printf '%s\n' '{"jsonrpc":"2.0","id":9,"method":"tools/list"}' > <FIFO>
USAGE
}

say() { printf '%s\n' "$*"; }
fail() { printf '%s\n' "$*" >&2; exit 1; }

# pid из файла; пусто и код 1, если файла нет или в нём не число (мусор не должен
# приводить к «kill» неизвестно чего).
pid_from() {
    local file="$1" pid
    [ -f "$file" ] || return 1
    pid="$(cat "$file" 2>/dev/null || true)"
    case "$pid" in
        '' | *[!0-9]*) return 1 ;;
    esac
    printf '%s' "$pid"
}

alive() { [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null; }

server_pid() { pid_from "$PID_FILE"; }
keeper_pid() { pid_from "$KEEPER_FILE"; }

# Файлы состояний от прошлого запуска: процесс умер, файл остался. Убираем, чтобы
# `status` не считал чужой pid живым сервером.
cleanup_stale() {
    local pid
    if pid="$(server_pid)" && ! alive "$pid"; then rm -f "$PID_FILE"; fi
    if pid="$(keeper_pid)" && ! alive "$pid"; then rm -f "$KEEPER_FILE"; fi
}

file_size() {
    if [ -f "$1" ]; then wc -c <"$1" | tr -d ' '; else printf '0'; fi
}

file_age() {
    local modified
    modified="$(stat -f %m "$1" 2>/dev/null || stat -c %Y "$1" 2>/dev/null || true)"
    if [ -n "$modified" ]; then printf '%s' "$(( $(date +%s) - modified ))"; fi
}

build_jar() {
    if [ "$NO_BUILD" = "1" ]; then
        [ -f "$JAR" ] || fail "нет $JAR, а сборка отключена (--no-build): соберите его через ./gradlew $BUILD_TASK"
        return 0
    fi
    say "сборка: ./gradlew $BUILD_TASK"
    (cd "$ROOT" && ./gradlew "$BUILD_TASK" --console=plain -q)
    [ -f "$JAR" ] || fail "после сборки нет $JAR"
}

# Держатель канала ввода: открывает FIFO на запись и живёт, пока сервер работает.
# Без него сервер получил бы конец ввода сразу после старта и вышел — для сервера это
# «клиент закрыл соединение», и он был бы прав.
start_keeper() {
    nohup sleep "$KEEPER_LIFETIME" >"$FIFO" 2>/dev/null &
    echo "$!" >"$KEEPER_FILE"
}

kill_keeper() {
    local pid
    if pid="$(keeper_pid)"; then kill -TERM "$pid" 2>/dev/null || true; fi
    rm -f "$KEEPER_FILE"
}

start() {
    local pid
    if pid="$(server_pid)" && alive "$pid"; then
        say "сервер уже запущен: pid $pid"
        say "  состояние: $STATE"
        return 0
    fi

    cleanup_stale
    build_jar
    mkdir -p "$STATE"
    [ -p "$FIFO" ] || mkfifo "$FIFO"
    : >"$LOG_FILE"
    : >"$FRAMES_FILE"

    start_keeper
    # Инструменты сервера проекта читают те же файлы, что сервер приложения, поэтому путь
    # к данным задаётся явно: иначе они читались бы относительно текущего каталога, и
    # «правила проекта» зависели бы от того, откуда запустили скрипт. Серверу GitHub
    # добавлять нечего: токен и адрес API он наследует из окружения скрипта.
    local launch_env=()
    if [ "$SERVER" = "project" ]; then
        launch_env+=(
            "INVARIANT_FILE=$DATA_DIR/invariants.json"
            "PROFILE_FILE=$DATA_DIR/profile.json"
        )
    fi
    # `${…[@]+…}` — совместимость с bash 3.2 в macOS: пустой массив под `set -u`
    # иначе считается необъявленной переменной.
    env ${launch_env[@]+"${launch_env[@]}"} \
        nohup "$JAVA" -cp "$JAR" "$MAIN" <"$FIFO" >"$FRAMES_FILE" 2>>"$LOG_FILE" &
    local server=$!
    echo "$server" >"$PID_FILE"

    sleep "$STARTUP_GRACE"
    if ! alive "$server"; then
        kill_keeper
        rm -f "$PID_FILE"
        say "сервер не запустился; последние строки лога:"
        tail -n "$TAIL_LINES" "$LOG_FILE" >&2 || true
        exit 1
    fi

    say "сервер запущен: pid $server"
    say "  сервер:      $SERVER ($MAIN)"
    if [ "$SERVER" = "project" ]; then
        say "  данные:      $DATA_DIR"
    fi
    say "  логи:        $LOG_FILE"
    say "  кадры:       $FRAMES_FILE"
    say "  остановить:  $0 stop"
}

stop() {
    local pid
    if ! pid="$(server_pid)" || ! alive "$pid"; then
        cleanup_stale
        say "сервер не запущен"
        return 0
    fi

    kill -TERM "$pid" 2>/dev/null || true
    local tenths=$((STOP_TIMEOUT * 5)) i=0
    while alive "$pid" && [ "$i" -lt "$tenths" ]; do
        sleep 0.2
        i=$((i + 1))
    done

    if alive "$pid"; then
        say "сервер не вышел за ${STOP_TIMEOUT} с — снимаю силой"
        kill -KILL "$pid" 2>/dev/null || true
        sleep 0.2
        if alive "$pid"; then
            kill_keeper
            fail "процесс $pid не снимается; проверьте его вручную"
        fi
    fi

    kill_keeper
    rm -f "$PID_FILE"
    say "сервер остановлен: pid $pid"
}

status() {
    local pid
    if ! pid="$(server_pid)" || ! alive "$pid"; then
        cleanup_stale
        say "сервер не запущен (канал и логи: $STATE)"
        return 1
    fi

    local age
    age="$(file_age "$PID_FILE")"
    if [ -n "$age" ]; then
        say "сервер работает: pid $pid, запущен ${age} с назад"
    else
        say "сервер работает: pid $pid"
    fi

    # Живой процесс — ещё не работающий сервер: проверяем ответом на tools/list.
    # Кадр кладётся в FIFO, ответ сервер пишет в файл кадров — это проверка по протоколу,
    # а не по состоянию процесса.
    local before after i=0
    before="$(file_size "$FRAMES_FILE")"
    printf '%s\n' "$INITIALIZE_FRAME" "$INITIALIZED_FRAME" "$TOOLS_LIST_FRAME" >"$FIFO"
    while [ "$i" -lt $((PROBE_TIMEOUT * 5)) ]; do
        after="$(file_size "$FRAMES_FILE")"
        if [ "$after" -gt "$before" ] && grep -q '"result"' "$FRAMES_FILE"; then
            say "сервер отвечает: кадров получено на $((after - before)) байт"
            return 0
        fi
        sleep 0.2
        i=$((i + 1))
    done

    say "сервер не ответил на tools/list за ${PROBE_TIMEOUT} с; смотрите $LOG_FILE" >&2
    return 1
}

tools() {
    [ -f "$JAR" ] || build_jar
    "$JAVA" -cp "$JAR" "$MAIN" --list-tools
}

show_file() {
    local file="$1" what="$2"
    if [ ! -f "$file" ]; then
        say "сервер ещё не запускался: $what появится после start"
        return 0
    fi
    if [ ! -s "$file" ]; then
        say "$what пока пуст: $file"
        return 0
    fi
    say "$what ($file):"
    tail -n "$TAIL_LINES" "$file"
}

NO_BUILD=0
DATA_DIR="$ROOT/server/data"
DATA_DIR_GIVEN=0

command="${1:-help}"
if [ $# -gt 0 ]; then shift; fi

while [ $# -gt 0 ]; do
    case "$1" in
        --server)
            shift
            [ $# -gt 0 ] || fail "--server требует имя: project или github"
            SERVER="$1"
            ;;
        --no-build) NO_BUILD=1 ;;
        --data-dir)
            shift
            [ $# -gt 0 ] || fail "--data-dir требует каталог"
            DATA_DIR="$1"
            DATA_DIR_GIVEN=1
            ;;
        -h | --help)
            usage
            exit 0
            ;;
        *)
            usage >&2
            exit 2
            ;;
    esac
    shift
done

select_server

# У сервера GitHub нет своих файлов данных: инструменты читают GitHub по токену, и
# каталог данных к ним не относится. Молча принять опцию значило бы сделать вид, что
# она что-то меняет.
if [ "$SERVER" != "project" ] && [ "$DATA_DIR_GIVEN" = "1" ]; then
    fail "--data-dir относится к серверу project: у сервера $SERVER своих файлов данных нет"
fi

case "$DATA_DIR" in
    /*) ;;
    *) DATA_DIR="$PWD/$DATA_DIR" ;;
esac

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA="$(command -v java || true)"
    [ -n "$JAVA" ] || fail "не нашёл java: ни в JAVA_HOME, ни в PATH"
fi

case "$command" in
    start) start ;;
    stop) stop ;;
    restart)
        stop
        start
        ;;
    status) status ;;
    tools) tools ;;
    logs) show_file "$LOG_FILE" "логи сервера" ;;
    frames) show_file "$FRAMES_FILE" "кадры протокола" ;;
    help | -h | --help) usage ;;
    *)
        usage >&2
        exit 2
        ;;
esac
