# Analysis image for the thesis experiments (tables and figures). Built by docker-compose.thesis.yml.
FROM python:3.12-slim

WORKDIR /app/scripts/thesis
COPY src/scripts/thesis/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY src/scripts/thesis/ .

ENTRYPOINT ["python", "-X", "utf8", "analyze_all.py"]
