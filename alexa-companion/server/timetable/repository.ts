import { DatabaseSync } from "node:sqlite";
import { PackRoles } from "@shared/generated/sharedData";
import { keptRuns, packRank, coreNumber, type TimetableRun } from "./copies";
import { bounded, legMode, lineLabel, normalizedWhitespace, operatorName, vehicleOf, type TimetableVehicle } from "./naming";
import { TrainNumberQuery } from "./trainNumbers";
import { decodedDate, encodedDate, gtfsInstant, isTimeZone, localDay, timeString } from "./wallClock";

/**
 * Read-only queries of the programmed timetable index the iPhone bundles
 * (ios/Resources/binario_gtfs_schedule_v1.sqlite.zlib, built by tools/build_ios_gtfs_schedule.py
 * from Android's packs), so the skill reads the same trains as the apps. The checks and the
 * next-trains query are those of the iPhone's `SQLiteGTFSJourneyRepository`:
 * contracts/shared-rules/v1/next-trains-v1.json holds the cases both read. A database that is not
 * the expected index fails closed.
 */

export const APPLICATION_ID = 0x424e4754; // "BNGT"
/** 2: `trip.bikes_allowed`. */
export const USER_VERSION = 2;
/** The planner's bound: the first trains of the next 24 hours. */
export const MAXIMUM_RESULTS = 8;

const EXPECTED_TABLES: Readonly<Record<string, readonly string[]>> = {
  metadata: ["key", "value"],
  pack: ["id", "source_id", "attribution", "last_service_date"],
  agency: ["id", "pack_id", "external_id", "name", "timezone"],
  route: ["id", "pack_id", "agency_id", "external_id", "short_name", "long_name", "route_type"],
  service: ["id", "pack_id", "external_id", "start_date", "day_count", "active_days"],
  trip: ["id", "pack_id", "route_id", "service_id", "external_id", "headsign", "short_name", "direction_id", "bikes_allowed"],
  stop: ["id", "pack_id", "external_id", "name", "latitude", "longitude", "catalog_station_id", "railway_code", "trenitalia_location_id"],
  stop_time: ["trip_id", "stop_sequence", "stop_id", "arrival_seconds", "departure_seconds", "pickup_type", "drop_off_type"],
};
/**
 * The city networks' tables (metro, tram, funiculars), present together or not at all. The skill reads trains
 * between catalogue stations, and a city stop is never one, so it only accepts them.
 */
const URBAN_TABLES: Readonly<Record<string, readonly string[]>> = {
  urban_stop: ["stop_id", "modes"],
  urban_walk: ["catalog_station_id", "stop_id", "minutes"],
};
const EXPECTED_INDICES = new Set([
  "idx_route_pack_external", "idx_service_pack_external", "idx_trip_pack_external",
  "idx_trip_route_service", "idx_stop_pack_external", "idx_stop_catalog_station",
  "idx_stop_time_stop_departure",
]);
const MAXIMUM_RESOLVED_STOPS = 128;
const MAXIMUM_TIME_ZONES = 16;
const MAXIMUM_CANDIDATE_ROWS = 4_096;
const MAXIMUM_GTFS_SECONDS = 168 * 60 * 60 - 1;
const MAXIMUM_SERVICE_DAY_LOOKBACK = Math.floor(MAXIMUM_GTFS_SECONDS / (24 * 60 * 60));
const MAXIMUM_SERVICE_DAYS = 3_660;
const MAXIMUM_TRAIN_RUNS = 8;
const DAY_MS = 86_400_000;

/** Why the index cannot answer: a file that is not the expected index, or a query over its bounds. */
export class TimetableIndexError extends Error {
  constructor(readonly reason: "invalidDatabase" | "invalidSchema" | "invalidData" | "limitExceeded") {
    super(`timetable index: ${reason}`);
    this.name = "TimetableIndexError";
  }
}

/** A station of the catalogue as the index binds its stops: the catalogue id, and its ViaggiaTreno code when it has one. */
export type StationRef = { id: string; railwayCode?: string | null };

