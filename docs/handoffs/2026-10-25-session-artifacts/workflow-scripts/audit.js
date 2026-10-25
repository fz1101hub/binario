export const meta = {
  name: 'binario-final-audit',
  description: 'Independent audit of the Binario repository: area/lens finders, adversarial skeptics, known-item re-verification, completeness critic',
  phases: [
    { title: 'Find', detail: 'one read-only finder per area or lens, writes find-<key>.json' },
    { title: 'Verify', detail: 'one adversarial skeptic per finder, writes verify-<key>.json' },
    { title: 'Recheck', detail: 'previously reported items re-verified against HEAD in chunks' },
    { title: 'Critic', detail: 'completeness critic over all results' },
  ],
}

// args: { mode: 'find' | 'critic', keys: [finder keys and/or low-chunk names], lowChunks: { name: [item ids] }, skeptic: boolean }
const S = '/tmp/claude-0/-home-user-binario/ec2338fa-afae-5418-81d9-da7d3b962a13/scratchpad'
const MODE = (args && args.mode) || 'find'
const KEYS = (args && args.keys) || []
const LOW = (args && args.lowChunks) || {}
const SKEPTIC = !args || args.skeptic !== false

const CONTEXT = `
Repository: /home/user/binario, branch claude/git-repo-audit-cdcsm6 (run "git -C /home/user/binario log --oneline -1" for HEAD).
Products:
- Android phone app app/ (it.binario.app, Kotlin + Jetpack Compose, minSdk 26, targetSdk 36) and its Wear OS companion
  module wear/ (same applicationId, not standalone) — target: Google Play.
- iOS app ios/ (it.binario.ios, SwiftUI, Swift 6 language mode, XcodeGen ios/project.yml, Swift package ios/BinarioKit) with an
  embedded Apple Watch app + WidgetKit extension apple-watch/ — target: App Store. apple-watch/project.yml is a
  standalone CI/test harness, not a store product.
- Expo/React Native "Binario Companion" companion-expo/ (im.binario.companion, prebuilt android/ with a wear module) — a
  separate secondary Android app (see docs/CHECKLIST_COMPANION.md for its intent).
- alexa-companion/ — Node/Express/tRPC/Drizzle/MySQL server behind the Alexa skill and the apps' account linking.
- Python tools/ verifiers and tests/ (python3 -m unittest discover -s tests), GitHub Actions workflows in .github/workflows.
Owner goal: a version of the apps with as few bugs as possible that can be uploaded to Google Play and the App Store
without problems. Your findings feed an implementation round by other agents: be precise about WHAT must change in the
repository (file:line, the wrong behaviour, the expected behaviour) and WHAT only the owner can do outside the repository
(accounts, consoles, keys, listings, data refresh).

History you must respect: HEAD already contains several fix rounds from October 2026 (see
"git -C /home/user/binario log --oneline 2b2151a..HEAD" and the docs under docs/store, docs/governance). Verify every
suspicion against the files at HEAD, not against older reports. ${S}/low/items.json lists ~270 findings from earlier
audits (fields id, text, file, status); if your finding is already there, put its id in "duplicate_of" and still give
current evidence. docs/audit-independent-2026-09-23.md and ${S}/report.md are earlier reports you may consult for context,
never as evidence.

Local tooling (no Android SDK, no Xcode, no Swift compiler, no device, no network to Google/Apple hosts):
- Python: python3 -m unittest discover -s tests; tools/verify_*.py.
- Companion: cd companion-expo && pnpm test / pnpm run check / pnpm run verify:managed-config (deps installed).
- Alexa: cd alexa-companion && pnpm test (deps installed); MariaDB for migration tests via ${S}/kt/mariadb-up.sh then
  RUN_DB_MIGRATION_TESTS=1 DATABASE_URL='mysql://root:test@127.0.0.1:3317/binario' pnpm test:db-migrations.
- Android: the Kotlin/Compose compile+unit-test harness ${S}/kt/android-check.sh is reserved for the implementers; do NOT
  run it (its output directory is shared and concurrent runs collide). Read the Kotlin, reason, and cite exact lines; the
  existing unit tests under app/src/test and wear/src/test show which behaviour is covered.
- Swift and Kotlin UI: static reading only; be explicit when a claim could not be executed.
This is a READ-ONLY task: never modify files under /home/user/binario (no git checkout/stash/commit either). Scratch
space: ${S}/audit/scratch-<your-key>/. Prefer precision over volume: a finding must name a concrete input or state and
the wrong outcome. Do not report style preferences. Do not report things fixed at HEAD.
`

