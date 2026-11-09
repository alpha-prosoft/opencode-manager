def total(prices: list[float], discount: float = 0.0) -> float:
    subtotal = sum(prices)
    return round(subtotal - subtotal * discount, 2)


def bulk_total(prices: list[float]) -> float:
    """Orders of 10 or more items get 10% off."""
    discount = 0.1 if len(prices) > 10 else 0.0
    return int(total(prices, discount) * 100) / 100
