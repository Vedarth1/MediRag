import logging
from contextlib import asynccontextmanager
from typing import List
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from sentence_transformers import SentenceTransformer

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s - %(message)s"
)
logger = logging.getLogger("embedding-service")

MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2"
EMBEDDING_DIMENSION = 384

# Model is loaded once at startup and reused for every request.
# Loading it per-request would add several seconds of latency each time.
model_holder = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"Loading embedding model: {MODEL_NAME}")
    model_holder["model"] = SentenceTransformer(MODEL_NAME)
    logger.info("Model loaded successfully. Embedding service ready.")
    yield
    logger.info("Shutting down embedding service.")
    model_holder.clear()


app = FastAPI(
    title="MediRAG Embedding Service",
    description="Generates vector embeddings for RAG retrieval",
    version="1.0.0",
    lifespan=lifespan,
)


class EmbedRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=8000,
                       description="Text to embed. Long text should be "
                                   "chunked by the caller before sending.")


class EmbedResponse(BaseModel):
    embedding: List[float]
    dimension: int
    model: str


class BatchEmbedRequest(BaseModel):
    texts: List[str] = Field(..., min_items=1, max_items=64,
                              description="Batch of texts to embed in one call. "
                                          "Used during knowledge base ingestion "
                                          "to avoid one HTTP round-trip per chunk.")


class BatchEmbedResponse(BaseModel):
    embeddings: List[List[float]]
    dimension: int
    model: str
    count: int


@app.get("/health")
def health_check():
    """Used by Docker healthcheck and diagnostic-service startup checks."""
    is_loaded = "model" in model_holder
    return {
        "status": "UP" if is_loaded else "MODEL_NOT_LOADED",
        "model": MODEL_NAME,
        "dimension": EMBEDDING_DIMENSION,
    }


@app.post("/embed", response_model=EmbedResponse)
def embed_text(request: EmbedRequest):
    """
    Embeds a single piece of text.
    Used at query time — e.g. embedding a patient's symptom description
    or a derived query string before running similarity search.
    """
    model = model_holder.get("model")
    if model is None:
        raise HTTPException(status_code=503, detail="Embedding model not loaded yet")

    try:
        vector = model.encode(request.text, normalize_embeddings=True)
        return EmbedResponse(
            embedding=vector.tolist(),
            dimension=len(vector),
            model=MODEL_NAME,
        )
    except Exception as e:
        logger.error(f"Embedding generation failed: {e}")
        raise HTTPException(status_code=500, detail=f"Embedding generation failed: {str(e)}")


@app.post("/embed/batch", response_model=BatchEmbedResponse)
def embed_batch(request: BatchEmbedRequest):
    """
    Embeds multiple texts in a single call.
    Used during knowledge base ingestion — chunking a 50-page medical
    reference document produces dozens of chunks; batching avoids
    making 50+ separate HTTP calls from the Java side.
    """
    model = model_holder.get("model")
    if model is None:
        raise HTTPException(status_code=503, detail="Embedding model not loaded yet")

    try:
        vectors = model.encode(request.texts, normalize_embeddings=True, batch_size=32)
        return BatchEmbedResponse(
            embeddings=[v.tolist() for v in vectors],
            dimension=vectors.shape[1],
            model=MODEL_NAME,
            count=len(vectors),
        )
    except Exception as e:
        logger.error(f"Batch embedding generation failed: {e}")
        raise HTTPException(status_code=500, detail=f"Batch embedding generation failed: {str(e)}")