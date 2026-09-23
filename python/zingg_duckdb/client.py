from __future__ import annotations
import subprocess
import uuid
import re
from pathlib import Path

class WorkerClient:
    MAX_LINE_CHARS = 8 * 1024 * 1024
    OPERATION = re.compile(r"^[A-Za-z0-9_.-]+$")
    def __init__(self, command: list[str] | None = None, database: str = ":memory:", *, input_root: str | None = None, output_root: str | None = None, threads: int | None = None, memory_bytes: int | None = None, max_temp_bytes: int | None = None, max_jobs: int | None = None, max_rows: int | None = None, max_spill_bytes: int | None = None, max_model_bytes: int | None = None, max_output_bytes: int | None = None, offline: bool = True, allowed_extensions: list[str] | None = None, unsafe_debug_sql: bool = False):
        if command is None:
            root = Path(__file__).resolve().parents[2]
            bundled = root / "runtime" / "java" / "bin" / ("java.exe" if __import__("os").name == "nt" else "java")
            candidates = [
                root / "worker" / "runtime-worker-0.1.0-SNAPSHOT.jar",
                root / "runtime-worker" / "target" / "runtime-worker-0.1.0-SNAPSHOT.jar",
            ]
            jar = next((candidate for candidate in candidates if candidate.exists()), candidates[0])
            if not jar.exists(): raise FileNotFoundError(f"runtime worker jar not found; checked: {candidates}")
            if bundled.exists():
                java = str(bundled)
            elif (root / "worker").is_dir():
                raise FileNotFoundError("bundled Java 21 runtime is missing from distribution: runtime/java")
            else:
                java = "java"
            url = self._jdbc_url(database)
            self._command = [java, "-jar", str(jar), "--url", url]
            if not offline: self._command.append("--online")
            if unsafe_debug_sql: self._command.append("--unsafe-debug-sql")
            for extension in allowed_extensions or []: self._command.extend(("--allow-extension", extension))
            for flag, value in (("--input-root", input_root), ("--output-root", output_root), ("--threads", threads), ("--memory-bytes", memory_bytes), ("--max-temp-bytes", max_temp_bytes), ("--max-jobs", max_jobs), ("--max-rows", max_rows), ("--max-spill-bytes", max_spill_bytes), ("--max-model-bytes", max_model_bytes), ("--max-output-bytes", max_output_bytes)):
                if value is not None: self._command.extend((flag, str(value)))
        else:
            self._command = command
        self._process = subprocess.Popen(self._command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1)
    @staticmethod
    def _jdbc_url(database: str) -> str:
        if database == ":memory:": return "jdbc:duckdb:"
        if database.startswith("jdbc:duckdb:"): return database
        return "jdbc:duckdb:" + database
    def request(self, line: str) -> str:
        if self._process.stdin is None or self._process.stdout is None: raise RuntimeError("worker pipes unavailable")
        operation, _, payload = line.partition(" ")
        if not self.OPERATION.fullmatch(operation):
            raise ValueError("invalid worker operation")
        escape = lambda value: value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
        request_id = uuid.uuid4().hex
        message = "\t".join(escape(value) for value in (request_id, operation, payload))
        if len(message) > self.MAX_LINE_CHARS:
            raise ValueError("worker request exceeds 8 MiB protocol limit")
        self._process.stdin.write(message + "\n"); self._process.stdin.flush()
        response = self._process.stdout.readline()
        if not response: raise RuntimeError("worker exited unexpectedly")
        fields = response.rstrip("\n").split("\t", 2)
        if len(fields) != 3: raise RuntimeError("invalid worker response")
        response_id, status, payload = fields
        if response_id != request_id:
            raise RuntimeError(f"worker response correlation mismatch: expected {request_id}, got {response_id}")
        if status == "error": raise RuntimeError(payload)
        return self._unescape(payload)
    def status(self) -> dict[str, str]:
        values = self.request("status").split(";")
        return dict(item.split("=", 1) for item in values if "=" in item)
    def explain(self, sql: str) -> str:
        return self.request("explain " + sql)
    @staticmethod
    def _unescape(value: str) -> str:
        out: list[str] = []
        escaped = False
        for char in value:
            if escaped:
                out.append({"n": "\n", "t": "\t", "\\": "\\"}.get(char, "\\" + char))
                escaped = False
            elif char == "\\":
                escaped = True
            else:
                out.append(char)
        if escaped: out.append("\\")
        return "".join(out)
    def close(self) -> None:
        try:
            if self._process.poll() is None:
                self._process.terminate()
                try:
                    self._process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self._process.kill()
                    self._process.wait(timeout=5)
        finally:
            for stream in (self._process.stdin, self._process.stdout, self._process.stderr):
                if stream is not None and not stream.closed:
                    stream.close()
    def __enter__(self): return self
    def __exit__(self, *_): self.close()
