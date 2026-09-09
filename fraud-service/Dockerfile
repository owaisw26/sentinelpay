FROM python:3.13-slim

WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
RUN useradd --create-home --uid 10001 appuser
COPY --chown=appuser:appuser app ./app
COPY --chown=appuser:appuser artifacts ./artifacts
COPY --chown=appuser:appuser migrations ./migrations

USER appuser

CMD ["python", "-m", "app.worker"]
