# Esque Gherkin Specification Exploration

This document extracts all behavioral specifications for Esque as Gherkin feature files.
The purpose is to evaluate:
1. What the full behavioral spec looks like in structured natural language
2. How many distinct step patterns exist and how much they overlap across scenarios
3. Whether Gherkin is worth the step-definition investment for the compatibility harness

Two categories of features are identified:
- **Compatibility** — black-box, CLI-driven, runs in the shared Python harness against all implementations
- **Unit** — internal behavior, each language implements its own tests

---

## Feature Files

### 1. migration-index-initialization.feature
*Category: Compatibility*

```gherkin
Feature: Migration Index Initialization

  Scenario: Creates the esque management index on first run
    Given a clean Elasticsearch instance
    And a migration directory with standard migrations
    When esque executes with migration key "init-test"
    Then the index ".esque" should exist in Elasticsearch

  Scenario: Does not fail when the esque management index already exists
    Given a clean Elasticsearch instance
    And a migration directory with standard migrations
    And esque has already executed with migration key "reinit-test"
    When esque executes again with migration key "reinit-test"
    Then esque should exit successfully
```

---

### 2. migration-execution.feature
*Category: Compatibility*

```gherkin
Feature: Migration Execution

  Scenario: Runs all migration files in version order
    Given a clean Elasticsearch instance
    And a migration directory with standard migrations
    When esque executes with migration key "execution-test"
    Then the index "test-index-v1" should exist in Elasticsearch
    And the index "test-index-v2" should exist in Elasticsearch
    And the index "test-index-v3" should exist in Elasticsearch
    And the index "test-index-v4" should exist in Elasticsearch

  Scenario: Execution is idempotent
    Given a clean Elasticsearch instance
    And a migration directory with standard migrations
    And esque has already executed with migration key "idempotent-test"
    When esque executes again with migration key "idempotent-test"
    Then there should be exactly 4 migration records with key "idempotent-test"

  Scenario: Second execution does not change existing migration record timestamps
    Given a clean Elasticsearch instance
    And a migration directory with standard migrations
    And esque has already executed with migration key "idempotent-timestamps-test"
    When esque executes again with migration key "idempotent-timestamps-test"
    Then the migration records should have the same installed-on timestamps as the first execution

  Scenario: Different migration keys are independent
    Given a clean Elasticsearch instance
    And a migration directory with standard migrations
    When esque executes with migration key "key-1"
    Then there should be exactly 4 migration records with key "key-1"
    And there should be exactly 0 migration records with key "key-2"
```

---

### 3. migration-history-recording.feature
*Category: Compatibility*

```gherkin
Feature: Migration History Recording

  Scenario: Records correct metadata for each applied migration
    Given a clean Elasticsearch instance
    And a migration directory with standard migrations
    When esque executes with migration key "history-test"
    Then there should be exactly 4 migration records with key "history-test"
    And the migration record at order 0 should have filename "V1.0.0__CreateTestIndex.yml"
    And the migration record at order 0 should have version "1.0.0"
    And the migration record at order 0 should have description "CreateTestIndex"
    And the migration record at order 1 should have filename "V1.1.0__CreateSecondIndex.yml"
    And the migration record at order 2 should have filename "V2.0.0__CreateThirdIndex.yml"
    And the migration record at order 3 should have filename "V3.0.0__CreateTemplatedIndex.yml"
    And each migration record should have a non-null checksum
    And each migration record should have a non-null installed-on timestamp
    And each migration record should have a non-negative execution time in milliseconds

  Scenario: Records the configured migration user
    Given a clean Elasticsearch instance
    And a migration directory with standard migrations
    When esque executes with migration key "user-test" and migration user "test-user"
    Then each migration record with key "user-test" should have installed-by "test-user"

  Scenario: Records null user when no migration user is configured
    Given a clean Elasticsearch instance
    And a migration directory with standard migrations
    When esque executes with migration key "no-user-test" with no migration user
    Then each migration record with key "no-user-test" should have no installed-by value
```

---

### 4. migration-integrity-verification.feature
*Category: Compatibility*

