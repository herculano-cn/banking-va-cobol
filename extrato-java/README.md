# Credit statement batch: Java 21 and Quarkus

A command-line batch application with no HTTP endpoint. It reads credit operations from a local pipe-delimited file or DB2, selects and orders them, then writes a fixed-record statement and separate log. The COBOL, DB2 DDL, JCL, mock data and historical golden remain unchanged. This is a functional migration with intentional corrections, **not byte-for-byte COBOL compatibility**.

## Build and run

Java 21 and Maven 3.9 or newer are required. From `extrato-java`:

```sh
mvn -B -ntp verify
java -jar target/quarkus-app/quarkus-run.jar --source mock --mock ../mock-operacoes.dat \
  --account 0042123456 --branch 0042 --date 20260508 --output SAIDA --log LOGFILE
```

Use `--help` for the CLI. All options take a value except `--help`; repeated value options or unknown options are errors. Paths are relative to the current working directory. Do not use production output paths during qualification.

| Option | Default | Meaning |
| --- | --- | --- |
| `--source` | `mock` | `mock` or `db2` |
| `--mock` | `../mock-operacoes.dat` | Local input file |
| `--account` | `0042123456` | 1–10 characters, without surrounding whitespace |
| `--branch` | `0042` | 1–4 characters, without surrounding whitespace |
| `--date` | `20260508` | Valid calendar date, `YYYYMMDD`, used as cutoff |
| `--output` | `SAIDA` | Statement path |
| `--log` | `LOGFILE` | Log path |
| `--encoding` | `ISO-8859-1` | Output charset; every resulting record must encode to exactly 132 bytes |

## Input and selection

The supplied account, branch and reference date are explicit inputs; date format is `YYYYMMDD`. Both sources select account and branch, status `A` or `I`, and reference date at or before the cutoff. Selected operations are ordered by modality and contract. A local mock is deterministic but is not evidence that DB2 SQL or ordering works on the target host.

Local input is a runtime-read, pipe-separated text file. Each row uses the historical ten fields, in this order:

```
account|contract|modality|description|original amount|installments|paid installments|balance|next due date|status
```

The file is decoded as ISO-8859-1 independently of `--encoding`. Blank lines and lines starting with `#` are skipped; every other line must contain exactly 10 or 12 fields. In the 12-field form, fields 11 and 12 are branch and reference date (`YYYYMMDD`). The due date is also `YYYYMMDD` and both dates must be valid calendar dates from year 0001 through 9999. Amounts are signed `DECIMAL(13,2)` values, with at most two nonzero fractional digits; installment counts use the signed `SMALLINT` range. Selection keys, modality, dates and numbers are validated even on rows excluded by the filter. The DB2 constraint paid installments ≤ installments is checked only for selected mock rows: the unchanged historical fixture includes a violating row for account `0004`, which fails if selected. A ten-field row derives branch from the first four account characters and assumes the requested cutoff as its reference date, because neither field exists in the historical mock. An account shorter than four characters cannot supply that prefix and is rejected; use the 12-field form instead. This convention cannot reconstruct the original DB2 values or prove equivalent selection. A missing file fails; an empty file produces no details, not the old three-record fallback. There is no historical 50-row limit.

Mock description is input data, copied without translation; historical fixtures contain Portuguese descriptions, so a mock report can contain those original values even though application-generated labels are English. DB2 modality descriptions are mapped to English by the application, including `RF` as `REAL ESTATE FINANCE`. The mock uses Java string ordering for modality and contract; DB2 ordering depends on database collation.

Mock account is nonblank and at most 10 characters after stripping trailing padding; status must be `A`, `I`, `Q`, or `R` (only `A` and `I` are selected). Accepted modality codes are `CPE`, `CSG`, `CDC`, `RF`, `RFI`, `VEI`, `CHQ`, `ANT` and `OUT`. Only selected rows are formatted: contract and description must fit their output fields, and values must fit the selected encoding. A 12-field branch is nonblank and 1–4 characters after trimming padding, unlike the ten-field prefix convention.

DB2 reads `DB2PROD.TB_OPERACAO_CREDITO` joined to `DB2PROD.TB_CLIENTE`, using parameterized account, branch and cutoff predicates and `WITH UR`. Provide an authorized read-only identity through the environment, without embedding credentials in commands or the repository:

```sh
export EXTRATO_DB2_URL='jdbc:db2://host:50000/database'
export EXTRATO_DB2_USER='read-only-user'
# Inject EXTRATO_DB2_PASSWORD through the deployment's secret manager.
java -jar target/quarkus-app/quarkus-run.jar --source db2 \
  --account 0042123456 --branch 0042 --date 20260508 --output SAIDA --log LOGFILE
```

## Output and errors

