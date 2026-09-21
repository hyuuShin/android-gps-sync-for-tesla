package dev.gpssync.for_tesla_probe

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FleetLocationClientTest {
    private val now = 1_800_000_000_000L
    private fun response(lat: Any = 37.5, lon: Any = 127.0, time: Any = now) = JSONObject().put("response", JSONObject().put("drive_state", JSONObject().put("latitude",lat).put("longitude",lon).put("timestamp",time))).toString()
    @Test fun parsesLocationAndAcceptsRealZeroCoordinates() {
        assertEquals(37.5, FleetLocationClient.parse(response(), now).latitude, 0.0)
        assertEquals(0.0, FleetLocationClient.parse(response(0,0), now).longitude, 0.0)
    }
    @Test fun rejectsMissingNullOutOfRangeAndStaleData() {
        for (body in listOf("{}", "{\"response\":{\"drive_state\":{}}}", response(JSONObject.NULL), response(91), response(lon=181), response(time=now-300_001), response(time=now+60_001), response(lat="37.5"))) {
            assertThrows(Exception::class.java) { FleetLocationClient.parse(body,now) }
        }
    }
    @Test fun rejectsApiErrorEvenWithCoordinates() {
        val body = JSONObject(response()).put("error","unavailable").toString()
        assertThrows(Exception::class.java) { FleetLocationClient.parse(body,now) }
    }
    @Test fun rejectsInvalidDestinationAndVinBeforeNetwork() {
        assertThrows(Exception::class.java) { FleetLocationClient.fetch("https://example.com","AAAAAAAAAAAAAAAAA","token") }
        assertThrows(Exception::class.java) { FleetLocationClient.fetch(FleetLocationClient.regions.values.first(),"../path","token") }
    }
}
