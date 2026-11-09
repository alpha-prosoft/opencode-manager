# Build #{{build}} failed: an existing test still passes the discount as a fraction

## What failed

| | |
|---|---|
| Change | #{{change}} on branch `{{branch}}` |
| Job | `{{job}}`, build #{{build}} |
| Stage | Test (`python3 -m unittest -v`) |
| Failing test | `tests.test_pricing.TotalTest.test_discount` |
| Assertion | `9.99 != 9.0` |

The other tests pass, including the two new ones in `tests/test_percent.py`.

## Cause

The change makes `total()` read `discount` as a percentage (`subtotal * discount / 100`). The existing test still calls `total([10.0], discount=0.1)` and expects `9.0`. With the new meaning, 0.1 is a tenth of a percent, so the result is `9.99`.

The agent verified the change with `python3 -m unittest tests.test_percent`, which runs only the new module. The build runs the whole suite.

## Proposed fix

1. Update `tests/test_pricing.py::test_discount` to the new unit: `total([10.0], discount=10)`.
2. Search the callers of `total()` for fractions (`discount=0.`) and convert them in the same change.
3. Run the full suite, `python3 -m unittest`, before submitting.

## Open questions

- Is the new unit wanted for every caller, or only for the checkout API? If only there, convert at that boundary and leave `total()` as it was.
