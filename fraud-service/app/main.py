from fastapi import FastAPI

app = FastAPI (
    title="SentinentalPay Fraud Service",
    version="0.1.0"
)

@app.get("/health")
def health():
    return {
        "status": "up"
    }