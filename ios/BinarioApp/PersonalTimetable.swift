import Foundation

/// The personal timetable index of the Personal configuration (`BINARIO_PERSONAL_SOURCES`, as
/// Android's personal flavour adds gtfs-personal/): the public packs and the city networks of Roma,
/// Torino, Venezia and Napoli, whose licences allow personal use only (docs/data-sources/
/// SOURCE_POLICY.md). Built by `tools/build_ios_gtfs_schedule.py --personal` into ios/Personal and
/// copied into the app by the Personal configuration only (project.yml); Debug and Release never
/// carry it and read the public index.
enum PersonalTimetable {
    struct Resources {
        let compressed: URL
        let metadata: URL
        let sourcePolicy: URL
    }

    /// The personal index's three files in `bundle`; nil outside the Personal configuration.
    static func resources(in bundle: Bundle) -> Resources? {
        #if BINARIO_PERSONAL_SOURCES
        guard
            let compressed = bundle.url(forResource: "binario_gtfs_schedule_personal_v1.sqlite", withExtension: "zlib"),
            let metadata = bundle.url(forResource: "binario_gtfs_schedule_personal_v1.metadata", withExtension: "json"),
            let sourcePolicy = bundle.url(forResource: "binario_gtfs_source_policy_personal_v1", withExtension: "json")
        else { return nil }
        return Resources(compressed: compressed, metadata: metadata, sourcePolicy: sourcePolicy)
        #else
        return nil
        #endif
    }
}