/** A train of the timetable, named as the iPhone's planner names it. */
export type TimetableService = {
  id: string;
  /** The trip's number, else its line, else its identifier (then `hasTrainNumber` is false). */
  number: string;
  hasTrainNumber: boolean;
  operatorName: string;
  /** The route's short name («FR», «REG», «S11»), else its long name, else «GTFS <type>». */
  category: string;
  /** The searched destination's stop name. */
  destination: string;
  /** Epoch milliseconds. */
  departure: number;
  /** «HH:mm» in the agency's time zone. */
  time: string;
  timeZone: string;
  vehicle: TimetableVehicle;
  attribution: string;
};

export type NextTrains =
  | { kind: "trains"; services: TimetableService[]; attributions: string[] }
  /** No train in the next 24 hours, or a station the index does not serve. */
  | { kind: "noRoute" }
  /** Every timetable that serves the route ended before the requested day. */
  | { kind: "scheduleExpired" };

/** One run of a train number on the requested day: its first and last stop with their times. */
export type TrainRun = {
  number: string;
  category: string;
  operatorName: string;
  originName: string;
  destinationName: string;
  /** The catalogue stations the first and last stops are bound to, when they are. */
  originStationId: string | null;
  destinationStationId: string | null;
  departure: number;
  arrival: number;
  departureTime: string;
  arrivalTime: string;
  timeZone: string;
  attribution: string;
};

type Row = Record<string, unknown>;

type Candidate = {
  id: string;
  number: string;
  departure: number;
  run: TimetableRun;
  service: TimetableService;
};

const text = (value: unknown): string | null => (typeof value === "string" ? value : null);
const nonEmpty = (value: string | null): string | null => (value ? value : null);
/** SQLite's integer accessor: NULL and non-integers read as 0, as `sqlite3_column_int64` does. */
const integer = (value: unknown): number => (typeof value === "number" ? Math.trunc(value) : typeof value === "bigint" ? Number(value) : 0);
const placeholders = (count: number) => Array.from({ length: count }, () => "?").join(",");

export class TimetableIndex {
  private readonly database: DatabaseSync;
  private readonly timeZones: readonly string[];
  readonly metadata: Readonly<Record<string, string>>;

  /** Opens and validates the index at `file`; throws `TimetableIndexError` for anything else. */
  constructor(file: string, expectedFingerprint?: string) {
    let database: DatabaseSync;
    try {
      database = new DatabaseSync(file, { readOnly: true });
      database.exec("PRAGMA query_only = ON");
    } catch {
      throw new TimetableIndexError("invalidDatabase");
    }
    this.database = database;
    try {
      this.metadata = this.validate();
      if (expectedFingerprint !== undefined && this.metadata.input_fingerprint_sha256 !== expectedFingerprint)
        throw new TimetableIndexError("invalidSchema");
      this.timeZones = this.agencyTimeZones();
    } catch (error) {
      database.close();
      throw error instanceof TimetableIndexError ? error : new TimetableIndexError("invalidDatabase");
    }
  }

  close() {
    this.database.close();
  }

  /** The day the index's sources were generated (yyyy-mm-dd). */
  get sourceGeneratedAt(): string {
    return this.metadata.source_generated_at;
  }

  private all(sql: string, ...parameters: Array<string | number>): Row[] {
    try {
      return this.database.prepare(sql).all(...parameters) as Row[];
    } catch {
      throw new TimetableIndexError("invalidDatabase");
    }
  }

  private scalar(sql: string): unknown {
    const row = this.all(sql)[0];
    if (!row) throw new TimetableIndexError("invalidDatabase");
    return Object.values(row)[0];
  }

