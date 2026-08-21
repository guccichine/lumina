import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from accounts import redirect_uri, status


class AccountsTests(unittest.TestCase):
    def test_redirect_uri(self):
        self.assertEqual(redirect_uri("127.0.0.1:8787"), "http://127.0.0.1:8787/api/auth/google/callback")

    def test_status_shape(self):
        data = status("127.0.0.1:8787")
        self.assertIn("google", data)
        self.assertIn("samsung", data)
        self.assertFalse(data["samsung"]["public_api"])
        self.assertTrue(data["apk_url"].endswith("/releases/latest"))


if __name__ == "__main__":
    unittest.main()
