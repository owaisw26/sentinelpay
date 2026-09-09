# ADR 0008: Bounded mixed-population anomaly model

## Status

Accepted for Day 14.

## Context

The deterministic Day 12 rules detect known risk patterns but cannot identify
novel combinations of otherwise ordinary-looking features. Day 14 adds an
Isolation Forest, which can identify observations unlike its training
population but cannot determine intent or prove fraud.

The model is trained on synthetic data. A clean, label-filtered training set
would make the meaning of "normal" easy to explain, but would also use labels
during fitting and overstate how clean a production baseline is. A mixed
population better preserves the unsupervised boundary. Its central risk is
contamination: sufficiently frequent fraud patterns can be learned as normal.

The mentoring choice was Option B. The assumed core invariant is that the
deterministic rules remain authoritative and the anomaly model can only add a
bounded contribution. The most likely failure mode is the mixed population
normalizing common fraud. Option A, fitting only labelled legitimate examples,
was rejected because its artificially clean baseline is less representative of
an unsupervised production deployment.

## Decision

Fit a seeded Isolation Forest on the complete synthetic training population
without consulting labels. Use labels only on a separate, later validation
cohort to select the highest-precision threshold whose recall is at least 80%.
Ties are resolved deterministically in favour of the higher threshold.

The training and online paths share one versioned feature transformer. Data is
ordered deterministically, training and validation use disjoint customer
cohorts and time ranges, preprocessing statistics are fitted on training data
only, and the estimator uses a fixed seed and one fitting thread.

An observation below the selected threshold contributes no points. An
anomalous observation contributes 20 points and a severe anomaly contributes
30 points. The contribution is added to, and never replaces or reduces, the
deterministic rule score. Existing approval, hold, and block thresholds remain
unchanged. Explanations are stable reason codes derived from the largest
feature deviations from training medians.

The versioned artifact contains the estimator, threshold, explanation
statistics, transformer version, dependency versions, and training seed. A
canonical metrics manifest records precision, recall, dataset counts, and the
artifact SHA-256. The worker verifies the checksum before loading the model.

## Alternatives considered

- Fit only labelled legitimate training rows and map anomaly severity to a
  continuous bounded contribution. This produces a cleaner baseline and more
  granular scores, but label filtering makes fitting partly supervised and the
  continuous mapping is more sensitive to minor numerical drift.
- Allow the anomaly model to directly hold or block payments. This gives the
  model more influence but incorrectly treats unusualness as a verdict and
  makes false positives capable of bypassing deterministic policy.

## Invariants and consequences

- Deterministic rule points and reason codes are never removed or reduced by
  the anomaly model.
- The anomaly contribution is always one of 0, 20, or 30 points.
- Model labels never enter fitting or preprocessing; they are validation-only.
- Training and online evaluation call the same transformation implementation.
- The same source event and feature/ruleset/model versions produce the same
  decision identity and output.
- Rebuilding in the pinned environment from the same seed must reproduce the
  metrics and artifact checksum.
- Meeting 80% recall on synthetic validation data is evidence about the
  generator, not a production-performance claim.
- Drift in model scores, anomaly rates, precision, and recall must be monitored;
  contamination can otherwise make repeated fraud patterns appear normal.