  private validate(): Record<string, string> {
    if (integer(this.scalar("PRAGMA query_only")) !== 1) throw new TimetableIndexError("invalidDatabase");
    if (integer(this.scalar("PRAGMA application_id")) !== APPLICATION_ID) throw new TimetableIndexError("invalidDatabase");
    if (integer(this.scalar("PRAGMA user_version")) !== USER_VERSION) throw new TimetableIndexError("invalidDatabase");
    if (this.scalar("PRAGMA quick_check(1)") !== "ok") throw new TimetableIndexError("invalidDatabase");

    const tables = new Set(this.all("SELECT name FROM sqlite_master WHERE type = ?", "table").map(row => text(row.name)));
    const schema = { ...EXPECTED_TABLES, ...(Object.keys(URBAN_TABLES).some(name => tables.has(name)) ? URBAN_TABLES : {}) };
    const expected = Object.keys(schema);
    if (tables.size !== expected.length || !expected.every(name => tables.has(name))) throw new TimetableIndexError("invalidSchema");
    for (const [table, columns] of Object.entries(schema)) {
      const actual = this.all(`PRAGMA table_info(${table})`).map(row => text(row.name));
      if (actual.length !== columns.length || actual.some((name, index) => name !== columns[index]))
        throw new TimetableIndexError("invalidSchema");
    }
    const indices = this.all("SELECT name FROM sqlite_master WHERE type = ? AND name NOT LIKE 'sqlite_autoindex_%'", "index").map(row => text(row.name));
    if (indices.length !== EXPECTED_INDICES.size || !indices.every(name => name !== null && EXPECTED_INDICES.has(name)))
      throw new TimetableIndexError("invalidSchema");

    const metadata: Record<string, string> = {};
    for (const row of this.all("SELECT key, value FROM metadata")) {
      const key = text(row.key);
      const value = text(row.value);
      if (key === null || value === null || Object.hasOwn(metadata, key)) throw new TimetableIndexError("invalidSchema");
      metadata[key] = value;
    }
    if (metadata.schema_version !== String(USER_VERSION)) throw new TimetableIndexError("invalidSchema");
    if (metadata.input_fingerprint_sha256?.length !== 64) throw new TimetableIndexError("invalidSchema");
    if (!metadata.source_generated_at || parseSourceDate(metadata.source_generated_at) === null) throw new TimetableIndexError("invalidSchema");
    if (!metadata.row_counts_json) throw new TimetableIndexError("invalidSchema");
    return metadata;
  }

  private agencyTimeZones(): string[] {
    const zones: string[] = [];
    for (const row of this.all("SELECT DISTINCT timezone FROM agency ORDER BY timezone LIMIT ?", MAXIMUM_TIME_ZONES + 1)) {
      const zone = text(row.timezone);
      if (zone === null || !isTimeZone(zone)) throw new TimetableIndexError("invalidData");
      zones.push(zone);
    }
    if (!zones.length || zones.length > MAXIMUM_TIME_ZONES) throw new TimetableIndexError("limitExceeded");
    return zones;
  }

  /** The stops of `station`: those bound to its catalogue id, only those with its ViaggiaTreno code when it has one. */
  private resolve(station: StationRef): number[] {
    const matches = this.all("SELECT id, railway_code FROM stop WHERE catalog_station_id = ? ORDER BY id LIMIT ?", station.id, MAXIMUM_RESOLVED_STOPS + 1);
    if (matches.length > MAXIMUM_RESOLVED_STOPS) throw new TimetableIndexError("limitExceeded");
    const wanted = station.railwayCode ?? null;
    return matches.filter(row => wanted === null || text(row.railway_code) === wanted).map(row => integer(row.id));
  }

  /** The stops of every station a name stands for (an alias can name several), each once. */
  private resolveAll(stations: readonly StationRef[]): number[] {
    const ids = new Set<number>();
    for (const station of stations) for (const id of this.resolve(station)) ids.add(id);
    return [...ids];
  }

  /** True when the last service day of every pack holding trips is before the requested day. */
  private indexEnded(at: number, timeZone: string): boolean {
    const last = integer(this.scalar(
      "SELECT COALESCE(MAX(p.last_service_date), 0) FROM pack AS p WHERE EXISTS (SELECT 1 FROM trip AS t WHERE t.pack_id = p.id)"
    ));
    return last > 0 && last < encodedDate(localDay(at, timeZone));
  }

