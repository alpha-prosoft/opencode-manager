def total(prices: list[float], discount: float = 0.0) -> float:
    subtotal = sum(prices)
    return round(subtotal - subtotal * discount, 2)
