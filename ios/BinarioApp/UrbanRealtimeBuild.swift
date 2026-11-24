import Foundation
import BinarioKit

/// The city networks' Trip Updates feeds of this build (Roma Servizi per la Mobilità, ACTV), gated as Android's
/// `UrbanRealtimeFeeds.forBuild`: a feed exists only when its city pack is in the timetable and, for a personal-only
/// feed (both today, like their packs), only in a personal build (the `BINARIO_PERSONAL_SOURCES` compilation condition
/// of the app target; BinarioKit cannot see it, so the gate lives here).
enum UrbanRealtimeBuild {
    static var personalSources: Bool {
        #if BINARIO_PERSONAL_SOURCES
        return true
        #else
        return false
        #endif
    }

    /// The feeds of this build for the packs `packs` of the timetable.
    static func feeds(packs: Set<String>) -> [UrbanRealtimeFeeds.Feed] {
        UrbanRealtimeFeeds.forBuild(personalSources: personalSources, packs: packs)
    }

    /// One source per feed of this build, by city pack: each keeps its own 30-second cache and pause.
    static func sources(packs: Set<String>) -> [String: UrbanRealtimeFeedSource] {
        var sources: [String: UrbanRealtimeFeedSource] = [:]
        for feed in feeds(packs: packs) { sources[feed.pack] = UrbanRealtimeFeedSource(feed: feed) }
        return sources
    }
}