  /** True when every pack holding both stations has its last service day before the requested day. */
  private packsEnded(at: number, originIds: number[], destinationIds: number[], timeZone: string): boolean {
    const row = this.all(
      `SELECT COALESCE(MAX(p.last_service_date), 0) AS last FROM pack AS p
       WHERE p.id IN (SELECT pack_id FROM stop WHERE id IN (${placeholders(originIds.length)}))
         AND p.id IN (SELECT pack_id FROM stop WHERE id IN (${placeholders(destinationIds.length)}))`,
      ...originIds,
      ...destinationIds
    )[0];
    const last = integer(row?.last);
    return last > 0 && last < encodedDate(localDay(at, timeZone));
  }

  /** True when a stop of `stopIds` belongs to a national pack: otherwise the station is a border one. */
  private nationalPackServes(stopIds: number[]): boolean {
    if (!stopIds.length) return false;
    const rows = this.all(
      `SELECT DISTINCT p.source_id FROM stop AS s JOIN pack AS p ON p.id = s.pack_id WHERE s.id IN (${placeholders(stopIds.length)})`,
      ...stopIds
    );
    return rows.some(row => PackRoles.NATIONAL_PACKS.has(text(row.source_id) ?? ""));
  }

  /**
   * The next trains from `origins` to `destinations` leaving from `at` within 24 hours, at most
   * {@link MAXIMUM_RESULTS}, each train once: the iPhone planner's query on its bundled index.
   */
  nextTrains(origins: readonly StationRef[], destinations: readonly StationRef[], at: number): NextTrains {
    const referenceTimeZone = this.timeZones[0];
    // After the last day any pack runs, every search needs an update: say so for any station.
    if (this.indexEnded(at, referenceTimeZone)) return { kind: "scheduleExpired" };
    const originIds = this.resolveAll(origins);
    if (!originIds.length) return { kind: "noRoute" };
    const destinationIds = this.resolveAll(destinations);
    if (!destinationIds.length) return { kind: "noRoute" };
    if (originIds.some(id => destinationIds.includes(id))) return { kind: "noRoute" };
    const border = !this.nationalPackServes(originIds) || !this.nationalPackServes(destinationIds);

    const candidates: Candidate[] = [];
    for (const timeZone of this.timeZones) {
      const requestedDay = localDay(at, timeZone);
      for (let offset = -MAXIMUM_SERVICE_DAY_LOOKBACK; offset <= 1; offset++)
        candidates.push(...this.candidates(originIds, destinationIds, timeZone, requestedDay + offset));
    }
    const upperBound = at + DAY_MS;
    const selected = firstResults(
      candidates.filter(candidate => candidate.departure >= at && candidate.departure <= upperBound),
      border
    );
    if (!selected.length)
      return this.packsEnded(at, originIds, destinationIds, referenceTimeZone) ? { kind: "scheduleExpired" } : { kind: "noRoute" };
    return {
      kind: "trains",
      services: selected.map(candidate => candidate.service),
      attributions: [...new Set(selected.map(candidate => candidate.service.attribution))],
    };
  }

