export const meta = {
  name: 'fix-round',
  description: 'Implement fix packages in isolated worktrees, adversarially review each, revise, report branches to merge',
  phases: [
    { title: 'Implement', detail: 'one engineer per package in its own git worktree and branch' },
    { title: 'Review', detail: 'adversarial reviewer per package: correctness, regressions, test adequacy' },
    { title: 'Revise', detail: 'address blocking review findings on the same branch' },
  ],
}

// args: { round, base, packagesPath, packages: [{ id, area, suites: [string] }] }  — items live in packagesPath (JSON: packages[].items)
const S = '/tmp/claude-0/-home-user-binario/ec2338fa-afae-5418-81d9-da7d3b962a13/scratchpad'
const ROUND = args.round
const BASE = args.base
const PACKAGES = args.packages
const PKG_PATH = args.packagesPath

const RULES = `
GROUND RULES (binding):
- The main checkout /home/user/binario must NOT be modified: never edit files there, never run git commands that change
  its working tree, index or branches (no checkout, reset, stash, commit, merge, rebase, push). Only read it.
- Work exclusively in your own worktree (instructions below). Never push. Never open pull requests.
- Commit messages: first line "fix(<area>): <what changes for the user>" (or "test(...)", "docs(...)", "build(...)"),
  a short body explaining the defect and the fix in plain words, then exactly these two trailer lines at the end:
  <commit attribution trailer lines configured for the session>
  No other model or tool names anywhere in code, comments, docs or messages.
- Commit after EVERY item (small commits): an interrupted run must leave finished items committed and nothing half-done.
- Match the surrounding code: naming, comment density, idiom, existing patterns and helpers. No reformatting, no
  drive-by refactors, no new dependencies unless unavoidable, no unrelated files.
- Every behavioural fix gets a regression test wherever the tooling can run one (it must fail without the fix and pass
  with it — check that, e.g. by stashing the non-test part in your worktree). When no tool can exercise the code (Swift,
  Compose rendering, CI YAML), say "static only" explicitly in your report.
- Android strings: add every new key to values/, values-en/, values-de/, values-fr/, values-es/ strings.xml with real
  translations (Italian is the default locale), escape apostrophes, keep positional format arguments, and check the XML
  parses (python3 -c "import xml.dom.minidom;xml.dom.minidom.parse('<file>')").
- Companion: if you change app.config.ts, plugins/, or anything prebuild derives, regenerate with
  "CI=1 pnpm exec expo prebuild --platform android --clean --no-install" in YOUR worktree and commit android/ too; then run
  it again and confirm git status is clean (idempotence is required by CI).
- Do not weaken, skip or delete existing tests to get green. If an existing test encodes the defect, change its
  expectation in the same commit and say so.
- Be honest in the report: an item you could not fix is "skipped" with the reason; never leave a half-done change committed.

LOCAL TOOLING (no Android SDK, Xcode, Swift toolchain, CI runners, devices or network to Google/Apple):
- Android app + Wear: REPO=<your worktree> ${S}/kt/android-check.sh all   compiles every app/src/main Kotlin file
  including Compose UI with the real compiler plugin and runs all app and Wear unit tests (read ${S}/kt/README.md for
  details and limitations). Modes: ui, app, wear. No aapt resource linking, lint or R8 — reference every new
  R.string/R.drawable name exactly as declared in res/. The harness compiles against Compose Multiplatform desktop 1.7.3
  jars and hand-written androidx/gms/wear stubs (the real Android artifacts are unreachable here), so: use only the
  Compose/androidx/Wear APIs and patterns the codebase already uses; an API that fails to resolve here may simply be
  newer than 1.7 (then prefer the existing pattern), and an API that resolves here could be desktop-only (never introduce
  one). If a stub lacks a member the real library has and you genuinely need it, add it to the stub under
  ${S}/kt/stubs/ (keep the real signature) and say so in your report.
- Companion (companion-expo/): first "ln -s /home/user/binario/companion-expo/node_modules companion-expo/node_modules"
  in your worktree (git ignores it); then pnpm test, pnpm check, pnpm verify:managed-config, pnpm verify:release-inputs,
  pnpm verify:dependency-audit.
- Alexa (alexa-companion/): same symlink trick for node_modules; pnpm test, pnpm check; migrations:
  ${S}/kt/mariadb-up.sh then RUN_DB_MIGRATION_TESTS=1 DATABASE_URL='mysql://root:test@127.0.0.1:3317/binario' pnpm test:db-migrations
- Python tooling: python3 -m unittest discover -s tests   (run from the worktree root).
- iOS/Watch Swift: static only; tests/test_verify_ios_project.py and tests/test_verify_apple_watch_project.py string-check
  Swift and project files — run them after any ios/ or apple-watch/ change.
`

