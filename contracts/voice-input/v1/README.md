# Voice input v1: shared dictation cases

`dictation-cases.json` holds the sentences that both phones must read alike: the companion app's dictation examples and the forms its audit found misread (EXPO_PORT_PLAN §3.3), plus the cases of the Android parser's own tests. Two tests read it:

- Android: `app/src/test/java/it/binario/app/PlannerVoiceDictationFormsTest.kt`, against `PlannerVoiceParser` (`PlannerVoiceGrammar.kt`).
- iPhone: `ios/BinarioKit/Tests/BinarioKitTests/RouteDictationTests.swift`, against `RouteDictation` (`DictationGrammar.swift`), a port of the Android parser.

Android's microphone sends the recognizer's text to the parser. The iPhone has no microphone of its own: the user types the sentence or says it with the keyboard's dictation key, and the same parser rules read it.

## Format

- `stations`: test stations, with `id`, `name` and `aliases`. Android builds each one with the city equal to its name. The aliases hold what the tests used to give as the city («Roma» for Roma Termini).
- `stationSets`: named lists of station ids. A case names one of them.
- `normalization`: `text` → `expected`, as the parser compares text. `foldLatinMarks: false` keeps Latin accents (Vietnamese). `joinNativeWords: true` tests the joining of words in Chinese, Japanese, Thai and Burmese.
- `offers`: per language code, whether a route, a station and a train number are read. A station is not read in the eight languages written in another script: their keyboards and recognizers write station names in that script, and the catalogue has them only in Latin letters.
- `cases`: each case has:
  - `spoken` read in `language` for a `target` (`route`, `station`, `train`) against the stations of its set;
  - `today`, or `now` (an instant) with `deviceZone`: the day is then Italy's date at that instant, whatever the phone's zone;
  - `expect`: only the keys present are checked, and `null` means no value. The keys are `status` (`confirmed`, `needsReview`, `unresolved`), `origin`, `destination`, `station` (station ids), `trainNumber`, `date` (`yyyy-MM-dd`), `time` (`HH:mm`), `returnJourney`, `feedback` (`confirmed`, `ambiguous`, `incomplete`, `dateTimeOnly`, `unresolved`), `candidates` (station ids per role) and `alternatives` (role and names shown when stations are ambiguous).

## Rules

- Language codes are the parser's: Android writes Indonesian as `in`, the iPhone as `id`. Both are read.
- A change to a grammar on either phone comes with cases here, so that the other phone's test fails until it is ported.
- The Android tests that read Android string resources (the hints «Prova: …») stay in `PlannerVoiceLanguagesTest`. The iPhone checks its own texts in `RouteDictationTests`.
- Station names are matched only in their Latin spelling. No native-script station name is invented.