const FINDERS = {
  'android-data': `Area: Android data layer, app/src/main/java/it/binario/app/data/** and the model/planning code it feeds
(GTFS embedded packs and realtime resolver, RFI/ViaggiaTreno/Italo page and JSON parsers, station catalogue and
canonicalizer, data quality bands, schedule/periodicity logic, caching, timezones and day boundaries, background refresh).
Lenses: correctness of parsing and time arithmetic (Europe/Rome, DST, after-midnight services, service-day vs calendar
day), robustness against malformed or changed upstream pages/feeds (exceptions reaching the UI or crashing), concurrency
(dispatchers, cancellation, shared mutable state), resource leaks, and data freshness/expiry handling. Read the unit
tests to see what is covered and look for what is not.`,
  'android-ui': `Area: Android presentation layer — app/src/main/java/it/binario/app/TrainViewModel.kt, MainActivity.kt,
Compose screens/components, navigation and state restoration (process death, rotation, back stack), settings, alerts and
notifications (channels, POST_NOTIFICATIONS, exact alarms and WorkManager), home-screen widget(s), the Wear data-layer
sender, Alexa linking UI, onboarding and empty/error states. Lenses: state bugs (stale, duplicated, lost input), lifecycle
and coroutine scope misuse, main-thread work, recomposition loops, accessibility (content descriptions, touch targets,
TalkBack order), text that cannot fit or truncates, error paths that silently do nothing, and flows that dead-end.`,
  'android-platform': `Area: Android platform and Play configuration — app/src/main/AndroidManifest.xml, wear manifest,
permissions and their runtime requests, exported components and intent filters, backup/data-extraction rules, network
security config, ProGuard/R8 rules versus reflection/serialisation/JSON/Jsoup usage in release builds (app/proguard-rules.pro,
wear/proguard-rules.pro — a missing keep rule crashes only the release build), Gradle configuration (app/build.gradle.kts,
wear/build.gradle.kts, gradle.properties, version catalog/lock files), targetSdk 36 behaviour changes (edge-to-edge
enforcement, predictive back, foreground service types, notification trampolines, exact alarm policy, broadcast receiver
export flags, PendingIntent mutability, 16 KB page size), resource configuration (localeConfig, resource shrinking removing
resources looked up by name), app bundle and Wear packaging, and Google Play policy inputs (Data safety, target API level
requirements for 2026, families/ads declarations, deprecated APIs). Read docs/store/PLAY_SUBMISSION.md and
docs/store/PLAY_DATA_SAFETY.md and check them against the code.`,
  'wear': `Area: the Wear OS module wear/ end-to-end — WearMainActivity, Tile service, complication service, data-layer
listener, snapshot store and expiry, string resources, manifest — and its contract with the phone app
(contracts/wear-snapshot, app/src/main/java/it/binario/app/wear/**). Lenses: payload versioning and backward compatibility,
expiry/revocation correctness, Tile/complication refresh behaviour and limits, round-screen layout, ambient mode, offline
and unpaired states, resource and string parity, and Play-for-Wear requirements (standalone flag, icon, version codes).`,
  'ios-app': `Area: the iPhone app ios/BinarioApp/** and the Swift package ios/BinarioKit/** (GTFS SQLite index reader,
planner, snapshot codec, localisation keys), plus ios/project.yml and resources. Lenses: Swift 6 strict-concurrency
correctness (actor isolation, Sendable, MainActor UI updates — anything that would not compile or would trap at runtime),
WatchConnectivity session lifecycle and message/application-context semantics, SQLite usage (statement finalisation,
thread confinement, error handling, index expiry), time arithmetic and locale, state restoration, accessibility and Dynamic
Type, empty/error states, and App Store-visible metadata generated from project.yml (Info.plist keys, privacy manifest
required-reason APIs versus APIs actually used, entitlements, icons, localisations).`,
  'apple-watch': `Area: the Apple Watch app and WidgetKit extension apple-watch/** (BinarioWatch, BinarioWatchWidgets,
Shared, Resources, tests) and its contract with the iPhone app (contracts/wear-snapshot/v1/ios-to-apple-watch-v1.json,
ios/BinarioKit snapshot codec). Lenses: snapshot decoding and versioning, App Group storage and expiry, complication and
timeline refresh budgets, relevance and stale-data presentation, localisation (Localizable.xcstrings completeness for all
five languages), Swift 6 concurrency, and App Store requirements for an embedded watch app (icons, bundle identifiers,
WKCompanionAppBundleIdentifier, privacy manifest).`,
  'companion': `Area: the Expo "Binario Companion" app companion-expo/** (TypeScript app, lib/, plugins, prebuilt android/
including its wear module and Glance widget, scripts/verify-*.mjs, i18n). First establish from docs/CHECKLIST_COMPANION.md,
README and app.config.ts whether it is meant to be published; audit it as a real Play candidate if yes, otherwise still
report crashes and misleading release tooling. Lenses: runtime crashes on Hermes (Intl, RegExp features, Date parsing),
state and storage bugs, widget update behaviour, wear payload compatibility with contracts/wear-snapshot, permissions and
manifest in android/, signing/version configuration, Play readiness (icons, adaptive icon, splash, versionCode,
targetSdk), prebuild idempotence (you may run CI=1 pnpm exec expo prebuild --platform android --clean --no-install in a
COPY under ${S}/audit/scratch-companion/ with node_modules symlinked from /home/user/binario/companion-expo/node_modules —
never in the repository — and diff against the committed android/), and whether pnpm test / pnpm run check pass at HEAD.`,
  'alexa': `Area: the alexa-companion/ server (Express, tRPC routers, Drizzle schema and migrations, Alexa skill request
handling, account linking, voice profile sync used by the apps, dashboards). Lenses: request validation and error handling,
authentication/authorisation of every router procedure, session and token handling, SSML and output escaping, database
migrations consistency with the schema (run the migration tests with the MariaDB script), time zone handling, operational
readiness (configuration from env, ports, health checks, logging of secrets), dependency and test health (pnpm test,
pnpm run check at HEAD), and what a self-hosting owner must configure. Note the earlier reports say parts depend on the
Manus OAuth identity provider; state precisely what works without it.`,
  'localization': `Lens: localisation completeness and correctness across all client apps, as a store-review and user-facing
bug class. Android: app/src/main/res/values/strings.xml (Italian default) versus values-en/-de/-fr/-es and the wear module:
missing/extra keys, placeholder and format-argument mismatches (%1$s vs %s, counts, plurals), apostrophe escaping,
translatable="false" misuse, untranslated Italian in non-Italian files, hard-coded user-facing Italian in Kotlin
(UI, widgets, notifications, Wear payload texts, UiMessage keys — exclude deliberate data-layer diagnostics), locale
configuration (localeConfig, resourceConfigurations). iOS/Watch: Localizable.strings parity across the five .lproj and
against BinarioLocalizationKeys, Localizable.xcstrings completeness, stringsdict plurals, CFBundleLocalizations. Companion:
its i18n files. Write a parity script under ${S}/audit/scratch-localization/ and report exact keys.`,
  'privacy-docs': `Lens: privacy, legal and release documentation truthfulness, as required by both stores. Check PRIVACY.md
(the stores need a public URL — is one referenced and are the placeholders listed?), in-app privacy disclosures (Android
settings/privacy screens, iOS Settings view) versus actual behaviour: which hosts are contacted by release builds (note
BuildConfig.PERSONAL_REALTIME_SOURCES and its default per build type in app/build.gradle.kts, the hosts in the data layer,
jbb.ghsq.de, Wear Data Layer, Alexa linking, backups), docs/store/PLAY_DATA_SAFETY.md host classification versus the code,
Apple App Privacy answers derivable from code, third-party data attributions required by the data sources' licences
(docs/data-sources, GTFS pack licences shown in the apps), open-source licence notices for bundled dependencies on Android
and iOS, docs/RELEASE_BUILD.md and docs/store/* accuracy versus the actual build scripts and CI, README claims versus
existing files, version/changelog consistency (gradle.properties, ios MARKETING_VERSION, companion app.config.ts) and
release notes for store listings.`,
  'ci-release': `Area: CI and release engineering — .github/workflows/*.yml, .github/ci-inputs-lock.json, tools/verify_*.py,
tools/build_*.py, tests/, gradle wrapper and dependency verification, signing configuration (BINARIO_RELEASE_* and
sign_release dispatch), version code/name derivation for app, wear and companion, App Store archive step, XcodeGen
generation, GTFS pack refresh workflow and expiry gates, artifact uploads. Lenses: does each workflow actually gate what the
docs claim, would a release build from a clean clone succeed (missing files, ignored paths, environment assumptions),
reproducibility checks, secrets handling, pinned actions and tool checksums, path filters that skip relevant changes,
and what the owner must configure (secrets, keystore, team id) with exact names. Reproduce verifier behaviour locally
where Python suffices.`,
  'e2e-flows': `Lens: end-to-end user journeys across products, read across modules rather than within one. Trace at least:
first launch with no network; search a station and open the departures board; open a train detail and follow a
realtime update; plan a journey between two stations (phone and iPhone); set a commuter alert and receive it; add the
home-screen widget and see it refresh; enable watch sharing and see the snapshot on Wear OS and on Apple Watch (expiry,
revocation, re-enable); link Alexa from the app; an embedded GTFS pack expiring while the app is installed; upstream
source outage or changed HTML; process death mid-flow; language switch. For each journey state where it breaks or
dead-ends, with file:line evidence. Also compare the behaviour of the three clients for the same journey and flag
contradictions users will notice (different data, different labels, different expiry rules).`,
  'security': `Lens: security of the clients and the server. Network (cleartext, certificate handling, hosts, user-agent and
rate behaviour towards third-party sites), parsing of untrusted HTML/JSON/ZIP/protobuf (jsoup, GTFS zips, realtime
protobuf — resource exhaustion and crashes), Android components (exported activities/receivers/providers, intent and deep
link handling, PendingIntent flags, widget RemoteViews/Glance actions, backup of sensitive data, logging of personal data),
iOS/Watch (App Group data, URL handling), Companion (secrets in app.config, storage), alexa-companion (auth on every
procedure, account-linking token validation, SQL via Drizzle, SSML injection, CORS, headers, dependency advisories), secrets
or credentials committed anywhere in the repository (including archives/ and artifacts/), and supply-chain pins.`,
}

