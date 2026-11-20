# Python AI 服务：仅安装运行期依赖，非 root 用户运行
FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

RUN useradd --create-home --uid 10001 app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
COPY prompts ./prompts

USER app
EXPOSE 8000

# 单进程 uvicorn：个人项目规模下单进程足够，多副本由 Compose 扩缩容
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