  private candidates(originIds: number[], destinationIds: number[], timeZone: string, serviceDay: number): Candidate[] {
    const rows = this.all(
      `SELECT t.id AS trip, t.external_id AS external_id, t.short_name AS trip_short_name, t.headsign AS headsign,
              r.short_name AS route_short_name, r.long_name AS long_name, r.route_type AS route_type,
              a.name AS agency, a.timezone AS timezone, destination_stop.name AS destination_name,
              COALESCE(origin_time.departure_seconds, origin_time.arrival_seconds) AS seconds,
              p.source_id AS source_id, p.attribution AS attribution,
              svc.start_date AS start_date, svc.day_count AS day_count, svc.active_days AS active_days, t.bikes_allowed AS bikes,
              origin_stop.name AS origin_name, COALESCE(destination_time.arrival_seconds, destination_time.departure_seconds) AS arrival_seconds
       FROM stop_time AS origin_time
       JOIN stop_time AS destination_time
         ON destination_time.trip_id = origin_time.trip_id
        AND destination_time.stop_sequence > origin_time.stop_sequence
       JOIN stop AS origin_stop ON origin_stop.id = origin_time.stop_id
       JOIN stop AS destination_stop ON destination_stop.id = destination_time.stop_id
       JOIN trip AS t ON t.id = origin_time.trip_id
       JOIN route AS r ON r.id = t.route_id
       JOIN agency AS a ON a.id = r.agency_id
       JOIN pack AS p ON p.id = t.pack_id
       JOIN service AS svc ON svc.id = t.service_id
       WHERE origin_time.stop_id IN (${placeholders(originIds.length)})
         AND destination_time.stop_id IN (${placeholders(destinationIds.length)})
         AND origin_time.pickup_type = 0
         AND destination_time.drop_off_type = 0
         AND a.timezone = ?
         AND COALESCE(origin_time.departure_seconds, origin_time.arrival_seconds) BETWEEN 0 AND ?
       ORDER BY COALESCE(origin_time.departure_seconds, origin_time.arrival_seconds), t.id,
                destination_time.stop_sequence
       LIMIT ?`,
      ...originIds,
      ...destinationIds,
      timeZone,
      MAXIMUM_GTFS_SECONDS,
      MAXIMUM_CANDIDATE_ROWS + 1
    );
    if (rows.length > MAXIMUM_CANDIDATE_ROWS) throw new TimetableIndexError("limitExceeded");
    const candidates: Candidate[] = [];
    for (const row of rows) {
      const externalTripId = nonEmpty(text(row.external_id));
      const agencyName = nonEmpty(text(row.agency));
      const destinationName = nonEmpty(text(row.destination_name));
      const sourceId = nonEmpty(text(row.source_id));
      const attribution = nonEmpty(normalizedWhitespace(text(row.attribution) ?? ""));
      const activeDays = row.active_days instanceof Uint8Array ? row.active_days : null;
      if (!externalTripId || !agencyName || text(row.timezone) !== timeZone || !destinationName || !sourceId || !attribution || !activeDays)
        throw new TimetableIndexError("invalidData");
      const seconds = integer(row.seconds);
      if (seconds < 0 || seconds > MAXIMUM_GTFS_SECONDS || !serviceRuns(serviceDay, integer(row.start_date), integer(row.day_count), activeDays))
        continue;
      const departure = gtfsInstant(serviceDay, seconds, timeZone);
      const tripShortName = nonEmpty(text(row.trip_short_name));
      const routeShortName = text(row.route_short_name);
      const routeType = integer(row.route_type);
      const number = trainNumber(tripShortName, routeShortName, externalTripId);
      const operator = bounded(operatorName(agencyName, sourceId), 64);
      const category = trainCategory(routeShortName, text(row.long_name), routeType);
      const destination = bounded(destinationName, 96);
      const mode = legMode(routeType, sourceId);
      if (!number || !operator || !category || !destination || mode === null) throw new TimetableIndexError("invalidData");
      const vehicle = vehicleOf(mode, operator, routeShortName === null ? null : lineLabel(routeShortName));
      // The arrival at the searched destination: two packs' copies of one train leave and arrive together.
      const arrivalSeconds = integer(row.arrival_seconds);
      const arrival = arrivalSeconds >= seconds && arrivalSeconds <= MAXIMUM_GTFS_SECONDS ? gtfsInstant(serviceDay, arrivalSeconds, timeZone) : null;
      const id = `${sourceId}:${externalTripId}:${encodedDate(serviceDay)}:${seconds}`;
      candidates.push({
        id,
        number,
        departure,
        run: { sourceId, number, operatorName: operator, departure, arrival, originName: text(row.origin_name) ?? "", destinationName: destination },
        service: {
          id,
          number,
          hasTrainNumber: tripShortName !== null,
          operatorName: operator,
          category,
          destination,
          departure,
          time: timeString(departure, timeZone),
          timeZone,
          vehicle,
          attribution,
        },
      });
    }
    return candidates;
  }

