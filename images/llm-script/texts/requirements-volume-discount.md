# Volume discount tiers

## Goal

Orders get a discount that grows with the number of items, replacing the single 10% rule sales applies by hand today.

## Stakeholders

- **Sales**: defines the tiers and wants to change them without a release.
- **Customers**: see the discount on the order summary before they pay.
- **Finance**: needs the discount on every invoice line for reporting.

## Functional requirements

| ID | Requirement | Acceptance criteria |
|----|-------------|---------------------|
| F1 | The discount depends on the number of items in the order. | 10 to 49 items: 5%. 50 to 199 items: 10%. 200 items or more: 15%. |
| F2 | The tier boundaries are inclusive at the lower end. | An order of exactly 10 items gets 5%; an order of 9 items gets none. |
| F3 | The discounted total is rounded to cents once, at the end. | `total(prices, discount)` is used; no intermediate value is rounded or truncated. |
| F4 | The order summary names the tier that was applied. | The summary shows the percentage and the number of items it is based on. |

## Non-functional requirements

- Tiers are configuration, not code: changing a percentage needs no deployment.
- The calculation is covered by unit tests at every boundary (9, 10, 49, 50, 199, 200 items).

## Assumptions

- Items are counted per order, not per product.
- Volume discounts do not combine with a customer's individual discount; the larger one wins.

## Risks

- An off-by-one at a boundary gives or withholds a discount for many orders; the boundary tests are mandatory.
- Truncating instead of rounding loses a cent on some totals (for example 8.20 becomes 8.19).

## Open questions

1. Do returned items reduce the tier of an order that was already invoiced?
2. Should the tiers differ per country or currency?
