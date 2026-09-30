package so.drafft.core.data.backend

import so.drafft.core.model.Profile

// Ports Drafft/Services/Backend/Safety.swift.

/** Reports and blocks, on the server. Profiles from the demo data (not server ids) stay on the device. */
class Safety(private val backend: Backend) {
    /**
     * The report reaches the safety team (who may hold the account at once, see reports_events), and
     * blocks them: `report_user` does both. [reason] is the report reason's raw value.
     */
    suspend fun report(person: Profile, reason: String, details: String) {
        if (uuidOrNull(person.id) == null) return
        backend.rpc(
            "report_user",
            jsonOf("p_target" to person.id, "p_reason" to reason, "p_details" to details.trim().take(1000)),
        )
    }

    suspend fun block(id: String) {
        if (uuidOrNull(id) == null) return
        attempt { backend.rpc("block_user", jsonOf("p_target" to id)) }
    }

    suspend fun unblock(id: String) {
        if (uuidOrNull(id) == null) return
        attempt { backend.rpc("unblock_user", jsonOf("p_target" to id)) }
    }
}
