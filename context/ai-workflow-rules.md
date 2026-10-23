# AI Workflow Rules — API Rate Limiter & Reverse Proxy

These rules govern how AI coding agents must operate while building and modifying the API Rate Limiter. They are imperative rules, not suggestions.

---

## 1. Spec-Driven Discipline

- **Never code without a spec**: Before writing or modifying any implementation code, read the active unit spec in `context/specs/NN-[feature-name].md`.
- **Stay strictly in scope**: Implement only what the spec demands. Do not add speculative "nice-to-have" features or import unapproved third-party libraries.
- **Consult invariants**: Check every change against `context/architecture.md` invariants before concluding a unit.

---

## 2. Git & Branching Discipline (The "Never Push to Main" Rule)

- **Never Commit or Push to Main**: AI agents must **NEVER** commit or push directly to `main` or `master`. All work must be conducted on isolated feature branches (`feat/unit-NN-...`).
- **Sync Upstream First (Mandatory Baseline)**: Before starting any new unit or creating a feature branch, ALWAYS switch to `main`, run `git fetch origin main && git pull origin main`, and verify local `main` matches `origin/main`. Always branch directly from updated `main` (`git checkout -b feat/unit-NN-... origin/main`).
- **Pre-Push Upstream Sync & Rebase**: Before pushing ANY feature branch, run `git fetch origin main`. If `origin/main` has advanced, rebase your feature branch cleanly onto `origin/main` (`git rebase origin/main`).
- **Build Artifact Hygiene**: Never commit build caches or generated binaries (`target/`, `*.class`, `*.jar`, `.mvn/wrapper/`, `.idea/`). Always ensure `.gitignore` is respected.

---

## 3. Scoping & Execution Discipline

- **One Unit at a Time**: Work on a single, isolated unit per prompt cycle. Complete, verify, and test it before moving to the next.
- **Explicit Consent Before Proceeding**: Never automatically proceed to the next unit without explicit user consent. Always complete, test, and present the current unit's deliverables, and wait for the user to instruct or approve moving to the next unit.
- **Update Progress Tracker**: Update `context/progress-tracker.md` immediately after starting and finishing a unit.
- **Never Bypass Errors**: If a compilation error or test failure occurs, fix the root cause. Never suppress warnings without explicit justification, and never disable tests.

---

## 4. Background Task & Process Lifecycle Management

- **No Orphaned Background Tasks**: When executing tests, Docker containers, or background dev processes, actively track all background task IDs.
- **Mandatory Kill on Completion/Teardown**: If a task finishes its job, times out, hangs, or is superseded, immediately terminate it via `manage_task(Action='kill')` to prevent memory leaks or locked ports on Windows.
- **Active Verification**: Before ending any turn or concluding a unit, inspect running background tasks (`manage_task(Action='list')`) and kill any non-daemon processes that should not be lingering.
