from fastapi import FastAPI

app = FastAPI(
    title="SentinelPay Fraud Service",
    version="0.1.0"
)

@app.get("/health")
def health():
    return {
        "status": "up"
    }
