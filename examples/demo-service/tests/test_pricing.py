import unittest

from demo.pricing import total


class TotalTest(unittest.TestCase):
    def test_sum(self):
        self.assertEqual(total([1.0, 2.5]), 3.5)

    def test_discount(self):
        self.assertEqual(total([10.0], discount=0.1), 9.0)


if __name__ == "__main__":
    unittest.main()
