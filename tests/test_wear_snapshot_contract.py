"""The watch snapshot contract v1 (contracts/wear-snapshot/v1) and the four programs that share it.

Wear OS and the Apple Watch depend on neither the Android app nor BinarioKit: their common base is the
contract's README and fixtures. These checks keep the fixtures within the contract's closed key set, and
the capability names of the optional fields identical in the README and in every producer and decoder.
"""

from __future__ import annotations

import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/wear-snapshot/v1"
BASE_KEYS = {
    "schemaVersion", "generatedAtEpochMillis", "expiresAtEpochMillis", "ttlMillis", "provenanceProducer",
    "provenanceDataNature", "provenanceSourceLabel", "provenanceObservedAtEpochMillis", "operationalState",
    "freshness", "confidence", "routeId", "routeLabel", "direction", "notice", "serviceCount",
}
SERVICE_KEYS = ("Number", "Operator", "Category", "Destination", "Scheduled", "Expected", "Status", "Platform", "DelayMinutes")
VEHICLES = {"TRAIN", "BUS_TRENITALIA", "FRECCIALINK", "ITALOBUS", "BUS", "REPLACEMENT_BUS"}
CAPABILITIES = {"binario_wear_reliability_v1", "binario_wear_vehicle_v1"}
# Where each fixture is read: the encoder that recreates it and the decoders that accept it.
READERS = {
    "android-to-wear-v1.json": ["app/src/test/java/it/binario/app/wear/AndroidWearSnapshotContractTest.kt",
                                "wear/src/test/java/it/binario/wear/model/WearSnapshotCodecTest.kt"],
    "android-to-wear-v1-reliability.json": ["app/src/test/java/it/binario/app/wear/AndroidWearSnapshotContractTest.kt",
                                            "wear/src/test/java/it/binario/wear/model/WearReliabilityTest.kt"],
    "android-to-wear-v1-vehicle.json": ["app/src/test/java/it/binario/app/wear/AndroidWearSnapshotContractTest.kt",
                                        "wear/src/test/java/it/binario/wear/model/WearVehicleTest.kt"],
    "companion-to-wear-v1.json": ["wear/src/test/java/it/binario/wear/model/WearReliabilityTest.kt"],
    "ios-to-apple-watch-v1.json": ["ios/BinarioKit/Tests/BinarioKitTests/WearSnapshotV1Tests.swift",
                                   "apple-watch/BinarioWatchTests/WatchSnapshotTests.swift",
                                   "wear/src/test/java/it/binario/wear/model/WearSnapshotCodecTest.kt"],
    "ios-to-apple-watch-v1-optional.json": ["ios/BinarioKit/Tests/BinarioKitTests/WearSnapshotV1Tests.swift",
                                            "apple-watch/BinarioWatchTests/WatchSnapshotTests.swift",
                                            "wear/src/test/java/it/binario/wear/model/WearVehicleTest.kt"],
}


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


