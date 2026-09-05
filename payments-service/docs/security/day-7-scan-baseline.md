# Day 7 security scan baseline

- Baseline date: 2026-09-05
- Repository commit at scan start: `d9ab457`
- Enforcement status: report-only; high/critical enforcement is planned for
  Day 18 after findings are triaged.

## Results

| Surface | Tool | Result |
|---|---|---|
| Java dependencies | OWASP Dependency-Check 12.2.2 | Initial NVD database population requires an API key or a long rate-limited download; the Maven profile is configured and the first update was started. Do not treat an update failure as a clean scan. |
| Python dependencies | pip-audit 2.10.1 | 27 resolved packages, no known vulnerabilities. |
| npm dependencies | npm audit | 177 total packages, no known vulnerabilities at any severity. |
| Git history secrets | Gitleaks 8.30.0 | Seven commits scanned, no leaks found. |
| Filesystem secrets/misconfiguration | Trivy 0.74.0 | No high/critical findings. No Terraform or Dockerfile exists yet; Compose is not covered by this Trivy scanner. The Maven POM was excluded from this pass after Maven Central returned HTTP 429, but Gitleaks covered it. |
| `postgres:17-alpine` | Trivy 0.74.0 | 1 critical and 23 high fixable findings: 2 OpenSSL package findings and 22 findings in the bundled `gosu` Go binary. |
| `localstack/localstack:4.14.0` | Trivy 0.74.0 | 7 critical and 198 high fixable findings: 81 OS, 60 Java, 14 Node.js, 22 Python, and 28 Lambda runtime findings (some CVEs occur in more than one component). |

The container findings are in local development infrastructure rather than the
SentinelPay application image. They still demonstrate why mutable image tags
must be refreshed, pinned by digest, and scanned again before CI enforcement.

## Reproduction

Run the Java report-only baseline:

```sh
./mvnw -Psecurity-baseline -DskipTests verify
```

The NVD strongly rate-limits unauthenticated initial population. CI should
provide `NVD_API_KEY` as a secret and cache Dependency-Check data.

Run the other repository-level scans from the repository root:

```sh
pip-audit -r fraud-service/requirements.txt
npm --prefix dashboard audit
gitleaks detect --source=. --redact
trivy fs --scanners secret,misconfig --severity HIGH,CRITICAL .
```

Run the local infrastructure image scans:

```sh
trivy image --severity HIGH,CRITICAL --ignore-unfixed postgres:17-alpine
trivy image --severity HIGH,CRITICAL --ignore-unfixed localstack/localstack:4.14.0
```

## Follow-up

- Select refreshed, digest-pinned PostgreSQL and LocalStack images and rescan.
- Add an application Dockerfile before claiming an application-image baseline.
- Add Terraform scanning when Terraform is introduced on Day 17.
- Cache scanner databases and dependency metadata in CI.
- Triage and document suppressions; never suppress solely to make a build pass.
- Enforce unresolved high/critical findings on Day 18.