The statement and log use fixed 132-byte records without newline delimiters. The default encoding is ISO-8859-1; `--encoding` selects the charset. Text that cannot be encoded and values that cannot fit their report fields fail instead of being silently replaced or truncated. Money uses exact decimal arithmetic with two fractional digits. The report has one header, one detail per selected row and one footer; empty selections still have header and footer.

Detail fields use these one-based inclusive positions: contract 1–14, description 17–36, original amount 39–53, installment count 56–61, paid count 64–69, balance 72–86, due date 89–98 (`DD/MM/YYYY`) and English status 101–111; spaces separate the fields and pad the record. The footer count is at 23–29 and total balance at 74–88. Signed values fit the individual DB2 column types, but an aggregate that exceeds the 15-character footer amount fails explicitly. These widened fields shift subsequent offsets relative to the legacy layout; consumers must not assume the old offsets.

A successful run returns 0 and writes start and completion log records. Fatal input, read, format or write errors return 12; processing failures attempt an error log record and print a sanitized message to stderr. The output and log files are replaced, and an error can leave them empty or partial: preserve them for diagnosis and do not distribute them. Input, output and log must be distinct paths, including existing aliases; this check is not an atomic defense against concurrent path changes. Inspect records locally, for example, with `fold -b -w132 SAIDA`.

Completion is written only after the report closes successfully; a report-close failure attempts a sanitized error record. Logging and close failures may still leave partial or missing log records, and a completion record can remain if the log close subsequently fails. Closing files does not guarantee durable storage or atomic publication.

Report and log labels are English; input data and identifiers are not translated. In particular, Portuguese legacy report labels and the English labels are incompatible even when the business data match. Do not claim byte parity with the legacy golden.

Detail amounts are right-aligned and use a decimal point in Java; the legacy formatter trims and left-aligns the amount and uses a comma editing mask. These changes affect fixed-position consumers independently of the language change and require downstream adaptation.

## Deliberate corrections to actual legacy behavior

The source evidence and remaining uncertainties are detailed in [TRACEABILITY_MATRIX.md](TRACEABILITY_MATRIX.md).

* `PGMEXTRT` calls the reader during initialization, and `PGMLECRD` fetches immediately; the processing loop fetches again and loses the first record. Java includes it in the details and totals.
* JCL describes only date and branch, but the COBOL main moves date into the account; the reader derives branch from that account and uses the current date as cutoff. Java takes account, branch and cutoff explicitly. The actual host delivery of `WS-PARM` remains unverified.
* The legacy DB2 reader takes the first eight characters of an ISO date and then treats them as `YYYYMMDD`. Java uses the complete date.
* The legacy numeric edit discards cents, and the footer moves a seven-digit count and larger total into narrower fields. Java retains decimal precision and rejects overflow rather than silently discarding digits.
* The legacy `RF` DDL code does not match the COBOL `RFI` lookup. This must be considered when reconciling descriptions.
* The legacy final log write follows closure of the log, and writer OPEN failure returns `9` while the caller checks only `2`. Java treats file failures as fatal and does not rely on writes to a closed log.

## Historical tools and evidence

`src/test/resources/SAIDA.golden` is retained as a historical GnuCOBOL 3.2 artifact for the old local mock, not an expected corrected report. Its SHA-256 is `58e51edc3af67a259159c866e4e024f34059eb8137720918d95ee617920e15a7`; the associated mock's SHA-256 is `23d2a60670105154e06177ae8363e5a843f38fd645f7a264ff644df366326f80`. `scripts/test-diferencial.py` assumes the old Portuguese CLI, first-row loss and exact legacy bytes. `homologacao_mock.py` invokes the old CLI and validates the former report and log. Both are historical and are **not compatible acceptance tools for the new batch**; redesign them with independent corrected expectations before any further use. The old COBOL scripts and mock build in the repository root remain for the COBOL workflow, not the Quarkus CLI. No fixture or legacy source was altered.

The GitHub workflow `.github/workflows/extrato-java.yml` builds and runs the Java tests on Java 21. It does not execute DB2, IBM COBOL or z/OS and does not run the old differential suite. Maven's normal verification is not a DB2 deployment test.

## Qualification evidence and release gates

The following gates are requirements, not evidence of approval. Record the named accountable owner, date, environment and retained evidence for each; no approval is inferred from a passing local build. Do not release until the responsible owners explicitly sign off all applicable gates.