class WearSnapshotContractTests(unittest.TestCase):
    def test_every_fixture_is_documented_read_and_within_the_closed_key_set(self) -> None:
        readme = read("contracts/wear-snapshot/v1/README.md")
        fixtures = sorted(path.name for path in CONTRACT.glob("*.json"))
        self.assertEqual(sorted(READERS), fixtures)
        for name in fixtures:
            with self.subTest(fixture=name):
                self.assertIn(f"`{name}`", readme)
                stem = name.removesuffix(".json")
                for reader in READERS[name]:
                    self.assertIn(stem, read(reader), reader)
                payload = json.loads((CONTRACT / name).read_text(encoding="utf-8"))
                count = payload["serviceCount"]
                allowed = set(BASE_KEYS)
                for index in range(count):
                    allowed |= {f"service{index}{key}" for key in SERVICE_KEYS}
                    allowed |= {f"service{index}ReliabilityOnTime", f"service{index}ReliabilitySamples", f"service{index}Vehicle"}
                    self.assertTrue({f"service{index}{key}" for key in SERVICE_KEYS} <= set(payload))
                    pair = [key for key in (f"service{index}ReliabilityOnTime", f"service{index}ReliabilitySamples") if key in payload]
                    self.assertIn(len(pair), (0, 2), "the reliability pair comes whole or not at all")
                    if pair:
                        samples = payload[f"service{index}ReliabilitySamples"]
                        self.assertTrue(5 <= samples <= 10_000)
                        self.assertTrue(0 <= payload[f"service{index}ReliabilityOnTime"] <= samples)
                    if f"service{index}Vehicle" in payload:
                        self.assertIn(payload[f"service{index}Vehicle"], VEHICLES)
                self.assertTrue(set(payload) <= allowed, sorted(set(payload) - allowed))
                self.assertTrue(BASE_KEYS <= set(payload))
                self.assertLessEqual(len(json.dumps(payload, ensure_ascii=False).encode("utf-8")), 8_192)

    def test_a_bus_is_sent_with_its_label_never_as_a_train(self) -> None:
        for name in ("android-to-wear-v1-vehicle.json", "ios-to-apple-watch-v1-optional.json"):
            payload = json.loads((CONTRACT / name).read_text(encoding="utf-8"))
            for index in range(payload["serviceCount"]):
                if payload.get(f"service{index}Vehicle", "TRAIN") != "TRAIN":
                    with self.subTest(fixture=name, service=index):
                        self.assertNotIn(payload[f"service{index}Category"].lower(), {"treno", "train", "bus"})

    def test_the_capability_names_are_the_same_in_the_readme_and_in_every_program(self) -> None:
        readme = read("contracts/wear-snapshot/v1/README.md")
        wear = read("wear/src/main/res/values/wear.xml")
        android = read("app/src/main/java/it/binario/app/wear/AndroidWearSnapshotContract.kt")
        iphone = read("ios/BinarioKit/Sources/BinarioKit/WearSnapshotV1.swift")
        watch = read("apple-watch/BinarioWatch/PhoneConnectivity.swift")
        for capability in sorted(CAPABILITIES):
            with self.subTest(capability=capability):
                self.assertIn(f"`{capability}`", readme)
                self.assertIn(f"<item>{capability}</item>", wear)
                self.assertIn(f'"{capability}"', android)
                self.assertIn(f'"{capability}"', iphone)
                self.assertIn(f'"{capability}"', watch)
        # The Apple Watch declares them under the key the iPhone reads.
        key = "binario_watch_capabilities"
        self.assertIn(f'static let contextKey = "{key}"', iphone)
        self.assertIn(f'static let contextKey = "{key}"', watch)
        self.assertIn(f'"{key}"', readme)
        self.assertIn("WatchCapabilities.decode(session.receivedApplicationContext)", read("ios/BinarioApp/WCSessionAdapter.swift"))
        self.assertIn("didReceiveApplicationContext applicationContext: [String: Any]) {", read("ios/BinarioApp/WCSessionAdapter.swift"))

    def test_the_vehicles_are_the_shared_cases_of_both_phones(self) -> None:
        cases = json.loads(read("contracts/shared-rules/v1/journey-leg-vehicles-v1.json"))
        text = json.dumps(cases)
        swift = read("ios/BinarioKit/Sources/BinarioKit/TimetableVehicle.swift")
        kotlin = read("app/src/main/java/it/binario/app/journeys/JourneyResultsPresenter.kt")
        watch = read("apple-watch/Shared/WatchModels.swift")
        wear = read("wear/src/main/java/it/binario/wear/model/WearSnapshot.kt")
        for vehicle in sorted(VEHICLES):
            with self.subTest(vehicle=vehicle):
                self.assertIn(f'"{vehicle}"', swift)
                self.assertRegex(kotlin, rf"\b{vehicle}\(")
                self.assertIn(f'"{vehicle}"', watch)
                self.assertRegex(wear, rf"\b{vehicle}\(")
                if vehicle != "TRAIN":
                    self.assertIn(vehicle, text)
        self.assertTrue(re.search(r"UNKNOWN\(false\)", wear))
        self.assertIn('case unknown = "UNKNOWN"', watch.split("enum WatchVehicle", 1)[1])


if __name__ == "__main__":
    unittest.main()
