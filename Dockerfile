# Приватный AI-сервис (день 30): образ собирается из тех же исходников, что и
# `./gradlew :server:run`, — второй сборки сервиса в проекте нет. Серверная половина
# и клиентская отличаются только тем, что запускается: код один.
#
# Почему в образе собирается Gradle, а не копируется готовый JAR: `docker compose up --build`
# должен работать на чистой машине одной командой, а готовая сборка на хосте — это второй шаг,
# который легко забыть и получить образ со старым кодом.

FROM eclipse-temurin:21-jdk AS build

WORKDIR /src

# Клиентские модули (`:app:*`) требуют Android SDK, которого в образе нет и сервису не нужен.
# `--configure-on-demand` настраивает только проекты, нужные задаче `:server:jar`, поэтому
# плагин Android в сборке образа не применяется вовсе. Кэш конфигурации отключён: он не
# совместим с настройкой по требованию, а разовая сборка от кэша ничего не выигрывает.
COPY . .

RUN ./gradlew --no-daemon --no-configuration-cache --configure-on-demand :server:jar

# В рабочий образ уезжает только JRE и один fat JAR: ни исходников, ни Gradle, ни кэшей
# зависимостей в нём нет — образ меньше, а вместе с ним меньше и то, что может утечь.
FROM eclipse-temurin:21-jre

# curl нужен проверке здоровья контейнера (`healthcheck` в docker-compose.yml): без него
# Docker не может спросить сервис о состоянии, и «запущен» означало бы «процесс жив».
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Сервису не нужны права root: он слушает порт выше 1024 и пишет только в том с данными.
# Пользователь создаётся до копирования файлов, чтобы каталоги принадлежали ему.
RUN useradd --system --create-home --shell /usr/sbin/nologin service \
    && mkdir -p /data /app && chown -R service:service /data /app

WORKDIR /app

COPY --from=build /src/server/build/libs/server-*.jar /app/service.jar

# Данные сервиса — история чатов (SQLite), профиль, память и инварианты — лежат в каталоге
# тома, а не в слое образа: перезапуск контейнера обязан сохранять диалоги (пункт 4.1
# задания), а слой образа для этого не годится — он часть образа, а не состояние сервиса.
ENV CHAT_DB=/data/service-chats.db \
    MEMORY_FILE=/data/memory.json \
    PROFILE_FILE=/data/profile.json \
    INVARIANT_FILE=/data/invariants.json

# Умолчания сводки прошлого чата и потолка списка чатов: те же значения, что в .env.example.
# Промпт сводки не зашит — он многострочный, и его задаёт запуск, а не образ.
ENV SERVICE_SUMMARY_ENABLED=true \
    SERVICE_SUMMARY_MAX_TOKENS=256 \
    SERVICE_SUMMARY_MAX_MESSAGES=40 \
    SERVICE_CHATS_LIST_LIMIT=100
VOLUME ["/data"]

EXPOSE 8080

USER service

# Память JVM настраивается снаружи переменной JAVA_TOOL_OPTIONS: её понимает сама JVM,
# поэтому в команде запуска нет шелла, а ограничение памяти не зашито в образ.
ENTRYPOINT ["java", "-jar", "/app/service.jar"]
