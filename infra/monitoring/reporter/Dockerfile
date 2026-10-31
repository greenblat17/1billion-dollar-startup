FROM python:3.12-alpine
RUN apk add --no-cache tzdata
COPY daily_report.py credit_monitor.py telegram_delivery.py /app/
CMD ["python", "/app/daily_report.py"]
