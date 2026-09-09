# Day 14 synthetic dataset statement

## Purpose and composition

The seeded generator creates feature-level AUD payment records solely for
developing and testing SentinelPay's anomaly pipeline. Seed `140014` produces
4,800 training rows from 400 synthetic customers and 1,440 validation rows from
120 different synthetic customers. Training observations start on 1 January
2026; validation observations start on 1 July 2026.

Each record contains a synthetic transaction/customer identifier, event time,
the five risk features used online, a synthetic fraud label, and a cohort name.
The roughly 8% labels select among three invented fraud-pattern distributions.
No real people, transactions, names, wallet IDs, or device tokens are present.

## Split and leakage controls

Training and validation have disjoint customer identifiers and non-overlapping
time periods. Feature statistics and Isolation Forest fitting use only training
rows. Validation labels are used only to select and report the operating
threshold. The shared production transformer is called directly for both
cohorts, preventing a second offline transformation from drifting away from
serving behavior.

## Limitations

The labels and distributions are assumptions written by the project author,
not observed fraud outcomes. Strong validation metrics mostly show that the
model can recover those assumptions. The data omits protected attributes,
merchant networks, chargebacks, seasonality, customer tenure, geographic
behavior, and adaptive attackers. It cannot establish production accuracy,
fairness, calibration, or business value.

Rebuild with:

```bash
cd fraud-service
python -m app.train_anomaly
```
