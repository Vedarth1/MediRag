FROM python:3.11-slim

WORKDIR /app

# Install dependencies first — Docker layer caching means this layer
# is reused on rebuilds unless requirements.txt actually changes
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Pre-download the model into the image at build time, not at first request.
# Without this, the very first container start would pause for ~30s
# downloading ~80MB from HuggingFace — this bakes it into the image instead.
RUN python -c "from sentence_transformers import SentenceTransformer; \
    SentenceTransformer('sentence-transformers/all-MiniLM-L6-v2')"

COPY main.py .

EXPOSE 8086

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8086"]