const FINDINGS_SCHEMA = {
  type: 'object',
  properties: {
    summary: { type: 'string' },
    findings: { type: 'array', items: { type: 'object', properties: {
      id: { type: 'string', description: 'AU-<key>-<n>' },
      title: { type: 'string' },
      severity: { type: 'string', enum: ['critical', 'high', 'medium', 'low', 'info'] },
      category: { type: 'string', enum: ['crash', 'correctness', 'security', 'privacy', 'store-policy', 'localization', 'ux', 'performance', 'docs', 'ci'] },
      product: { type: 'string', enum: ['android', 'wear', 'ios', 'watch', 'companion', 'alexa', 'repo'] },
      evidence: { type: 'string', description: 'file:line references, the input/state and the wrong outcome' },
      fix_in_repo: { type: 'string', description: 'concrete change to make in the repository, or empty' },
      files: { type: 'array', items: { type: 'string' } },
      owner_action: { type: 'string', description: 'what only the owner can do outside the repository, or empty' },
      verification: { type: 'string', description: 'how an implementer proves the fix locally; "static only" if no tool applies' },
      effort: { type: 'string', enum: ['S', 'M', 'L'] },
      duplicate_of: { type: 'string', description: 'id from items.json if already known, else empty' },
      confidence: { type: 'string', enum: ['high', 'medium', 'low'] },
    }, required: ['id', 'title', 'severity', 'category', 'product', 'evidence', 'fix_in_repo', 'files', 'owner_action', 'verification', 'effort', 'duplicate_of', 'confidence'] } },
    could_not_check: { type: 'array', items: { type: 'string' } },
  },
  required: ['summary', 'findings', 'could_not_check'],
}