  /**
   * The runs of a train number on the day of `at` (Italian time where the agency's zone is Rome):
   * Android's programmed number search (`servicesByNumber`) on the index. Each trip whose number
   * the query matches, running on that day or on one of the two before and still running on it,
   * from its first to its last stop; a train two packs publish is listed once, from the preferred
   * pack. At most {@link MAXIMUM_TRAIN_RUNS}, in departure order.
   */
  trainRuns(published: string, at: number): TrainRun[] {
    const query = new TrainNumberQuery(published);
    if (!query.valid) return [];
    // Every number the query matches ends with its last digits: only those trips are read.
    const lastDigits = query.normalized.match(/[0-9]+/g)?.at(-1) ?? "";
    const pattern = `%${lastDigits.replace(/^0+/, "") || lastDigits}%`;
    const runs: Array<TrainRun & { sourceId: string }> = [];
    for (const timeZone of this.timeZones) {
      const today = localDay(at, timeZone);
      const rows = this.all(
        `SELECT t.id AS trip, t.external_id AS external_id, t.short_name AS trip_short_name, t.headsign AS headsign,
                r.short_name AS route_short_name, r.long_name AS long_name, r.route_type AS route_type,
                a.name AS agency, p.source_id AS source_id, p.attribution AS attribution,
                svc.start_date AS start_date, svc.day_count AS day_count, svc.active_days AS active_days,
                first_stop.name AS origin_name, first_stop.catalog_station_id AS origin_station, first_time.departure_seconds AS departure_seconds,
                last_stop.name AS destination_name, last_stop.catalog_station_id AS destination_station, last_time.departure_seconds AS arrival_seconds
         FROM trip AS t
         JOIN route AS r ON r.id = t.route_id
         JOIN agency AS a ON a.id = r.agency_id
         JOIN pack AS p ON p.id = t.pack_id
         JOIN service AS svc ON svc.id = t.service_id
         JOIN stop_time AS first_time ON first_time.trip_id = t.id AND first_time.stop_sequence =
              (SELECT MIN(stop_sequence) FROM stop_time WHERE trip_id = t.id AND departure_seconds IS NOT NULL)
         JOIN stop_time AS last_time ON last_time.trip_id = t.id AND last_time.stop_sequence =
              (SELECT MAX(stop_sequence) FROM stop_time WHERE trip_id = t.id AND departure_seconds IS NOT NULL)
         JOIN stop AS first_stop ON first_stop.id = first_time.stop_id
         JOIN stop AS last_stop ON last_stop.id = last_time.stop_id
         WHERE a.timezone = ?
           AND (t.short_name LIKE ? OR t.headsign LIKE ? OR t.external_id LIKE ?)
         ORDER BY t.id
         LIMIT ?`,
        timeZone,
        pattern,
        pattern,
        pattern,
        MAXIMUM_CANDIDATE_ROWS + 1
      );
      if (rows.length > MAXIMUM_CANDIDATE_ROWS) throw new TimetableIndexError("limitExceeded");
      for (const row of rows) {
        const externalTripId = nonEmpty(text(row.external_id));
        const sourceId = nonEmpty(text(row.source_id));
        const activeDays = row.active_days instanceof Uint8Array ? row.active_days : null;
        if (!externalTripId || !sourceId || !activeDays) throw new TimetableIndexError("invalidData");
        const tripShortName = text(row.trip_short_name);
        const headsign = text(row.headsign)?.trim() ?? "";
        const searchNumber = tripShortName?.trim() ? tripShortName : /^[0-9]+$/.test(headsign) ? headsign : externalTripId;
        if (!query.matches(searchNumber)) continue;
        const routeType = integer(row.route_type);
        if (legMode(routeType, sourceId) !== "TRAIN") continue;
        const departureSeconds = integer(row.departure_seconds);
        const arrivalSeconds = integer(row.arrival_seconds);
        if (departureSeconds < 0 || arrivalSeconds <= departureSeconds || arrivalSeconds > MAXIMUM_GTFS_SECONDS) continue;
        for (const serviceDay of [today - 2, today - 1, today]) {
          if (!serviceRuns(serviceDay, integer(row.start_date), integer(row.day_count), activeDays)) continue;
          const departure = gtfsInstant(serviceDay, departureSeconds, timeZone);
          const arrival = gtfsInstant(serviceDay, arrivalSeconds, timeZone);
          // A run of an earlier service day counts while it is still on its way today.
          if (localDay(departure, timeZone) > today || localDay(arrival, timeZone) < today) continue;
          const routeShortName = text(row.route_short_name);
          runs.push({
            sourceId,
            number: trainNumber(nonEmpty(tripShortName), routeShortName, externalTripId),
            category: trainCategory(routeShortName, text(row.long_name), routeType),
            operatorName: bounded(operatorName(text(row.agency) ?? "", sourceId), 64),
            originName: bounded(text(row.origin_name) ?? "", 96),
            destinationName: bounded(text(row.destination_name) ?? "", 96),
            originStationId: nonEmpty(text(row.origin_station)),
            destinationStationId: nonEmpty(text(row.destination_station)),
            departure,
            arrival,
            departureTime: timeString(departure, timeZone),
            arrivalTime: timeString(arrival, timeZone),
            timeZone,
            attribution: normalizedWhitespace(text(row.attribution) ?? ""),
          });
        }
      }
    }
    // One train two packs publish: the same number leaving at the same minute, kept from the preferred pack.
    runs.sort((one, other) =>
      one.departure - other.departure || packRank(one.sourceId, false) - packRank(other.sourceId, false) || (one.number < other.number ? -1 : one.number > other.number ? 1 : 0)
    );
    const seen = new Set<string>();
    const kept: TrainRun[] = [];
    for (const { sourceId, ...run } of runs) {
      const key = `${coreNumber(run.number) ?? run.number}|${Math.floor(run.departure / 60_000)}`;
      if (seen.has(key) || !sourceId) continue;
      seen.add(key);
      kept.push(run);
      if (kept.length >= MAXIMUM_TRAIN_RUNS) break;
    }
    return kept;
  }
}

