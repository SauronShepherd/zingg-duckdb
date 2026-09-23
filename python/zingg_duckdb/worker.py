from __future__ import annotations
from .client import WorkerClient

def _payload(*fields: str) -> str:
    import base64
    encoded = [base64.urlsafe_b64encode(value.encode("utf-8")).decode("ascii").rstrip("=") for value in fields]
    return "v1." + str(len(encoded)) + "." + ".".join(encoded)

class DuckWorker:
    """Small typed facade over the line-oriented worker protocol."""
    def __init__(self, command: list[str] | None = None, database: str = ":memory:", **worker_options):
        self.client = WorkerClient(command, database, **worker_options)
    def ping(self) -> str:
        return self.client.request("ping")
    def status(self) -> dict[str, str]:
        values = self.client.request("status").split(";")
        return dict(item.split("=", 1) for item in values if "=" in item)
    def explain(self, sql: str) -> str:
        return self.client.explain(sql)
    def count(self, sql: str) -> int:
        return int(self.client.request("count " + sql))
    def run(self, phase: str, input_path: str | list[str], output_path: str, predicate: str = "TRUE", classifier_model: str | None = None) -> int:
        inputs = input_path if isinstance(input_path, list) else [input_path]
        fields = [phase, output_path, predicate, classifier_model or ""] + inputs
        payload = _payload(*fields)
        return int(self.client.request("run " + payload))
    def train(self, input_path: str | list[str], artifact_path: str, blocking_expression: str,
              blocking_column: str, max_rows: int = 1_000_000) -> str:
        inputs = input_path if isinstance(input_path, list) else [input_path]
        payload = _payload("", artifact_path, blocking_expression, blocking_column, str(max_rows), *inputs)
        return self.client.request("train " + payload)
    def train_classifier(self, input_path: str | list[str], artifact_path: str, label_column: str,
                         features: list[str], max_rows: int = 1_000_000, iterations: int = 200,
                         learning_rate: float = 0.05, l2: float = 1e-4,
                         profile: str = "zingg-0.7.0") -> str:
        inputs = input_path if isinstance(input_path, list) else [input_path]
        payload = _payload("", artifact_path, label_column, ",".join(features), str(max_rows),
                           str(iterations), str(learning_rate), str(l2), profile, *inputs)
        return self.client.request("train " + payload)
    def close(self) -> None:
        try:
            self.client.request("shutdown")
        finally:
            self.client.close()
    def __enter__(self): return self
    def __exit__(self, *_): self.close()
