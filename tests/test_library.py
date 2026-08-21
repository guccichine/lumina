import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from library import Library
from seed_demo import write_png, make_receipt


class LibraryFlowTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.lib = Library()
        self.lib.set_root(str(self.tmp), persist=False)

    def _png(self, name: str, w: int = 64, h: int = 64, color=(20, 80, 30)):
        px = bytearray(bytes(color) * (w * h))
        path = self.tmp / "_Inbox" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        write_png(path, w, h, px)
        return path

    def test_scan_files_into_type_folders(self):
        self._png("golden-retriever-field.png")
        self._png("Screenshot_2024-01-01.png", 1080, 2400, (240, 240, 240))
        make_receipt(self.tmp / "_Inbox" / "receipt_harbor_cafe.png")
        state = self.lib.scan()
        types = {p["filename"]: p["type"] for p in state["photos"]}
        self.assertEqual(types["golden-retriever-field.png"], "animals")
        self.assertEqual(types["Screenshot_2024-01-01.png"], "screenshots")
        self.assertEqual(types["receipt_harbor_cafe.png"], "documents")
        folders = {p["filename"]: p["rel_path"].split("/")[0] for p in state["photos"]}
        self.assertEqual(folders["golden-retriever-field.png"], "Animals")
        self.assertEqual(folders["Screenshot_2024-01-01.png"], "Screenshots")
        self.assertEqual(folders["receipt_harbor_cafe.png"], "Documents")
        self.assertTrue((self.tmp / "Animals").is_dir())
        shots = {p["filename"] for p in self.lib.state(query="screenshot")["photos"]}
        self.assertEqual(shots, {"Screenshot_2024-01-01.png"})

    def test_search_and_delete_type(self):
        self._png("golden-retriever-field.png")
        self._png("sleeping-tabby-cat.png")
        self._png("margherita-pizza.png")
        self.lib.scan()
        animals = self.lib.state(typ="animals")
        self.assertEqual(len(animals["photos"]), 2)
        self.assertEqual(next(a["count"] for a in animals["albums"] if a["id"] == "animals"), 2)
        found = self.lib.state(query="pizza")
        self.assertEqual(len(found["photos"]), 1)
        deleted = self.lib.delete(typ="animals")
        self.assertEqual(len(deleted["deleted"]), 2)
        self.assertEqual(self.lib.state()["total"], 1)
        self.assertEqual(self.lib.state(trash=True)["trash_count"], 2)


if __name__ == "__main__":
    unittest.main()