/** The first {@link MAXIMUM_RESULTS} trains in departure order, each once (`TimetableCopies.kept`). */
function firstResults(candidates: readonly Candidate[], border: boolean): Candidate[] {
  const kept = keptRuns(candidates.map(candidate => candidate.run), border).map(index => candidates[index]);
  kept.sort((first, second) => {
    if (first.departure !== second.departure) return first.departure - second.departure;
    if (first.number !== second.number) return first.number < second.number ? -1 : 1;
    return first.id < second.id ? -1 : first.id > second.id ? 1 : 0;
  });
  const result: Candidate[] = [];
  for (const candidate of kept) {
    if (result.length >= MAXIMUM_RESULTS) break;
    if (result.some(listed => listed.id === candidate.id)) continue;
    result.push(candidate);
  }
  return result;
}

/** True when the service runs on `serviceDay`: its day bit, counted from its start date. */
function serviceRuns(serviceDay: number, startDate: number, dayCount: number, activeDays: Uint8Array): boolean {
  if (dayCount <= 0 || dayCount > MAXIMUM_SERVICE_DAYS || activeDays.length !== Math.floor((dayCount + 7) / 8)) return false;
  const start = decodedDate(startDate);
  if (start === null) return false;
  const offset = serviceDay - start;
  if (offset < 0 || offset >= dayCount) return false;
  return (activeDays[Math.floor(offset / 8)] & (1 << (offset % 8))) !== 0;
}

/** The trip's published number, else its route's short name, else the trip's identifier. */
function trainNumber(tripShortName: string | null, routeShortName: string | null, externalTripId: string): string {
  return bounded(tripShortName || routeShortName || externalTripId, 24);
}

/** The route's short name as Android shows it («FR» for «0083_FR»), else its long name, else its GTFS route type. */
function trainCategory(routeShortName: string | null, longName: string | null, routeType: number): string {
  const line = routeShortName === null ? "" : lineLabel(routeShortName);
  return bounded(line || longName || `GTFS ${routeType}`, 64);
}

/** «yyyy-mm-dd» → epoch milliseconds, or null. */
function parseSourceDate(value: string): number | null {
  const parts = value.split("-");
  if (parts.length !== 3 || parts.some(part => !/^[+-]?[0-9]+$/.test(part))) return null;
  const [year, month, day] = parts.map(Number);
  return Date.UTC(year, month - 1, day);
}
