import { readFileSync } from "node:fs";
import path from "node:path";
import { DatabaseSync } from "node:sqlite";
import { APPLICATION_ID, USER_VERSION } from "./repository";

/**
 * Test support: synthetic timetable indexes in the schema of the bundled one (userVersion 2 of
 * tools/build_ios_gtfs_schedule.py, as the iPhone tests' `GTFSFixtureDatabase` writes it), built
 * from the rows of contracts/shared-rules/v1/next-trains-v1.json or of a test.
 */

export const NEXT_TRAINS_CONTRACT = path.resolve(import.meta.dirname, "..", "..", "..", "contracts", "shared-rules", "v1", "next-trains-v1.json");

export type IndexRows = {
  packs: Array<{ id: number; sourceId: string; attribution: string; lastServiceDate: number }>;
  agencies: Array<{ id: number; pack: number; externalId: string; name: string; timezone: string }>;
  routes: Array<{ id: number; pack: number; agency: number; externalId: string; shortName: string | null; longName: string | null; routeType: number }>;
  services: Array<{ id: number; pack: number; externalId: string; startDate: number; dayCount: number; activeDays: string }>;
  stops: Array<{ id: number; pack: number; externalId: string; name: string; catalogStationId: string | null; railwayCode: string | null }>;
  /** `calls`: [stop, arrival, departure, pickupType = 0, dropOffType = 0] in stop order. */
  trips: Array<{ id: number; pack: number; route: number; service: number; externalId: string; shortName: string | null; headsign?: string | null; calls: number[][] }>;
};

export type ContractStation = { id: string; name: string; railwayCode: string | null };
export type ContractTrain = { time: string; number: string; category: string; operator: string; destination: string; vehicle: string };
export type NextTrainsContract = {
  index: IndexRows;
  cases: Array<{
    why: string;
    origin: ContractStation;
    destination: ContractStation;
    at: string;
    expected: { trains: ContractTrain[] } | { unavailable: "noRoute" | "scheduleExpired" };
  }>;
};

export function nextTrainsContract(): NextTrainsContract {
  return JSON.parse(readFileSync(NEXT_TRAINS_CONTRACT, "utf8")) as NextTrainsContract;
}

const SCHEMA = `
CREATE TABLE metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL) WITHOUT ROWID;
CREATE TABLE pack (id INTEGER PRIMARY KEY, source_id TEXT NOT NULL UNIQUE, attribution TEXT NOT NULL, last_service_date INTEGER NOT NULL);
CREATE TABLE agency (id INTEGER PRIMARY KEY, pack_id INTEGER NOT NULL REFERENCES pack(id), external_id TEXT NOT NULL, name TEXT NOT NULL, timezone TEXT NOT NULL, UNIQUE(pack_id, external_id));
CREATE TABLE route (id INTEGER PRIMARY KEY, pack_id INTEGER NOT NULL REFERENCES pack(id), agency_id INTEGER NOT NULL REFERENCES agency(id), external_id TEXT NOT NULL, short_name TEXT, long_name TEXT, route_type INTEGER NOT NULL, UNIQUE(pack_id, external_id));
CREATE TABLE service (id INTEGER PRIMARY KEY, pack_id INTEGER NOT NULL REFERENCES pack(id), external_id TEXT NOT NULL, start_date INTEGER NOT NULL, day_count INTEGER NOT NULL CHECK(day_count > 0), active_days BLOB NOT NULL, UNIQUE(pack_id, external_id), CHECK(length(active_days) = (day_count + 7) / 8));
CREATE TABLE trip (id INTEGER PRIMARY KEY, pack_id INTEGER NOT NULL REFERENCES pack(id), route_id INTEGER NOT NULL REFERENCES route(id), service_id INTEGER NOT NULL REFERENCES service(id), external_id TEXT NOT NULL, headsign TEXT, short_name TEXT, direction_id INTEGER, bikes_allowed INTEGER CHECK(bikes_allowed IN (1, 2)), UNIQUE(pack_id, external_id));
CREATE TABLE stop (id INTEGER PRIMARY KEY, pack_id INTEGER NOT NULL REFERENCES pack(id), external_id TEXT NOT NULL, name TEXT NOT NULL, latitude REAL, longitude REAL, catalog_station_id TEXT, railway_code TEXT, trenitalia_location_id TEXT, UNIQUE(pack_id, external_id));
CREATE TABLE stop_time (trip_id INTEGER NOT NULL REFERENCES trip(id), stop_sequence INTEGER NOT NULL, stop_id INTEGER NOT NULL REFERENCES stop(id), arrival_seconds INTEGER, departure_seconds INTEGER, pickup_type INTEGER NOT NULL, drop_off_type INTEGER NOT NULL, PRIMARY KEY(trip_id, stop_sequence)) WITHOUT ROWID;
CREATE UNIQUE INDEX idx_route_pack_external ON route(pack_id, external_id);
CREATE UNIQUE INDEX idx_service_pack_external ON service(pack_id, external_id);
CREATE UNIQUE INDEX idx_trip_pack_external ON trip(pack_id, external_id);
CREATE INDEX idx_trip_route_service ON trip(route_id, service_id);
CREATE UNIQUE INDEX idx_stop_pack_external ON stop(pack_id, external_id);
CREATE INDEX idx_stop_catalog_station ON stop(catalog_station_id) WHERE catalog_station_id IS NOT NULL;
CREATE INDEX idx_stop_time_stop_departure ON stop_time(stop_id, departure_seconds, trip_id);
`;

