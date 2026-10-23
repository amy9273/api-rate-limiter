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

- Update `context/progress-tracker.md` after each meaningful implementation change.
- Work strictly against the current unit spec in `context/specs/`.
- If implementation changes architecture, scope, or standards, update the relevant context file before continuing.
- Never violate the architectural invariants documented in `context/architecture.md` or best practices in `context/engineering-best-practices.md`.
- Never commit or push directly to `main` or `master`. Always work in `feat/unit-NN-...` branches.
