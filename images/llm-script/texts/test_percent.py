import unittest

from demo.pricing import total


class PercentDiscountTest(unittest.TestCase):
    def test_ten_percent(self):
        self.assertEqual(total([200.0], discount=10), 180.0)

    def test_no_discount(self):
        self.assertEqual(total([19.99, 5.01]), 25.0)


if __name__ == "__main__":
    unittest.main()