```gherkin
Feature: Migration Integrity Verification

  Scenario: Fails when migration records outnumber local migration files
    Given a clean Elasticsearch instance
    And standard migrations have been applied with key "too-many-records-test"
    And the migration directory now contains fewer files than were applied
    When esque executes with migration key "too-many-records-test"
    Then esque should exit with a non-zero status code

  Scenario: Fails when a previously applied migration file cannot be found by filename
    Given a clean Elasticsearch instance
    And standard migrations have been applied with key "missing-file-test"
    And the migration file "V1.0.0__CreateTestIndex.yml" has been removed from the directory
    When esque executes with migration key "missing-file-test"
    Then esque should exit with a non-zero status code

  Scenario: Fails when a previously applied migration file has a different checksum
    Given a clean Elasticsearch instance
    And standard migrations have been applied with key "checksum-test"
    And the contents of migration file "V1.0.0__CreateTestIndex.yml" have been modified
    When esque executes with migration key "checksum-test"
    Then esque should exit with a non-zero status code

  Scenario: Fails when migration record order is inconsistent with file order
    Given a clean Elasticsearch instance
    And standard migrations have been applied with key "order-test"
    And a new migration file has been inserted before an already-applied migration
    When esque executes with migration key "order-test"
    Then esque should exit with a non-zero status code
```

---

### 5. template-variable-substitution.feature
*Category: Compatibility*

```gherkin
Feature: Template Variable Substitution

  Scenario: Substitutes template variables in migration request paths
    Given a clean Elasticsearch instance
    And a migration directory with a migration using placeholder "#{indexName}" in the path
    And template properties:
      | key       | value          |
      | indexName | test-index-v4  |
    When esque executes with migration key "template-path-test"
    Then the index "test-index-v4" should exist in Elasticsearch

  Scenario: Substitutes template variables in migration request bodies
    Given a clean Elasticsearch instance
    And a migration directory with a migration using placeholder "#{replicaCount}" in the body
    And template properties:
      | key          | value |
      | replicaCount | 1     |
    When esque executes with migration key "template-body-test"
    Then esque should exit successfully

  Scenario: Ignores extra template properties when no placeholders exist in migration files
    Given a clean Elasticsearch instance
    And a migration directory with standard migrations that contain no placeholders
    And template properties:
      | key    | value        |
      | unused | ignored      |
      | indexName | test-index-v4 |
    When esque executes with migration key "extra-props-test"
    Then esque should exit successfully

  Scenario: Fails before running any migration when a required template variable is missing
    Given a clean Elasticsearch instance
    And a migration directory with a migration using placeholder "#{indexName}" in the path
    And no template properties are provided
    When esque executes with migration key "missing-var-test"
    Then esque should exit with a non-zero status code
    And there should be exactly 0 migration records with key "missing-var-test"
```

---

### 6. migration-file-loading.feature
*Category: Compatibility (file discovery) + Unit (parsing internals)*

```gherkin
Feature: Migration File Loading and Ordering

  # Compatibility: observable ordering behavior
  Scenario: Migration files are executed in ascending version order
    Given a clean Elasticsearch instance
    And a migration directory containing files in this order on disk:
      | filename                      |
      | V2.0.0__CreateThirdIndex.yml  |
      | V1.0.0__CreateFirstIndex.yml  |
      | V1.1.0__CreateSecondIndex.yml |
    When esque executes with migration key "ordering-test"
    Then the migration record at order 0 should have version "1.0.0"
    And the migration record at order 1 should have version "1.1.0"
    And the migration record at order 2 should have version "2.0.0"

  # Compatibility: version segment comparison
  Scenario: Version segments are compared numerically not lexicographically
    Given a clean Elasticsearch instance
    And a migration directory with files:
      | filename               |
      | V1.9.0__First.yml      |
      | V1.10.0__Second.yml    |
    When esque executes with migration key "numeric-version-test"
    Then the migration record at order 0 should have version "1.9.0"
    And the migration record at order 1 should have version "1.10.0"

  # Unit: internal parsing — each language implements these themselves
  Scenario: Parses version from filename
    Given a migration file named "V1.2.3__CreateMyIndex.yml"
    When the file metadata is parsed
    Then the version should be "1.2.3"
    And the description should be "CreateMyIndex"
    And the filename should be "V1.2.3__CreateMyIndex.yml"
```

---

### 7. checksum-stability.feature
*Category: Compatibility*