| Gate | Owner and evidence required | Release decision |
| --- | --- | --- |
| Corrected-format local golden | Java test owner: retain `mvn -B -ntp verify` results and independent literal 132-byte report/log expectations for synthetic selection, ordering, empty and failure cases, including IBM037. This is a local regression check, not host certification. | Fail on any mismatch or missing evidence. |
| Historical artifact | Migration owner: retain the historical GnuCOBOL golden and its checksum above as provenance only; document intentional differences. It is not an expected Java golden. | Do not use old defective parity as an acceptance gate. |
| Real legacy versus Java reconciliation | Business/data owner and legacy owner: use an authorized controlled, reproducible snapshot and matched inputs; retain both outputs, counts, totals, hashes, row-level differences and signed disposition of each correction and unexplained difference. | Fail on unexplained differences; do not assume historical mock proves reconciliation. |
| Live DB2/JCC and dates | DB2 owner: retain authorized environment and JCC versions, schema, privileges, collation and isolation, SQL and date-binding evidence, boundary-date selection and expected results; run the opt-in profile with independent fixtures. | Fail on missing access, discrepancies or unapproved isolation. |
| CCSID and transfer | Encoding/host owner: identify actual CCSIDs and conversion path; retain representative encoded bytes, record lengths, lossless round-trip and transfer results on target systems. Local IBM037 tests do not establish the host CCSID. | Fail on conversion loss, mismatches or unknown CCSID. |
| z/OS FB and scheduler | Operations owner: retain target dataset allocation and `RECFM=FB,LRECL=132` evidence for report and log, scheduler/JCL, monitoring, return-code and recovery tests. | Fail on allocation, sequencing, monitoring or return-code mismatch. |
| Downstream layout and labels | Each downstream consumer and business owner: sign off the English labels, widened offsets, amount representation and interface specification; retain approval and consumer test results. | Fail if any consumer has not explicitly approved the changed contract. |
| Failure and rollback | Operations and release owners: retain evidence for malformed input, read/write/DB2 failures, RC 12, logs, quarantine of partial files and a rehearsed rollback to the legacy job; record the go/no-go decision. | Fail closed, do not distribute partial output, and roll back on a failed gate or production trigger. |

## DB2 and mainframe deployment limitations

No DB2 or z/OS instance is available here. Before deployment, validate the driver and JDBC URL, schema, privileges, network/TLS, SQL dialect and JDBC 4.2 `LocalDate` binding and retrieval against the authorized DB2 version and JCC driver. The sample DDL declares EBCDIC and non-null columns; validate actual schema and null behavior. `WITH UR` can see uncommitted changes; use a controlled snapshot or approved isolation policy for reproducible reconciliation, and account for DB2 collation differences. Do not run the sample DDL or JCL against production.

The opt-in `mvn -B -ntp -Pdb2-it verify` profile requires an authorized read-only DB2 connection and independently prepared synthetic fixtures. In addition to `EXTRATO_DB2_URL`, `EXTRATO_DB2_USER`, and `EXTRATO_DB2_PASSWORD`, set `EXTRATO_DB2_IT_ACCOUNT`, `EXTRATO_DB2_IT_OTHER_ACCOUNT`, `EXTRATO_DB2_IT_BRANCH`, `EXTRATO_DB2_IT_CORTE` (ISO `YYYY-MM-DD`), `EXTRATO_DB2_IT_OPERACOES` (expected TSV path), and `EXTRATO_DB2_IT_SAIDA` (expected report path). The TSV is UTF-8, with the ten fields listed above in expected DB2 sort order, due date `YYYYMMDD`, and DB2's English mapped descriptions; blank and `#` lines are ignored. Provide at least three selected operations across two modalities, including two contracts in one modality, and database witnesses excluded by branch, status, cutoff and account. The expected report must be independently prepared in the corrected 132-byte ISO-8859-1 format, including the first selected row, not generated from the Java run being checked. The profile exercises the reader and report against this fixture, not the real clock, JCL, host COBOL or operational log. It has not been run here.

The qualification harness suppresses fixture values, paths and underlying exception details in its own failure diagnostics; this is not a confidentiality guarantee for Maven, JDBC drivers, operating systems or other external tooling. Use synthetic or approved anonymized data, restrict access to fixtures, generated reports, logs and test artifacts, and securely retain or dispose of them under local policy. Do not publish artifacts or detailed external logs without a security review, and keep credentials out of commands, logs and repositories.

ISO-8859-1 is not EBCDIC. Establish the host CCSID and an approved lossless per-record conversion and transfer process; preserve source records and verify length and round-trip before comparing. A Java file is not automatically a z/OS FB dataset. JCL DD allocation (`RECFM=FB,LRECL=132`), scheduler, print step, secret management, monitoring, access controls and rollback remain external. The JCL's `DISP=NEW` and conditional steps are not replicated by ordinary local files.

Qualify in an isolated environment with independently approved expected results, synthetic or anonymized data, filter and sort witnesses, empty/one/multiple rows, numeric and encoding boundaries, and read/write/database failures. Capture versions, configuration, record counts, hashes, return codes, logs, partial-file behavior and each intentional difference. Obtain business and operations approval before switching the scheduler; retain the COBOL job for rollback. See [TRANSLATION_DECISIONS.md](TRANSLATION_DECISIONS.md).
