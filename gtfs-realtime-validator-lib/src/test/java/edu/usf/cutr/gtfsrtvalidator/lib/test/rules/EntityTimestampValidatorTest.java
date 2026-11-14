/*
 * Copyright (C) 2026 Evan Siroky.
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
import edu.usf.cutr.gtfsrtvalidator.lib.test.FeedMessageTest;
import edu.usf.cutr.gtfsrtvalidator.lib.test.util.TestUtils;
import edu.usf.cutr.gtfsrtvalidator.lib.validation.rules.EntityTimestampValidator;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static edu.usf.cutr.gtfsrtvalidator.lib.util.TimestampUtils.MIN_POSIX_TIME;
import static edu.usf.cutr.gtfsrtvalidator.lib.validation.ValidationRules.*;
import static org.junit.Assert.assertTrue;

/*
 * Tests the warnings in EntityTimestampValidator:
 *  * W014 - TripUpdate timestamp missing or stale
 *  * W015 - VehiclePosition timestamp missing or stale
 *  * W016 - Many TripUpdate timestamps missing or stale
 *  * W017 - Many VehiclePosition timestamps missing or stale
 */
public class EntityTimestampValidatorTest extends FeedMessageTest {

    private final static long NOW_SECONDS = MIN_POSIX_TIME + 1000;
    private final static long NOW_MILLIS = TimeUnit.SECONDS.toMillis(NOW_SECONDS);

    public EntityTimestampValidatorTest() throws IOException {
    }

    private GtfsRealtime.FeedEntity tripUpdate(String id, long timestamp) {
        GtfsRealtime.TripUpdate.Builder b = GtfsRealtime.TripUpdate.newBuilder()
                .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId(id));
        if (timestamp != 0) {
            b.setTimestamp(timestamp);
        }
        return GtfsRealtime.FeedEntity.newBuilder().setId(id).setTripUpdate(b).build();
    }

    private GtfsRealtime.FeedEntity vehicle(String id, long timestamp) {
        GtfsRealtime.VehiclePosition.Builder b = GtfsRealtime.VehiclePosition.newBuilder()
                .setVehicle(GtfsRealtime.VehicleDescriptor.newBuilder().setId(id));
        if (timestamp != 0) {
            b.setTimestamp(timestamp);
        }
        return GtfsRealtime.FeedEntity.newBuilder().setId(id).setVehicle(b).build();
    }

    private GtfsRealtime.FeedMessage feed(GtfsRealtime.FeedEntity... entities) {
        GtfsRealtime.FeedMessage.Builder b = GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(NOW_SECONDS));
        for (GtfsRealtime.FeedEntity e : entities) {
            b.addEntity(e);
        }
        return b.build();
    }

    @Test
    public void testFreshTimestampsHaveNoWarnings() {
        EntityTimestampValidator validator = new EntityTimestampValidator();
        results = validator.validate(NOW_MILLIS, gtfsData, gtfsDataMetadata,
                feed(tripUpdate("1.1", NOW_SECONDS - 90), vehicle("v1", NOW_SECONDS - 5), vehicle("v2", NOW_SECONDS + 10)), null, null);
        TestUtils.assertResults(new HashMap<>(), results);
        assertTrue(results.isEmpty());
    }

    @Test
    public void testTripUpdates() {
        EntityTimestampValidator validator = new EntityTimestampValidator();
        Map<ValidationRule, Integer> expected = new HashMap<>();

        // 1 of 3 missing/stale - below the feed-wide threshold, so only W014
        results = validator.validate(NOW_MILLIS, gtfsData, gtfsDataMetadata,
                feed(tripUpdate("1.1", NOW_SECONDS), tripUpdate("1.2", NOW_SECONDS - 10), tripUpdate("1.3", NOW_SECONDS - 91)), null, null);
        expected.put(W014, 1);
        TestUtils.assertResults(expected, results);
        assertTrue(results.size() == 1);

        // 1 missing and 1 stale out of 3 - W014 for both, and W016 for the feed
        results = validator.validate(NOW_MILLIS, gtfsData, gtfsDataMetadata,
                feed(tripUpdate("1.1", NOW_SECONDS), tripUpdate("1.2", 0), tripUpdate("1.3", NOW_SECONDS - 600)), null, null);
        expected.put(W014, 2);
        expected.put(W016, 1);
        TestUtils.assertResults(expected, results);
        assertTrue(results.size() == 2);
    }

    @Test
    public void testVehiclePositions() {
        EntityTimestampValidator validator = new EntityTimestampValidator();
        Map<ValidationRule, Integer> expected = new HashMap<>();

        results = validator.validate(NOW_MILLIS, gtfsData, gtfsDataMetadata,
                feed(vehicle("v1", NOW_SECONDS), vehicle("v2", NOW_SECONDS - 10), vehicle("v3", 0)), null, null);
        expected.put(W015, 1);
        TestUtils.assertResults(expected, results);
        assertTrue(results.size() == 1);

        // 2 of 2 stale - W015 for each, and W017 for the feed
        results = validator.validate(NOW_MILLIS, gtfsData, gtfsDataMetadata,
                feed(vehicle("v1", NOW_SECONDS - 200), vehicle("v2", NOW_SECONDS - 300)), null, null);
        expected.put(W015, 2);
        expected.put(W017, 1);
        TestUtils.assertResults(expected, results);
        assertTrue(results.size() == 2);
    }
}
