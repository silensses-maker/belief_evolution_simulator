# ── Polarization measures (#129): the pol_measures version, in ONE place ─────────────────────
# Commit of github.com/Ulvenforst/pol_measures (Python package `measures`) installed for the
# polarization worker (polarization/worker.py). Bumping it is a one-line change here; see the
# README, "Polarization measures", for the checks to run after a bump. numpy and SciPy are pinned
# too, so a rebuild of the same commit computes the same values.
ARG POL_MEASURES_REF=0e05564ed0b7b23de368dd19becac3a1de4ef745
ARG POL_NUMPY_VERSION=2.5.4
ARG POL_SCIPY_VERSION=1.18.1

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

# ── Stage 2: pol_measures venv (#129) ────────────────────────────────────────
# Same base as the runtime, so the venv's python3 is the runtime's /usr/bin/python3. git and the
# venv tooling stay in this stage; only /opt/polarization/venv is copied. Wheels only for numpy and
# SciPy (no compiler anywhere), no pip cache, and pip itself and the packages' bundled test suites
# are removed from the venv (never imported at runtime; the self-test below runs without them).
FROM eclipse-temurin:21-jre-noble AS polarization

ARG POL_MEASURES_REF
ARG POL_NUMPY_VERSION
ARG POL_SCIPY_VERSION
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    python3-venv \
    git \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN python3 -m venv /opt/polarization/venv && \
    /opt/polarization/venv/bin/pip install --no-cache-dir --disable-pip-version-check \
        --only-binary=numpy,scipy \
        "numpy==${POL_NUMPY_VERSION}" "scipy==${POL_SCIPY_VERSION}" \
        "git+https://github.com/Ulvenforst/pol_measures.git@${POL_MEASURES_REF}" && \
    /opt/polarization/venv/bin/pip uninstall -y --disable-pip-version-check pip && \
    find /opt/polarization/venv/lib -depth -type d -name tests -path "*/site-packages/*" -exec rm -rf {} +

# ── Stage 3: runtime (solo JRE, arranque en segundos) ────────────────────────
FROM eclipse-temurin:21-jre-noble

ARG POL_MEASURES_REF

# python3 (interpreter only, no pip / venv / compilers) runs the polarization worker (#129).
RUN apt-get update && apt-get install -y --no-install-recommends python3 \
    && rm -rf /var/lib/apt/lists/*

RUN useradd -m -u 1001 appuser

WORKDIR /app

COPY --from=builder --chown=appuser:appuser /app/target/universal/stage /app

# Root-owned and read-only for appuser: the worker and the venv never write to disk.
COPY --from=polarization /opt/polarization/venv /opt/polarization/venv
COPY polarization/worker.py polarization/worker_selftest.py /app/polarization/

# Informational (startup log, self-test): the worker reads the commit from the installed package.
ENV POL_MEASURES_REF=${POL_MEASURES_REF}

USER appuser

EXPOSE 9000

# Heap y flags extra se pueden pasar via JAVA_OPTS (lo respeta el script de
# native-packager), p.ej. JAVA_OPTS="-Xmx8g" en docker-compose.
CMD ["/app/bin/extended_model", "-J--add-modules=jdk.incubator.vector", "-J--enable-preview"]
