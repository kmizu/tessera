# Tessera Minimal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first executable Tessera vertical slice with a minimal Scala 3 project, kernel + CLI skeleton, and term/proof examples so we can start validating the design in `tessera_complete.md`.

**Architecture:** Implement a small multi-layer pipeline under a single sbt module: `core` for term/kind checks, `kernel` for closed-term checking, `elab` for surface-to-core placeholders, `meta` for synthesis combinators, and `cli` for user-facing commands, with explicit type-checked boundaries.

**Tech Stack:** Scala 3, sbt, munit, zio-json (none for MVP unless needed), handwritten parser for later stage.

---

## Global Constraints

- Follow `tessera_complete.md` principle that the kernel must be isolated from parser/elab/meta/search/control.
- Ensure generated ordinary terms are inspectable through dedicated show commands before adding new automation.
- Do not add raw continuation APIs to stable public surface in MVP.
- Keep the scope to a vertical slice: no LSP/Web Playground in this phase.
- Use `sbt test` as the local verification gate where possible.

---

### Task 1: Repository bootstrap + layout

**Files:**
- Create: `build.sbt`
- Create: `project/build.properties`
- Create: `project/plugins.sbt`
- Create: `src/main/scala/tessera/core/terms.scala`
- Create: `src/main/scala/tessera/kernel/Kernel.scala`
- Create: `src/main/scala/tessera/cli/Commands.scala`
- Create: `src/test/scala/tessera/kernel/KernelTest.scala`

**Interfaces:**
- `tessera.cli.TesseraCli.main(args: Array[String]): Unit` executes CLI commands.
- `tessera.core.Type` and `tessera.core.Term` define the smallest language surface for MVP.
- `tessera.kernel.CoreChecker.checkTerm(term: CoreTerm, expected: Type): CheckResult` revalidates closed terms.

- [ ] **Step 1: Add sbt project skeleton**

```bash
cat > build.sbt <<'EOF'
ThisBuild / scalaVersion := "3.3.3"

lazy val root = (project in file("."))
  .settings(
    name := "tessera",
    version := "0.0.1-SNAPSHOT",
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.2" % Test
  )
EOF
```

- [ ] **Step 2: Add minimal core term data model**

Create algebraic data types for `Type`, `Sort`, `Term`, and an explicit universe level type.

- [ ] **Step 3: Add kernel checker API and data structures**

Implement `checkTerm` and structured `CheckResult` that can report `KernelError`.

- [ ] **Step 4: Add CLI entry command parser**

Support command names `check`, `show-term`, and `show-core` with placeholder diagnostics.

- [ ] **Step 5: Add kernel tests for identity and mismatch**

```scala
class KernelTest extends munit.FunSuite {
  test("kernel accepts closed identity term") {
    // TODO: instantiate Type[0], lambda, app, and constant check
  }

  test("kernel rejects type mismatch") {
    // TODO: expect KernelError for wrong constructor arg
  }
}
```

- [ ] **Step 6: Run tests once bootstrap files compile**

```bash
sbt test
```

- [ ] **Step 7: Mark scaffold as committed artifact in status file**

```bash
echo "- bootstrap scaffolding created" >> IMPLEMENTATION_STATUS.md
```

### Task 2: Documentation freeze for Phase 0

**Files:**
- Create: `README.md`
- Create: `AGENTS.md`
- Create: `IMPLEMENTATION_STATUS.md`
- Create: `docs/VISION.md`
- Create: `docs/LANGUAGE.md`
- Create: `docs/CORE_CALCULUS.md`
- Create: `docs/ELABORATION.md`
- Create: `docs/META_LANGUAGE.md`
- Create: `docs/DO_NOTATION.md`
- Create: `docs/CONTINUATIONS.md`
- Create: `docs/KERNEL.md`
- Create: `docs/DIAGNOSTICS.md`
- Create: `docs/ROADMAP.md`
- Create: `docs/adr/01-universe-model.md`

**Interfaces:**
- `README.md` must document required sbt commands and the minimum supported CLI commands.
- `IMPLEMENTATION_STATUS.md` tracks implemented, in-progress, and blocked items with source references.

- [x] **Step 1: Write root docs and vision-level architecture**

- [x] **Step 2: Write ADR stub for universe model and add placeholder ADR index list**

- [x] **Step 3: Document doc deliverables and expected next milestones**

- [x] **Step 4: Mark initial status as Phase 0 complete and Phase 1 blocked by no parser**

### Task 3: Phase 1 kernel smoke (first vertical slice)

**Files:**
- Modify: `src/main/scala/tessera/core/terms.scala`
- Modify: `src/main/scala/tessera/kernel/Kernel.scala`
- Modify: `src/test/scala/tessera/kernel/KernelTest.scala`

**Interfaces:**
- `src/main/scala/tessera/kernel/Kernel.scala` owns `normalize` + `isDefEq` and reports `KernelError` for universe sort violations.
- `KernelCheckResult` exposes a boolean and a list of diagnostics.

- [x] **Step 1: Implement sort/type representation used by checker**

- [x] **Step 2: Implement de Bruijn var and substitution helpers**

- [x] **Step 3: Add beta reduction and weak head reduction check helpers**

- [x] **Step 4: Implement check and infer entry points**

- [x] **Step 5: Add tests for shift substitution and defeq invariants**

- [x] **Step 6: Run `sbt test` and verify only intended fails are present before next stage**

### Task 4: Self-review

**Files:**
- `IMPLEMENTATION_STATUS.md`
- `docs/ROADMAP.md`

- [ ] **Step 1: Reconcile plan item coverage against `tessera_complete.md` section references**

- [ ] **Step 2: Ensure no placeholders remain in implemented docs**

- [ ] **Step 3: Confirm next execution chunk can start from Task 3 with no ambiguous dependencies**
