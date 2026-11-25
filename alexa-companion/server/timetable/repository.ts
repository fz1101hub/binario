import { DatabaseSync } from "node:sqlite";
import { PackRoles } from "@shared/generated/sharedData";
import { keptRuns, packRank, coreNumber, type TimetableRun } from "./copies";
import {
  bounded,
  isUrban,
  legMode,
  lineLabel,
  normalizedWhitespace,
  operatorName,
  urbanLine,
  vehicleOf,
  type TimetableLegMode,
  type TimetableVehicle,
} from "./naming";
import { TrainNumberQuery } from "./trainNumbers";
import { decodedDate, encodedDate, gtfsInstant, isTimeZone, localDay, timeString } from "./wallClock";

/**
 * Read-only queries of the programmed timetable index the iPhone bundles
 * (ios/Resources/binario_gtfs_schedule_v1.sqlite.zlib, built by tools/build_ios_gtfs_schedule.py
 * from Android's packs), so the skill reads the same trains as the apps. The checks and the
 * next-trains query are those of the iPhone's `SQLiteGTFSJourneyRepository`:
 * contracts/shared-rules/v1/next-trains-v1.json holds the cases both read. A database that is not
 * the expected index fails closed.
 *
 * A station a short walk from a stop of a city network (`urban_walk`: at most 600 m in a straight
 * line, at 4.5 km/h rounded up to the minute plus 2 minutes, contracts/shared-data/v1/urban-stops-v1.json)
 * is served by that line too, as the iPhone's planner reads it: Bologna Centrale and Bologna
 * Aeroporto by the Marconi Express. A run boarded there is listed with the stop and the minutes of
 * the walk, and it leaves no earlier than the request's time plus the walk.
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
 * The city networks' tables (metro, tram, funiculars), present together or not at all: each city stop's modes, and
 * the catalogue stations a short walk from each, with the minutes of the walk (tools/build_ios_gtfs_schedule.py).
 */
const URBAN_TABLES: Readonly<Record<string, readonly string[]>> = {
  urban_stop: ["stop_id", "modes"],
  urban_walk: ["catalog_station_id", "stop_id", "minutes"],
};
/**
 * The details the iPhone's planner of journeys with changes reads (blocks, wheelchair values, transfer points), present
 * together or not at all: the skill does not read them, so it only accepts them.
 */
const DETAIL_TABLES: Readonly<Record<string, readonly string[]>> = {
  trip_detail: ["trip_id", "block_id", "wheelchair_accessible"],
  stop_detail: ["stop_id", "place", "wheelchair_boarding"],
};
const EXPECTED_INDICES = new Set([
  "idx_route_pack_external", "idx_service_pack_external", "idx_trip_pack_external",
  "idx_trip_route_service", "idx_stop_pack_external", "idx_stop_catalog_station",
  "idx_stop_time_stop_departure",
]);
const MAXIMUM_RESOLVED_STOPS = 128;
/** City stops a walk joins to one catalogue station, at most (tools/verify_ios_gtfs_schedule.py). */
const MAXIMUM_WALKED_STOPS = 128;
/** The longest walk the index may hold: a longer one is not the expected index. */
const MAXIMUM_WALK_MINUTES = 60;
/** Services of the city networks read to leave out those that do not run on a day. */
const MAXIMUM_URBAN_SERVICES = 4_096;
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
  /** Minutes of walking from the searched station to the city stop boarded; 0 at the station. */
  walkMinutes: number;
  /** The city stop boarded when it is a walk from the searched station, else null. */
  boardingStop: string | null;
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
  /** The run of a service day (pack, trip, day): a city run boarded at several stops is one. */
  runKey: string;
};

/** Stops a search starts or ends at, with the minutes of walking to each: 0 for a station's own stops. */
type Endpoint = Map<number, number>;

const text = (value: unknown): string | null => (typeof value === "string" ? value : null);
const nonEmpty = (value: string | null): string | null => (value ? value : null);
/** SQLite's integer accessor: NULL and non-integers read as 0, as `sqlite3_column_int64` does. */
const integer = (value: unknown): number => (typeof value === "number" ? Math.trunc(value) : typeof value === "bigint" ? Number(value) : 0);
const placeholders = (count: number) => Array.from({ length: count }, () => "?").join(",");