const IMPL_SCHEMA = {
  type: 'object',
  properties: {
    branch: { type: 'string' },
    worktree: { type: 'string' },
    commits: { type: 'array', items: { type: 'object', properties: { sha: { type: 'string' }, subject: { type: 'string' }, items: { type: 'array', items: { type: 'string' } } }, required: ['sha', 'subject', 'items'] } },
    items: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string' },
          outcome: { type: 'string', enum: ['fixed', 'partial', 'skipped'] },
          what_changed: { type: 'string', description: 'plain-words description of the change and why it is correct' },
          tests: { type: 'string', description: 'tests added/changed and the evidence they fail without the fix (or "static only")' },
          reason_if_not_fixed: { type: 'string' },
        },
        required: ['id', 'outcome', 'what_changed', 'tests', 'reason_if_not_fixed'],
      },
    },
    verification: { type: 'string', description: 'exact commands run at the end and their summary lines (test counts, compile rc)' },
    concerns: { type: 'string', description: 'anything the reviewer or the owner should know (risks, assumptions, follow-ups)' },
  },
  required: ['branch', 'worktree', 'commits', 'items', 'verification', 'concerns'],
}

const REVIEW_SCHEMA = {
  type: 'object',
  properties: {
    verdict: { type: 'string', enum: ['approve', 'changes_required', 'reject'] },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          item: { type: 'string' },
          severity: { type: 'string', enum: ['blocker', 'major', 'minor'] },
          file: { type: 'string' },
          description: { type: 'string' },
          suggested_fix: { type: 'string' },
        },
        required: ['item', 'severity', 'file', 'description', 'suggested_fix'],
      },
    },
    verification: { type: 'string', description: 'commands the reviewer ran in the worktree and their results' },
    notes: { type: 'string' },
  },
  required: ['verdict', 'findings', 'verification', 'notes'],
}

const implPrompt = (p) => `You are a senior engineer implementing a package of fixes in the Binario repository
(Android + Wear OS app, Expo Companion, Alexa companion server, iOS + Apple Watch; see docs/ and CLAUDE.md where present).
${RULES}
SETUP: create your worktree and branch from the base branch, then work only there:
  git -C /home/user/binario worktree add -b fix/r${ROUND}-${p.id} ${S}/fix/wt-${p.id} ${BASE}
  cd ${S}/fix/wt-${p.id}
If the worktree already exists, reuse it: an earlier attempt was interrupted there. First run "git log --oneline ${BASE}..HEAD"
and "git status --short" in it, read the existing commits (git show) and continue from them — items already committed and
green count as done (re-verify them with the suites, do not redo them), uncommitted edits are reviewed and either finished
or reverted deliberately. Report that branch and path.

PACKAGE ${p.id} (area: ${p.area}). Read the package definition from ${PKG_PATH} (JSON; find packages[] with id "${p.id}";
its "items" array holds, for every item: id, the original finding text, a fix summary, the files involved and, where
available, an approach and verification plan; sometimes "also_closes" lists related findings the same fix must cover).
Design the minimal correct fix from the finding and the surrounding code; follow the plan unless the code at HEAD proves it
wrong, in which case do the right thing and explain. Fix every item and every also_closes entry. The base branch has
absorbed a large upstream change set since the findings were written: for each item FIRST check whether HEAD still has
the defect (read the code, run a probe or the existing tests); if it is already fixed, report outcome "fixed" with
what_changed = "already fixed at HEAD: <evidence>" and change nothing; if only partly fixed, fix the remainder.

Required suites to run before you finish (all must be green; include their summary lines in "verification"):
${p.suites.map((s) => '  - ' + s).join('\n')}

Procedure per item: read the code first, implement, add/adjust the regression test, run the targeted check, commit.
At the end run the required suites on the final state, make sure "git status" in your worktree is clean, and report.`

