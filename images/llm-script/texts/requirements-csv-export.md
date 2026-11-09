# CSV export of monthly invoices

## Goal

Customers can download the invoices of one month as a CSV file from the billing page, so that they can load them into their own bookkeeping without asking support.

## Stakeholders

- **Customers' finance teams**: use the file every month end.
- **Support**: answers today's export requests by hand (about 40 a month).
- **Billing team**: owns the billing page and the invoice data.

## Functional requirements

| ID | Requirement | Acceptance criteria |
|----|-------------|---------------------|
| F1 | The billing page offers **Export CSV** for a selected month. | The button is shown for every month that has at least one invoice. |
| F2 | The file has one row per invoice line. | Columns: invoice number, date, customer reference, description, net amount, discount, total, currency. |
| F3 | Totals in the file equal the totals on the invoice. | For every invoice, the sum of its rows equals the amount `demo.pricing.total` returns, to the cent. |
| F4 | The export covers exactly one calendar month. | Invoices dated on the first and the last day of the month are included; others are not. |
| F5 | Only the customer's own invoices are exported. | A user of another customer gets no file and no hint that the invoices exist. |

## Non-functional requirements

- A month with 5,000 invoice lines is exported in under 5 seconds.
- The file is UTF-8 with a header row, comma separated, values quoted where needed (RFC 4180).
- Amounts use a dot as decimal separator and two decimals, whatever the customer's locale.

## Assumptions

- Invoices never change after they are issued, so an export can be repeated with the same result.
- Discounts are already applied by `total(prices, discount)`; the export shows them, it does not calculate them.

## Risks

- Spreadsheet programs may read invoice numbers with leading zeros as numbers. Quote them as text.
- Large customers may ask for a year at once; exporting month by month keeps the load predictable.

## Open questions

1. Should credit notes appear as negative rows or in a file of their own?
2. Which time zone decides the month an invoice belongs to: the customer's or UTC?
3. Is the export needed in other formats (XLSX, DATEV) later, and should the design allow for it now?