const VERDICT_SCHEMA = {
  type: 'object',
  properties: {
    verdicts: { type: 'array', items: { type: 'object', properties: {
      id: { type: 'string' }, verdict: { type: 'string', enum: ['confirmed', 'downgraded', 'refuted'] },
      severity: { type: 'string', enum: ['critical', 'high', 'medium', 'low', 'info'] }, reason: { type: 'string' }, fix_in_repo: { type: 'string' },
    }, required: ['id', 'verdict', 'severity', 'reason', 'fix_in_repo'] } },
    missed: { type: 'array', items: { type: 'object', properties: {
      id: { type: 'string', description: 'AU-<key>-M<n>' }, title: { type: 'string' },
      severity: { type: 'string', enum: ['critical', 'high', 'medium', 'low', 'info'] },
      category: { type: 'string', enum: ['crash', 'correctness', 'security', 'privacy', 'store-policy', 'localization', 'ux', 'performance', 'docs', 'ci'] },
      product: { type: 'string', enum: ['android', 'wear', 'ios', 'watch', 'companion', 'alexa', 'repo'] },
      evidence: { type: 'string' }, fix_in_repo: { type: 'string' }, files: { type: 'array', items: { type: 'string' } },
      owner_action: { type: 'string' }, verification: { type: 'string' }, effort: { type: 'string', enum: ['S', 'M', 'L'] },
    }, required: ['id', 'title', 'severity', 'category', 'product', 'evidence', 'fix_in_repo', 'files', 'owner_action', 'verification', 'effort'] } },
  },
  required: ['verdicts', 'missed'],
}

