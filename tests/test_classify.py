import unittest
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from classify import classify


class ClassifyTests(unittest.TestCase):
    def test_screenshot_filename(self):
        result = classify(filename="Screenshot_20260328-214410.png", width=1080, height=2400)
        self.assertEqual(result["type"], "screenshots")

    def test_receipt_filename(self):
        result = classify(filename="receipt_harbor_cafe.png", width=1240, height=1800)
        self.assertEqual(result["type"], "documents")

    def test_dog_filename(self):
        result = classify(filename="golden-retriever-field.jpg")
        self.assertEqual(result["type"], "animals")

    def test_food_filename(self):
        result = classify(filename="margherita-pizza.jpg")
        self.assertEqual(result["type"], "food")

    def test_night_filename(self):
        result = classify(filename="rainy-night-street.jpg")
        self.assertEqual(result["type"], "night")

    def test_folder_hint(self):
        result = classify(filename="IMG_1002.jpg", rel_path="People/IMG_1002.jpg")
        self.assertEqual(result["type"], "people")

    def test_document_visual(self):
        result = classify(
            filename="scan.png",
            visual={"brightness": 240, "saturation": 0.04, "whiteFrac": 0.8, "darkFrac": 0.01, "edge": 0.2},
        )
        self.assertEqual(result["type"], "documents")

    def test_search_tokens_not_needed_here(self):
        result = classify(filename="vintage-coupe-highway.jpg")
        self.assertEqual(result["type"], "vehicles")


if __name__ == "__main__":
    unittest.main()
