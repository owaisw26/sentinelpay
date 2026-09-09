# SentinelPay Isolation Forest v1 model card

## Intended use

`isolation-forest-v1` adds a bounded anomaly contribution to SentinelPay's
deterministic payment-screening rules. It is a portfolio demonstration trained
only on synthetic AUD payment features. It must not be used as a fraud verdict,
as identity proof, or with real customers without representative data,
fairness review, monitoring, and human-governed model risk controls.

## Model and decision policy

- Algorithm: scikit-learn Isolation Forest with 256 trees, a 512-row sample,
  fixed seed `140014`, and one fitting thread.
- Fit population: mixed labelled synthetic transactions; labels are never
  supplied to model fitting.
- Threshold: chosen on a disjoint, chronologically later customer cohort by
  maximizing precision subject to recall of at least 80%.
- Contribution: 0 below threshold, 20 for anomalous, and 30 for severely
  anomalous. The deterministic rule contribution is unchanged.
- Explanations: up to two stable reason codes identify the largest feature
  deviations from training medians, scaled by training interquartile ranges.

Exact validation metrics, dependency versions, threshold values, and the model
artifact SHA-256 are recorded in
`artifacts/isolation_forest_v1.metrics.json`.

The forest is stored as canonical JSON rather than an executable pickle. The
online scorer verifies its SHA-256 before parsing it and implements the same
Isolation Forest path-length formula used during validation.

## Features

The shared `AnomalyFeatureTransformer` produces log amount, first-time-payee,
device-change, capped ten-minute transaction count, and accepted NameCheck
mismatch. No names, wallet identifiers, device identifiers, or labels enter the
model vector.

## Limitations and risks

- Synthetic performance is not evidence of production performance.
- A mixed population can normalize fraud patterns when contamination grows.
- Novel legitimate behavior can appear anomalous and create false positives.
- Deviation explanations describe unusual inputs; they are not causal
  explanations of model internals or evidence of criminal intent.
- Population drift, queue delay, or upstream feature changes can invalidate the
  selected threshold.
- The synthetic generator models feature distributions, not the complete
  temporal, demographic, adversarial, or network structure of real payments.

## Required monitoring and governance

Monitor anomaly-score distribution, alert rates, feature drift, precision,
recall, model checksum/version, and outcomes by reviewed operational cohorts.
Retraining requires a new immutable model version, evaluation, review, and
deployment; it must never rewrite an already-final risk decision.