const RECHECK_SCHEMA = {
  type: 'object',
  properties: {
    results: { type: 'array', items: { type: 'object', properties: {
      id: { type: 'string' },
      status: { type: 'string', enum: ['fixed', 'open', 'partial', 'not_a_bug', 'owner_action', 'cannot_verify'] },
      evidence: { type: 'string', description: 'file:line at HEAD proving the status' },
      severity_now: { type: 'string', enum: ['critical', 'high', 'medium', 'low', 'info'] },
      fix_in_repo: { type: 'string' }, files: { type: 'array', items: { type: 'string' } },
      owner_action: { type: 'string' }, effort: { type: 'string', enum: ['S', 'M', 'L'] },
    }, required: ['id', 'status', 'evidence', 'severity_now', 'fix_in_repo', 'files', 'owner_action', 'effort'] } },
  },
  required: ['results'],
}

const finderPrompt = (key) => `You are an independent senior auditor examining the Binario repository before its store submissions.
${CONTEXT}
${FINDERS[key]}
Work from the actual files at HEAD. Report every real defect with exact evidence and an honest severity (critical = crash,
data loss, security hole or store rejection; high = wrong result users will notice or a reviewer will reject; medium =
degraded behaviour with a workaround; low = minor; info = owner decision). Number findings AU-${key}-1, -2, ...
When you finish, ALSO write your full result as JSON to ${S}/audit/find-${key}.json (same structure as your structured output).`

const skepticPrompt = (key, result) => `You are an adversarial reviewer. A colleague audited the Binario repository for this scope:
"${FINDERS[key].split('\n')[0]}"
${CONTEXT}
Their findings:
${JSON.stringify(result.findings, null, 1)}
For each finding, verify it yourself against the files at HEAD: is the evidence real and current, is the severity right,
is the proposed repository fix correct, complete and the smallest change that fixes the defect? Mark confirmed, downgraded
or refuted with the concrete reason; refute anything already fixed at HEAD or that is a preference rather than a defect.
Then look for defects in this scope the colleague MISSED (different files, different inputs, different lens) and list them
under "missed" with the same rigour, numbered AU-${key}-M1, -M2, ...
When you finish, ALSO write your full result as JSON to ${S}/audit/verify-${key}.json.`