/** Writes the index of `rows` to `file` (a new file) and closes it. */
export function writeIndex(file: string, rows: IndexRows, fingerprint = "a".repeat(64)) {
  const database = new DatabaseSync(file);
  try {
    database.exec(SCHEMA);
    database.exec(`PRAGMA application_id = ${APPLICATION_ID}; PRAGMA user_version = ${USER_VERSION};`);
    const metadata = database.prepare("INSERT INTO metadata VALUES (?, ?)");
    for (const [key, value] of [
      ["schema_version", String(USER_VERSION)],
      ["input_fingerprint_sha256", fingerprint],
      ["source_generated_at", "2024-03-30"],
      ["row_counts_json", "{}"],
    ])
      metadata.run(key, value);
    const insert = (sql: string, values: Array<string | number | null | Uint8Array>) => database.prepare(sql).run(...values);
    for (const pack of rows.packs) insert("INSERT INTO pack VALUES (?, ?, ?, ?)", [pack.id, pack.sourceId, pack.attribution, pack.lastServiceDate]);
    for (const agency of rows.agencies) insert("INSERT INTO agency VALUES (?, ?, ?, ?, ?)", [agency.id, agency.pack, agency.externalId, agency.name, agency.timezone]);
    for (const route of rows.routes)
      insert("INSERT INTO route VALUES (?, ?, ?, ?, ?, ?, ?)", [route.id, route.pack, route.agency, route.externalId, route.shortName, route.longName, route.routeType]);
    for (const service of rows.services)
      insert("INSERT INTO service VALUES (?, ?, ?, ?, ?, ?)", [service.id, service.pack, service.externalId, service.startDate, service.dayCount, Buffer.from(service.activeDays, "hex")]);
    for (const stop of rows.stops)
      insert("INSERT INTO stop VALUES (?, ?, ?, ?, NULL, NULL, ?, ?, NULL)", [stop.id, stop.pack, stop.externalId, stop.name, stop.catalogStationId, stop.railwayCode]);
    for (const trip of rows.trips) {
      insert("INSERT INTO trip VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL)", [trip.id, trip.pack, trip.route, trip.service, trip.externalId, trip.headsign ?? null, trip.shortName]);
      trip.calls.forEach(([stop, arrival, departure, pickup = 0, dropOff = 0], index) =>
        insert("INSERT INTO stop_time VALUES (?, ?, ?, ?, ?, ?, ?)", [trip.id, index + 1, stop, arrival, departure, pickup, dropOff])
      );
    }
  } finally {
    database.close();
  }
}

const H = (hours: number, minutes = 0) => (hours * 60 + minutes) * 60;
const call = (stop: number, seconds: number) => [stop, seconds, seconds];

/**
 * The skill tests' timetable, on the station codes of the punctuality fixtures
 * (server/punctuality/testFixtures.ts): daily Trenitalia trains and a bus from Roma Termini to
 * Tivoli, and FR 9558 from Milano Centrale to Roma Termini, every day of November and December 2026.
 */
export function skillTimetableRows(): IndexRows {
  const stop = (id: number, externalId: string, name: string, code: string) => ({ id, pack: 1, externalId, name, catalogStationId: code, railwayCode: code });
  const trip = (id: number, route: number, shortName: string, calls: number[][]) => ({ id, pack: 1, route, service: 1, externalId: `T${id}`, shortName, calls });
  return {
    packs: [{ id: 1, sourceId: "trenitalia-gtfs", attribution: "Trenitalia", lastServiceDate: 20261231 }],
    agencies: [{ id: 1, pack: 1, externalId: "TI", name: "TRENITALIA", timezone: "Europe/Rome" }],
    routes: [
      { id: 1, pack: 1, agency: 1, externalId: "REG", shortName: "0083_REG", longName: null, routeType: 2 },
      { id: 2, pack: 1, agency: 1, externalId: "FR", shortName: "0083_FR", longName: null, routeType: 2 },
      { id: 3, pack: 1, agency: 1, externalId: "BUS", shortName: "0083_BUS", longName: null, routeType: 3 },
    ],
    // 61 days from 1 November 2026, every one active.
    services: [{ id: 1, pack: 1, externalId: "daily", startDate: 20261101, dayCount: 61, activeDays: "FFFFFFFFFFFFFF1F" }],
    stops: [
      stop(1, "ROMA", "Roma Termini", "S08409"),
      stop(2, "GUIDONIA", "Guidonia Montecelio S. Angelo", "S08502"),
      stop(3, "TIVOLI", "Tivoli", "S08508"),
      stop(4, "MILANO", "Milano Centrale", "S01700"),
      stop(5, "FIRENZE", "Firenze S. M. Novella", "S06421"),
    ],
    trips: [
      trip(1, 1, "2157", [call(1, H(8, 12)), call(2, H(8, 40)), call(3, H(9))]),
      trip(2, 1, "2161", [call(1, H(9, 12)), call(3, H(10))]),
      trip(3, 3, "7001", [call(1, H(9, 30)), call(3, H(10, 30))]),
      trip(4, 1, "2165", [call(1, H(10, 12)), call(3, H(11))]),
      trip(5, 2, "9558", [call(4, H(6)), call(5, H(7, 40)), call(1, H(9, 10))]),
    ],
  };
}