export class TimetableIndex {
  private readonly database: DatabaseSync;
  private readonly timeZones: readonly string[];
  /** True for an index with city networks (`urban_stop`, `urban_walk`). */
  private urban = false;
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
    this.urban = tables.has("urban_stop");
    const schema = {
      ...EXPECTED_TABLES,
      ...(Object.keys(URBAN_TABLES).some(name => tables.has(name)) ? URBAN_TABLES : {}),
      ...(Object.keys(DETAIL_TABLES).some(name => tables.has(name)) ? DETAIL_TABLES : {}),
    };
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

  /**
   * The stops a search may start or end at for the stations a name stands for (an alias can name
   * several), with the minutes of walking to each, as the iPhone's planner reaches them
   * (`endpointStops`): 0 for a station's own stops, the walk for the city stops a short walk from
   * it (`urban_walk`), the shortest when two stations reach one stop.
   */
  private endpointStops(stations: readonly StationRef[]): Endpoint {
    const stops: Endpoint = new Map();
    for (const station of stations) for (const id of this.resolve(station)) stops.set(id, 0);
    for (const station of stations)
      for (const [id, minutes] of this.walkedStops(station.id)) {
        const known = stops.get(id);
        if (known === undefined || minutes < known) stops.set(id, minutes);
      }
    return stops;
  }

  /** The city stops a short walk from the catalogue station `catalogStationId`, with the minutes of the walk. */
  private walkedStops(catalogStationId: string): Endpoint {
    const stops: Endpoint = new Map();
    if (!this.urban) return stops;
    const rows = this.all(
      `SELECT w.stop_id AS stop, w.minutes AS minutes FROM urban_walk AS w
       JOIN stop AS s ON s.id = w.stop_id
       WHERE w.catalog_station_id = ?
       ORDER BY w.stop_id
       LIMIT ?`,
      catalogStationId,
      MAXIMUM_WALKED_STOPS + 1
    );
    if (rows.length > MAXIMUM_WALKED_STOPS) throw new TimetableIndexError("limitExceeded");
    for (const row of rows) {
      const minutes = integer(row.minutes);
      if (minutes < 0 || minutes > MAXIMUM_WALK_MINUTES) throw new TimetableIndexError("invalidData");
      stops.set(integer(row.stop), minutes);
    }
    return stops;
  }

