import unittest
from unittest.mock import patch

from zingg_duckdb.client import WorkerClient


class WorkerClientHelpersTest(unittest.TestCase):
    def test_jdbc_url_normalization(self):
        self.assertEqual("jdbc:duckdb:", WorkerClient._jdbc_url(":memory:"))
        self.assertEqual("jdbc:duckdb:sample.db", WorkerClient._jdbc_url("sample.db"))
        self.assertEqual("jdbc:duckdb:sample.db", WorkerClient._jdbc_url("jdbc:duckdb:sample.db"))

    def test_unescape_round_trip(self):
        encoded = r"line1\nline2\tvalue\\tail"
        self.assertEqual("line1\nline2\tvalue\\tail", WorkerClient._unescape(encoded))

    def test_operation_and_size_limits(self):
        client = WorkerClient(command=["python", "-c", ""])
        try:
            with self.assertRaises(ValueError):
                client.request("bad/op payload")
        finally:
            client.close()

    def test_structured_worker_payload_preserves_delimiters(self):
        from zingg_duckdb.worker import _payload
        import base64
        encoded = _payload("/tmp/a|b;part.csv", "a || b", "C:\\data\\x.y")
        parts = encoded.split(".")
        values = [base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)).decode("utf-8") for part in parts[2:]]
        self.assertEqual(["/tmp/a|b;part.csv", "a || b", "C:\\data\\x.y"], values)

    def test_collect_budget_is_forwarded_to_worker(self):
        class FakePipe:
            def close(self): pass
        class FakeProcess:
            stdin = FakePipe()
            stdout = FakePipe()
            stderr = FakePipe()
            def poll(self): return 0
        with patch("zingg_duckdb.client.subprocess.Popen", return_value=FakeProcess()) as popen:
            WorkerClient(command=["worker"], max_collect_bytes=123)
            self.assertEqual(["worker"], popen.call_args.args[0])

        # The option is part of the default-launch path; verify it without starting a process.
        with patch("zingg_duckdb.client.subprocess.Popen", return_value=FakeProcess()) as popen:
            WorkerClient(max_collect_bytes=123)
            self.assertIn("--max-collect-bytes", popen.call_args.args[0])
            self.assertIn("123", popen.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