const reviewPrompt = (p, impl) => `You are an adversarial code reviewer for the Binario repository. A colleague implemented
fix package ${p.id} (area ${p.area}) on branch ${impl.branch} (worktree ${impl.worktree}); base branch ${BASE}.
${RULES}
The items and their plans are in ${PKG_PATH} (JSON; packages[] with id "${p.id}", field items, each with id, text,
fix summary, files and sometimes approach/verification/also_closes). Read it first.
The colleague's report:
${JSON.stringify(impl, null, 1)}

Review READ-ONLY (do not edit the worktree, do not commit; you may run tests and scratch probes under ${S}/fix/review-${p.id}/):
1. git -C ${impl.worktree} diff ${BASE}...${impl.branch} — read every hunk.
2. For each item: is the defect actually fixed for every code path and platform the finding names? Any regression
   (nullability, concurrency, lifecycle, locale/timezone, persistence format compatibility, widget/Wear side effects,
   security)? Does the new test really exercise the fix (would it fail without it)? Were existing tests weakened?
   Style and scope: anything unrelated or inconsistent with surrounding code? Commit message trailers present?
3. Run the required suites yourself in the worktree:
${p.suites.map((s) => '  - ' + s).join('\n')}
   and compare with the colleague's claims.
Verdict: approve (merge as is), changes_required (list blockers/majors with concrete fixes), or reject (the approach is
wrong; explain). Minor findings are advisory; only blocker/major force a revision.`

const revisePrompt = (p, impl, review) => `You are the engineer finishing fix package ${p.id} (area ${p.area}) on branch
${impl.branch} in worktree ${impl.worktree} (base ${BASE}). A reviewer requested changes.
${RULES}
Work in that existing worktree (cd ${impl.worktree}); do not create a new one.
Original items: in ${PKG_PATH} (packages[] with id "${p.id}").
Implementation report: ${JSON.stringify(impl, null, 1)}
Review findings (address every blocker and major; minors where cheap):
${JSON.stringify(review.findings, null, 1)}
Reviewer notes: ${review.notes}

Fix the findings with proper regression tests, commit on the same branch (new commits; no history rewriting), rerun the
required suites (${p.suites.join(' ; ')}) and report the final state with the same honesty rules.`

phase('Implement')
const results = await pipeline(
  PACKAGES,
  (p) => agent(implPrompt(p), { label: `impl:${p.id}`, phase: 'Implement', schema: IMPL_SCHEMA }),
  async (impl, p) => {
    if (!impl) return { package: p.id, impl: null, review: null, revision: null }
    const review = await agent(reviewPrompt(p, impl), { label: `review:${p.id}`, phase: 'Review', schema: REVIEW_SCHEMA })
    return { package: p.id, impl, review, revision: null }
  },
  async (r, p) => {
    if (!r.impl || !r.review) return r
    if (r.review.verdict === 'approve') return r
    const blocking = r.review.findings.filter((f) => f.severity !== 'minor')
    if (r.review.verdict === 'changes_required' && blocking.length === 0) return r
    const revision = await agent(revisePrompt(p, r.impl, r.review), { label: `revise:${p.id}`, phase: 'Revise', schema: IMPL_SCHEMA })
    return { ...r, revision }
  },
)

const out = results.filter(Boolean)
for (const r of out) {
  if (!r.impl) { log(`package ${r.package}: implementation failed`); continue }
  const fixed = r.impl.items.filter((i) => i.outcome === 'fixed').length
  log(`package ${r.package}: ${fixed}/${r.impl.items.length} fixed, review ${r.review ? r.review.verdict : 'n/a'}${r.revision ? ', revised' : ''} -> ${r.impl.branch}`)
}
return out