const recheckPrompt = (name, ids) => `You are re-verifying previously reported findings against the current repository HEAD.
${CONTEXT}
Load ${S}/low/items.json (a JSON list; fields id, text, file, status, severity, area) and take exactly these ids:
${JSON.stringify(ids)}
For each item read its text (the reports under docs/ may give the original context: search by the id), locate the code it
refers to at HEAD, and decide: fixed (cite the code or commit that fixes it), open (still reproducible: cite file:line and
the wrong behaviour), partial (what remains), not_a_bug (why the report was wrong, with evidence), owner_action (nothing to
change in the repository), cannot_verify (needs a device/toolchain: say what). For open/partial items give the concrete
repository fix. Severity_now is your own current assessment, not the original one. Every id in the list must appear in
your results exactly once. When you finish, ALSO write your full result as JSON to ${S}/audit/recheck-${name}.json.`

const criticPrompt = () => `You are the completeness critic of an audit of the Binario repository.
${CONTEXT}
The audit's results are the JSON files ${S}/audit/find-*.json (finders), ${S}/audit/verify-*.json (adversarial skeptics)
and ${S}/audit/recheck-*.json (re-verification of earlier findings). Read them all. Then answer: what is missing — a
product area, a lens, a user journey, a file family or a store requirement that no finder covered or that was covered only
superficially; claims whose evidence is weak; contradictions between finders and skeptics; and defects you can establish
yourself by reading the code in the gaps you identify (give them ids AU-critic-<n> with full evidence and fixes). Write
your result as JSON to ${S}/audit/critic.json with the structure {gaps:[{area, why, how_to_cover}], weak:[{id, why}],
contradictions:[{ids, resolution}], findings:[...same fields as a finder finding...]} and return it.`

if (MODE === 'critic') {
  phase('Critic')
  const c = await agent(criticPrompt(), { label: 'critic', phase: 'Critic' })
  return c
}

const finderKeys = KEYS.filter((k) => FINDERS[k])
const lowKeys = KEYS.filter((k) => LOW[k])
const unknown = KEYS.filter((k) => !FINDERS[k] && !LOW[k])
if (unknown.length) log(`unknown keys ignored: ${unknown.join(', ')}`)

const findResults = await pipeline(
  finderKeys,
  (key) => agent(finderPrompt(key), { label: `find:${key}`, phase: 'Find', schema: FINDINGS_SCHEMA }).then((r) => ({ key, find: r })),
  async (r) => {
    if (!r.find || !SKEPTIC) return { ...r, verify: null }
    const verify = await agent(skepticPrompt(r.key, r.find), { label: `verify:${r.key}`, phase: 'Verify', schema: VERDICT_SCHEMA })
    return { ...r, verify }
  },
)
const recheckResults = await parallel(lowKeys.map((name) => () =>
  agent(recheckPrompt(name, LOW[name]), { label: `recheck:${name}`, phase: 'Recheck', schema: RECHECK_SCHEMA, effort: 'medium' })
    .then((r) => ({ key: name, recheck: r }))))

const out = [...findResults.filter(Boolean), ...recheckResults.filter(Boolean)]
const summary = out.map((r) => {
  if (r.find) {
    const v = r.verify ? r.verify.verdicts : []
    return { key: r.key, findings: r.find.findings.length, confirmed: v.filter((x) => x.verdict === 'confirmed').length,
      downgraded: v.filter((x) => x.verdict === 'downgraded').length, refuted: v.filter((x) => x.verdict === 'refuted').length,
      missed: r.verify ? r.verify.missed.length : null, could_not_check: r.find.could_not_check }
  }
  const res = r.recheck ? r.recheck.results : []
  const count = (s) => res.filter((x) => x.status === s).length
  return { key: r.key, items: res.length, fixed: count('fixed'), open: count('open'), partial: count('partial'),
    not_a_bug: count('not_a_bug'), owner_action: count('owner_action'), cannot_verify: count('cannot_verify') }
})
for (const s of summary) log(JSON.stringify(s))
return summary
