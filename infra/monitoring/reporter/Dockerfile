FROM python:3.12-alpine
RUN apk add --no-cache tzdata
COPY daily_report.py /app/daily_report.py
CMD ["python", "/app/daily_report.py"]
