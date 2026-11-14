/*
 * Copyright (C) 2026 University of South Florida.
 * All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.usf.cutr.gtfsrtvalidator.lib.test.rules;

import com.google.transit.realtime.GtfsRealtime;
import edu.usf.cutr.gtfsrtvalidator.lib.model.ValidationRule;
import edu.usf.cutr.gtfsrtvalidator.lib.model.helper.ErrorListHelperModel;
import edu.usf.cutr.gtfsrtvalidator.lib.test.FeedMessageTest;
import edu.usf.cutr.gtfsrtvalidator.lib.test.util.TestUtils;
import edu.usf.cutr.gtfsrtvalidator.lib.validation.rules.ScheduleCoverageValidator;
import org.junit.Test;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

import static edu.usf.cutr.gtfsrtvalidator.lib.validation.ValidationRules.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/*
 * Tests the warnings in ScheduleCoverageValidator, using testagency.zip (America/New_York).  At 10:05 on Wed 2026-03-04
 * the only non-frequency trips in service are 14.2 (09:50-10:50) and 9.1 (10:00:10-11:00).
 *  * W010 - Scheduled in-service trip_id not in TripUpdates feed
 *  * W011 - Most scheduled in-service trips not in TripUpdates feed
 *  * W012 - Most scheduled in-service trips not in VehiclePositions feed
 *  * W013 - Scheduled in-service trip_id not in VehiclePositions feed
 */
public class ScheduleCoverageValidatorTest extends FeedMessageTest {

    private final static long NOW_MILLIS = ZonedDateTime.of(2026, 3, 4, 10, 5, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli();
    private final static long NOW_SECONDS = NOW_MILLIS / 1000;

    public ScheduleCoverageValidatorTest() throws IOException {
    }

    private GtfsRealtime.FeedEntity tripUpdate(String tripId, GtfsRealtime.TripDescriptor.ScheduleRelationship relationship) {
        return GtfsRealtime.FeedEntity.newBuilder().setId("tu" + tripId).setTripUpdate(GtfsRealtime.TripUpdate.newBuilder()
                .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId(tripId).setScheduleRelationship(relationship))).build();
    }

    private GtfsRealtime.FeedEntity vehicle(String tripId) {
        return GtfsRealtime.FeedEntity.newBuilder().setId("vp" + tripId).setVehicle(GtfsRealtime.VehiclePosition.newBuilder()
                .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId(tripId))
                .setVehicle(GtfsRealtime.VehicleDescriptor.newBuilder().setId("bus" + tripId))).build();
    }

    private GtfsRealtime.FeedMessage feed(GtfsRealtime.FeedEntity... entities) {
        GtfsRealtime.FeedMessage.Builder b = GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(NOW_SECONDS));
        for (GtfsRealtime.FeedEntity e : entities) {
            b.addEntity(e);
        }
        return b.build();
    }

    private static final GtfsRealtime.TripDescriptor.ScheduleRelationship SCHEDULED = GtfsRealtime.TripDescriptor.ScheduleRelationship.SCHEDULED;
    private static final GtfsRealtime.TripDescriptor.ScheduleRelationship CANCELED = GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED;

    @Test
    public void testTripUpdates() {
        ScheduleCoverageValidator validator = new ScheduleCoverageValidator();
        Map<ValidationRule, Integer> expected = new HashMap<>();

        // Both in-service trips are covered (canceled trips still count as accounted for) - no warnings
        results = validator.validate(NOW_MILLIS, gtfsData, gtfsDataMetadata, feed(tripUpdate("14.2", SCHEDULED), tripUpdate("9.1", CANCELED)), null, null);
        assertTrue(results.isEmpty());

        // One of two covered (50%) - W010 for the missing trip and W011 for the feed
        results = validator.validate(NOW_MILLIS, gtfsData, gtfsDataMetadata, feed(tripUpdate("14.2", SCHEDULED), tripUpdate("1.1", SCHEDULED)), null, null);
        expected.put(W010, 1);
        expected.put(W011, 1);
        TestUtils.assertResults(expected, results);
        assertEquals(2, results.size());
        for (ErrorListHelperModel m : results) {
            if (m.getErrorMessage().getValidationRule().equals(W010)) {
                assertEquals("trip_id 9.1", m.getOccurrenceList().get(0).getPrefix());
            }
        }
    }

    @Test
    public void testVehiclePositions() {
        ScheduleCoverageValidator validator = new ScheduleCoverageValidator();
        Map<ValidationRule, Integer> expected = new HashMap<>();

        // No vehicle for either trip - W013 for each trip, W012 for the feed
        results = validator.validate(NOW_MILLIS, gtfsData, gtfsDataMetadata, feed(vehicle("1.1")), null, null);
        expected.put(W013, 2);
        expected.put(W012, 1);
        TestUtils.assertResults(expected, results);
        assertEquals(2, results.size());

        // Trip 9.1 canceled in the combined feed, so it isn't expected to have a vehicle - and 14.2 is covered
        GtfsRealtime.FeedMessage vehicles = feed(vehicle("14.2"));
        GtfsRealtime.FeedMessage combined = feed(vehicle("14.2"), tripUpdate("9.1", CANCELED));
        results = validator.validate(NOW_MILLIS, gtfsData, gtfsDataMetadata, vehicles, null, combined);
        assertTrue(results.isEmpty());

        // Without the cancellation, 9.1 is missing
        results = validator.validate(NOW_MILLIS, gtfsData, gtfsDataMetadata, vehicles, null, null);
        expected.put(W013, 1);
        TestUtils.assertResults(expected, results);
    }

    @Test
    public void testNoTripsInService() {
        // 03:00 - nothing is in service, and the frequency-based trips are ignored
        long threeAm = ZonedDateTime.of(2026, 3, 4, 3, 0, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli();
        results = new ScheduleCoverageValidator().validate(threeAm, gtfsData, gtfsDataMetadata, feed(tripUpdate("1.1", SCHEDULED)), null, null);
        assertTrue(results.isEmpty());
    }
}
