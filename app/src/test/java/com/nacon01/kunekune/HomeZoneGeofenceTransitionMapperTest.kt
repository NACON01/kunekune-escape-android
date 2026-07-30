package com.nacon01.kunekune

import com.google.android.gms.location.Geofence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeZoneGeofenceTransitionMapperTest {
    @Test
    fun acceptsOnlyHomeEnterAndExit() {
        assertEquals(
            LocationObservation.INSIDE,
            HomeZoneGeofenceTransitionMapper.observationFor(
                HomeZoneGeofenceManager.HOME_REQUEST_ID,
                Geofence.GEOFENCE_TRANSITION_ENTER
            )
        )
        assertEquals(
            LocationObservation.OUTSIDE,
            HomeZoneGeofenceTransitionMapper.observationFor(
                HomeZoneGeofenceManager.HOME_REQUEST_ID,
                Geofence.GEOFENCE_TRANSITION_EXIT
            )
        )
        assertNull(HomeZoneGeofenceTransitionMapper.observationFor("other", Geofence.GEOFENCE_TRANSITION_ENTER))
        assertNull(HomeZoneGeofenceTransitionMapper.observationFor(HomeZoneGeofenceManager.HOME_REQUEST_ID, Geofence.GEOFENCE_TRANSITION_DWELL))
    }
}

