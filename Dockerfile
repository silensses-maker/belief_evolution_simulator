# ── Stage 1: build (sbt stage genera la app empaquetada) ─────────────────────
FROM eclipse-temurin:21-jdk-noble AS builder

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y \
    curl \
    wget \
    unzip \
    git \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# Install SBT
RUN echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | tee /etc/apt/sources.list.d/sbt_old.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && \
    apt-get install -y sbt && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Cachear dependencias antes de copiar el código
COPY build.sbt .
COPY project/ project/
RUN sbt update

COPY src/ src/
RUN sbt stage

# ── Stage 2: runtime (solo JRE, arranque en segundos) ────────────────────────
FROM eclipse-temurin:21-jre-noble

RUN useradd -m -u 1001 appuser

WORKDIR /app
USER appuser

COPY --from=builder --chown=appuser:appuser /app/target/universal/stage /app

EXPOSE 9000

# Heap y flags extra se pueden pasar via JAVA_OPTS (lo respeta el script de
# native-packager), p.ej. JAVA_OPTS="-Xmx8g" en docker-compose.
CMD ["/app/bin/extended_model", "-J--add-modules=jdk.incubator.vector", "-J--enable-preview"]