```gherkin
Feature: Checksum Stability

  Scenario: Checksum is stable across multiple runs against the same file
    Given a clean Elasticsearch instance
    And a migration directory with standard migrations
    And esque has already executed with migration key "checksum-stability-test"
    When esque executes again with migration key "checksum-stability-test"
    Then the checksums in migration records should be unchanged from the first execution

  Scenario: Checksum is the same whether values are hardcoded or substituted from template
    Given a clean Elasticsearch instance
    And a migration directory with two equivalent migrations:
      | filename                      | style      |
      | V1.0.0__Hardcoded.yml         | hardcoded  |
      | V2.0.0__Templated.yml         | templated  |
    And template properties:
      | key   | value          |
      | index | test-index-v1  |
    When esque executes with migration key "checksum-equivalence-test"
    Then the migration record at order 0 should have the same checksum as order 1
```

---

## Step Pattern Analysis

### Distinct Given Steps (~10 patterns)

| Step Pattern | Used In |
|---|---|
| `a clean Elasticsearch instance` | All scenarios |
| `a migration directory with standard migrations` | Most scenarios |
| `a migration directory with [custom files]` | ordering, integrity |
| `a migration directory with a migration using placeholder {ph} in the {field}` | template scenarios |
| `no template properties are provided` | template missing-var |
| `template properties: [table]` | template scenarios |
| `esque has already executed with migration key {key}` | idempotency, integrity |
| `standard migrations have been applied with key {key}` | integrity scenarios |
| `the migration file {filename} has been removed from the directory` | integrity |
| `the contents of migration file {filename} have been modified` | integrity |
| `a new migration file has been inserted before an already-applied migration` | integrity |

### Distinct When Steps (~4 patterns)

| Step Pattern | Used In |
|---|---|
| `esque executes with migration key {key}` | Most scenarios |
| `esque executes with migration key {key} and migration user {user}` | user recording |
| `esque executes with migration key {key} with no migration user` | null user |
| `esque executes again with migration key {key}` | idempotency |

### Distinct Then Steps (~12 patterns)

| Step Pattern | Used In |
|---|---|
| `esque should exit successfully` | error-free scenarios |
| `esque should exit with a non-zero status code` | failure scenarios |
| `the index {index} should exist in Elasticsearch` | execution, template |
| `there should be exactly {n} migration records with key {key}` | history, idempotency |
| `the migration record at order {n} should have filename {filename}` | history |
| `the migration record at order {n} should have version {version}` | history, ordering |
| `the migration record at order {n} should have description {description}` | history |
| `each migration record should have a non-null checksum` | history |
| `each migration record should have a non-null installed-on timestamp` | history |
| `each migration record should have a non-negative execution time in milliseconds` | history |
| `each migration record with key {key} should have installed-by {user}` | user recording |
| `each migration record with key {key} should have no installed-by value` | null user |
| `the migration records should have the same installed-on timestamps as the first execution` | idempotency |
| `the checksums in migration records should be unchanged from the first execution` | checksum stability |

---

## Summary

**~26 distinct step patterns** across all compatibility scenarios.

**Implementation effort per step:**
- `Given a clean Elasticsearch instance` → testcontainers setup, likely in `conftest.py`, not a step
- `Given a migration directory with standard migrations` → resolve path to shared fixture dir
- `When esque executes with migration key {key}` → `subprocess.run([cli, "--migration-key", key, ...])`
- `Then the index {index} should exist` → `httpx.get(f"{es_url}/{index}")` → assert 200
- `Then there should be exactly {n} migration records` → query `.esque` index, count hits
- `Then the migration record at order {n} should have {field} {value}` → query `.esque`, index into hits

Most steps are 3-5 lines of Python. The heavy lifting (ES setup, CLI invocation) is shared infrastructure in `conftest.py`. The step definitions themselves are thin wrappers.

**Rough estimate:** ~200-250 lines of Python step definitions covers all compatibility scenarios.

**Key finding:** There is significant step reuse. The `When esque executes` family and `Then the index should exist` / `Then there should be N records` steps appear in almost every scenario. Writing them once covers the majority of the test surface.

**Verdict:** The overhead is manageable. 26 patterns, most are trivial, and once written they work for every language added to the harness. The AI-spec value (unambiguous, executable requirements) is real at this scale.
