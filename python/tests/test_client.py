import unittest

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


if __name__ == "__main__":
    unittest.main()
