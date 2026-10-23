# Application Building Context — API Rate Limiter & Reverse Proxy

Read the following files in order before implementing or making any architectural decision:

1. `context/project-overview.md` — Product definition, systems challenges, and success criteria.
2. `context/architecture.md` — System structure, lock-free CAS state model, and architectural invariants.
3. `context/engineering-best-practices.md` — Virtual threads, non-blocking atomic memory models, and benchmarking.
4. `context/ui-context.md` — Prometheus metrics, terminal banner, and high-throughput logging rules.
5. `context/code-standards.md` — Java 21 LTS standards, zero lock contention, and RFC 6585 error formats.
6. `context/ai-workflow-rules.md` — Upstream Git discipline, branch-protection (never push to main), and verification loops.
7. `context/progress-tracker.md` — Current phase, roadmap, completed units, open questions, and next steps.

---

### Operating Rules

- **Sync Upstream First (Mandatory Baseline)**: Before starting any new unit or creating a feature branch, ALWAYS switch to `main`, run `git fetch origin main && git pull origin main`, and verify local `main` matches `origin/main`. Always branch directly from updated `main` (`git checkout -b feat/unit-NN-... origin/main`).
- **Never Commit or Push to Main**: AI agents must **NEVER** commit or push directly to `main` or `master`. All work must be conducted on isolated feature branches (`feat/unit-NN-...`).
- **Pre-Push Upstream Sync & Rebase**: Before pushing ANY branch, always run `git fetch origin main`. If `origin/main` has advanced, rebase your feature branch cleanly onto `origin/main` (`git rebase origin/main`).
- **Work Strictly Against Active Spec**: Work strictly against the current unit spec in `context/specs/`.
- **Update Progress Tracker**: Update `context/progress-tracker.md` after each meaningful implementation change.
- **Preserve Invariants**: Never violate the architectural invariants documented in `context/architecture.md` or best practices in `context/engineering-best-practices.md`.
- **Mandatory Quality Check Loop**: Run compilation (`mvn clean compile`) and automated tests (`mvn test`) before creating commits.
- **Explicit Consent for Next Unit**: Never automatically proceed to the next unit without explicit user consent. Always complete and verify the current unit, present the deliverables, and wait for the user's confirmation before starting the next unit.
- **Process & Task Lifecycle**: Never leave background tasks hanging or accumulating; immediately terminate/kill finished or orphaned background tasks (`manage_task(Action='kill')`).
- **Build Artifact Hygiene**: Never commit `target/`, `*.class`, `*.jar`, or IDE metadata (`.idea/`, `.vscode/`). Always ensure `.gitignore` is strictly enforced.