  /**
   * The services of the city networks that do not run on `serviceDay`: their trips are left out in
   * SQL, so that the many timetable variants of a city line never fill the rows read (the bound
   * tools/verify_ios_gtfs_schedule.py checks for the iPhone). Empty for an index without city networks.
   */
  private idleUrbanServices(serviceDay: number): number[] {
    if (!this.urban) return [];
    const rows = this.all(
      `SELECT sv.id AS id, sv.start_date AS start_date, sv.day_count AS day_count, sv.active_days AS active_days FROM service AS sv
       WHERE sv.pack_id IN (SELECT s.pack_id FROM urban_stop AS u JOIN stop AS s ON s.id = u.stop_id)
       ORDER BY sv.id
       LIMIT ?`,
      MAXIMUM_URBAN_SERVICES + 1
    );
    if (rows.length > MAXIMUM_URBAN_SERVICES) throw new TimetableIndexError("limitExceeded");
    const idle: number[] = [];
    for (const row of rows) {
      const activeDays = row.active_days instanceof Uint8Array ? row.active_days : null;
      if (!activeDays) throw new TimetableIndexError("invalidData");
      if (!serviceRuns(serviceDay, integer(row.start_date), integer(row.day_count), activeDays)) idle.push(integer(row.id));
    }
    return idle;
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
   * {@link MAXIMUM_RESULTS}, each train once: the iPhone planner's query on its bundled index. A
   * city run boarded a walk from the origin leaves no earlier than `at` plus the walk, and is
   * listed once, where the walk is shortest.
   */
  nextTrains(origins: readonly StationRef[], destinations: readonly StationRef[], at: number): NextTrains {
    const referenceTimeZone = this.timeZones[0];
    // After the last day any pack runs, every search needs an update: say so for any station.
    if (this.indexEnded(at, referenceTimeZone)) return { kind: "scheduleExpired" };
    const origin = this.endpointStops(origins);
    if (!origin.size) return { kind: "noRoute" };
    const destination = this.endpointStops(destinations);
    if (!destination.size) return { kind: "noRoute" };
    const own = (stops: Endpoint) => [...stops].filter(([, minutes]) => minutes === 0).map(([id]) => id);
    const destinationOwn = new Set(own(destination));
    if (own(origin).some(id => destinationOwn.has(id))) return { kind: "noRoute" };
    // A city stop a short walk from both ends is no journey: the ends are a walk apart there.
    for (const id of [...origin.keys()])
      if (destination.has(id)) {
        origin.delete(id);
        destination.delete(id);
      }
    if (!origin.size || !destination.size) return { kind: "noRoute" };
    const originIds = [...origin.keys()].sort((a, b) => a - b);
    const destinationIds = [...destination.keys()].sort((a, b) => a - b);
    // A city stop at an end: a run is read once per boarding, and the city services idle that day are skipped in SQL.
    const cityStops = this.urban && [...origin.values(), ...destination.values()].some(minutes => minutes > 0);
    const border = !this.nationalPackServes(originIds) || !this.nationalPackServes(destinationIds);

    const upperBound = at + DAY_MS;
    const candidates: Candidate[] = [];
    for (const timeZone of this.timeZones) {
      const requestedDay = localDay(at, timeZone);
      for (let offset = -MAXIMUM_SERVICE_DAY_LOOKBACK; offset <= 1; offset++) {
        const serviceDay = requestedDay + offset;
        // Only the runs of the window: an earlier service day reaches it only after 24:00.
        const base = gtfsInstant(serviceDay, 0, timeZone);
        const earliest = Math.ceil((at - base) / 1000);
        const latest = Math.floor((upperBound - base) / 1000);
        if (latest < 0 || earliest > MAXIMUM_GTFS_SECONDS) continue;
        candidates.push(
          ...this.candidates(origin, destinationIds, timeZone, serviceDay, {
            from: Math.max(0, earliest),
            to: Math.min(MAXIMUM_GTFS_SECONDS, latest),
            cityStops,
            idleServices: cityStops ? this.idleUrbanServices(serviceDay) : [],
          })
        );
      }
    }
    // A city stop a walk away is reached that many minutes after the request's time.
    const selected = firstResults(
      onePerCityRun(
        candidates.filter(candidate => candidate.departure >= at + candidate.service.walkMinutes * 60_000 && candidate.departure <= upperBound)
      ),
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

  /**
   * The runs of `serviceDay` leaving a stop of `origin` (with the minutes of walking to it) at a GTFS
   * second of the window, to a later stop of `destinationIds`. With `cityStops` (a city stop at an
   * end; `idleServices` the city services that do not run that day) a run is read once per
   * boarding, to the first such stop: a city line passes several stops near a station.
   */
  private candidates(
    origin: Endpoint,
    destinationIds: number[],
    timeZone: string,
    serviceDay: number,
    window: { from: number; to: number; cityStops: boolean; idleServices: readonly number[] }
  ): Candidate[] {
    const originIds = [...origin.keys()].sort((a, b) => a - b);
    const firstStop = !window.cityStops
      ? ""
      : `
         AND NOT EXISTS (
             SELECT 1 FROM stop_time AS earlier
             WHERE earlier.trip_id = origin_time.trip_id
               AND earlier.stop_sequence > origin_time.stop_sequence
               AND earlier.stop_sequence < destination_time.stop_sequence
               AND earlier.stop_id IN (${placeholders(destinationIds.length)})
               AND earlier.drop_off_type = 0)`;
    const idle = window.idleServices.length ? `
         AND t.service_id NOT IN (${placeholders(window.idleServices.length)})` : "";
    // The builder never lets a run go back in time, so a destination is never left before the
    // window opens (an untimed stop aside): the bound lets the index skip the earlier rows.
    const rows = this.all(
      `SELECT t.id AS trip, t.external_id AS external_id, t.short_name AS trip_short_name, t.headsign AS headsign,
              r.short_name AS route_short_name, r.long_name AS long_name, r.route_type AS route_type,
              a.name AS agency, a.timezone AS timezone, destination_stop.name AS destination_name,
              COALESCE(origin_time.departure_seconds, origin_time.arrival_seconds) AS seconds,
              p.source_id AS source_id, p.attribution AS attribution,
              svc.start_date AS start_date, svc.day_count AS day_count, svc.active_days AS active_days, t.bikes_allowed AS bikes,
              origin_stop.name AS origin_name, COALESCE(destination_time.arrival_seconds, destination_time.departure_seconds) AS arrival_seconds,
              origin_time.stop_id AS origin_stop_id
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
         AND (destination_time.departure_seconds IS NULL OR destination_time.departure_seconds >= ?)${firstStop}
         AND a.timezone = ?
         AND COALESCE(origin_time.departure_seconds, origin_time.arrival_seconds) BETWEEN ? AND ?${idle}
       ORDER BY COALESCE(origin_time.departure_seconds, origin_time.arrival_seconds), t.id,
                destination_time.stop_sequence
       LIMIT ?`,
      ...originIds,
      ...destinationIds,
      window.from,
      ...(window.cityStops ? destinationIds : []),
      timeZone,
      window.from,
      window.to,
      ...window.idleServices,
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
      const mode = legMode(routeType, sourceId);
      if (mode === null) throw new TimetableIndexError("invalidData");
      const naming = serviceNaming(mode, tripShortName, routeShortName, text(row.long_name), routeType, externalTripId);
      const { number, category } = naming;
      const operator = bounded(operatorName(agencyName, sourceId), 64);
      const destination = bounded(destinationName, 96);
      if (!number || !operator || !category || !destination) throw new TimetableIndexError("invalidData");
      const vehicle = vehicleOf(mode, operator, routeShortName === null ? null : lineLabel(routeShortName));
      // A city stop a short walk from the searched station: said with the run.
      const walkMinutes = origin.get(integer(row.origin_stop_id)) ?? 0;
      const originName = text(row.origin_name) ?? "";
      // The arrival at the searched destination: two packs' copies of one train leave and arrive together.
      const arrivalSeconds = integer(row.arrival_seconds);
      const arrival = arrivalSeconds >= seconds && arrivalSeconds <= MAXIMUM_GTFS_SECONDS ? gtfsInstant(serviceDay, arrivalSeconds, timeZone) : null;
      const id = `${sourceId}:${externalTripId}:${encodedDate(serviceDay)}:${seconds}`;
      candidates.push({
        id,
        number,
        departure,
        run: { sourceId, number, operatorName: operator, departure, arrival, originName, destinationName: destination, urban: isUrban(vehicle) },
        service: {
          id,
          number,
          hasTrainNumber: naming.hasTrainNumber,
          operatorName: operator,
          category,
          destination,
          walkMinutes,
          boardingStop: walkMinutes > 0 ? bounded(originName, 96) : null,
          departure,
          time: timeString(departure, timeZone),
          timeZone,
          vehicle,
          attribution,
        },
        runKey: `${sourceId}:${externalTripId}:${encodedDate(serviceDay)}`,
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

/**
 * A city run passing several stops a walk from the origin is listed once: boarded where the walk is
 * shortest, then where it leaves first (`onePerCityRun`, Android's `boardingConnection`). Trains
 * and buses are kept as they are.
 */
function onePerCityRun(candidates: readonly Candidate[]): Candidate[] {
  const best = new Map<string, number>();
  candidates.forEach((candidate, index) => {
    if (!isUrban(candidate.service.vehicle)) return;
    const current = best.get(candidate.runKey);
    if (current === undefined) {
      best.set(candidate.runKey, index);
      return;
    }
    const kept = candidates[current];
    const shorter = candidate.service.walkMinutes < kept.service.walkMinutes;
    const sooner = candidate.service.walkMinutes === kept.service.walkMinutes && candidate.departure < kept.departure;
    if (shorter || sooner) best.set(candidate.runKey, index);
  });
  const kept = new Set(best.values());
  return candidates.filter((candidate, index) => !isUrban(candidate.service.vehicle) || kept.has(index));
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

/**
 * The number and category a service is listed with: a train's (`trainNumber`, `trainCategory`), or,
 * for a city run, which has a line but no number (its trip id is not one), the line both times
 * («M1», «14», «MEX»: `urbanLine`), else the long name, else the mode's own word.
 */
function serviceNaming(
  mode: TimetableLegMode,
  tripShortName: string | null,
  routeShortName: string | null,
  longName: string | null,
  routeType: number,
  externalTripId: string
): { number: string; category: string; hasTrainNumber: boolean } {
  if (!isUrban(mode))
    return {
      number: trainNumber(tripShortName, routeShortName, externalTripId),
      category: trainCategory(routeShortName, longName, routeType),
      hasTrainNumber: tripShortName !== null,
    };
  const line = bounded(urbanLine(routeShortName, mode) ?? (longName || mode.toLowerCase()), 24);
  return { number: line, category: line, hasTrainNumber: false };
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
