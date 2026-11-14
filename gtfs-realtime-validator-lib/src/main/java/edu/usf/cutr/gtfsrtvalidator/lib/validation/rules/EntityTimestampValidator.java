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
package edu.usf.cutr.gtfsrtvalidator.lib.validation.rules;

import com.google.transit.realtime.GtfsRealtime;
import edu.usf.cutr.gtfsrtvalidator.lib.model.MessageLogModel;
import edu.usf.cutr.gtfsrtvalidator.lib.model.OccurrenceModel;
import edu.usf.cutr.gtfsrtvalidator.lib.model.helper.ErrorListHelperModel;
import edu.usf.cutr.gtfsrtvalidator.lib.util.GtfsUtils;
import edu.usf.cutr.gtfsrtvalidator.lib.util.RuleUtils;
import edu.usf.cutr.gtfsrtvalidator.lib.validation.GtfsMetadata;
import edu.usf.cutr.gtfsrtvalidator.lib.validation.interfaces.FeedEntityValidator;
import org.onebusaway.gtfs.services.GtfsMutableDao;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static edu.usf.cutr.gtfsrtvalidator.lib.util.TimestampUtils.getAge;
import static edu.usf.cutr.gtfsrtvalidator.lib.validation.ValidationRules.*;

/**
 * Examines the timestamps of individual TripUpdate and VehiclePosition entities (the header timestamp is
 * examined by {@link TimestampValidator}).  All rules in this class are warnings.
 *
 * W014 - TripUpdate timestamp missing or stale
 * W015 - VehiclePosition timestamp missing or stale
 * W016 - Many TripUpdate timestamps missing or stale
 * W017 - Many VehiclePosition timestamps missing or stale
 */
public class EntityTimestampValidator implements FeedEntityValidator {

    private static final org.slf4j.Logger _log = LoggerFactory.getLogger(EntityTimestampValidator.class);

    /**
     * Maximum age of an entity timestamp, in seconds - from the GTFS-realtime best practices, "Data within a
     * GTFS Realtime feed should not be older than 90 seconds for Trip Updates and Vehicle Positions"
     */
    public final static long MAX_AGE_SECONDS = 90L;

    /**
     * Fraction (0.0 - 1.0) of entities with a missing or stale timestamp at or above which the feed-wide warnings
     * W016 and W017 are logged
     */
    public final static double FEED_WIDE_THRESHOLD = 0.5d;

    @Override
    public List<ErrorListHelperModel> validate(long currentTimeMillis, GtfsMutableDao gtfsData, GtfsMetadata gtfsMetadata, GtfsRealtime.FeedMessage feedMessage, GtfsRealtime.FeedMessage previousFeedMessage, GtfsRealtime.FeedMessage combinedFeedMessage) {
        List<OccurrenceModel> w014List = new ArrayList<>();
        List<OccurrenceModel> w015List = new ArrayList<>();
        List<OccurrenceModel> w016List = new ArrayList<>();
        List<OccurrenceModel> w017List = new ArrayList<>();
        int tripUpdateCount = 0;
        int vehiclePositionCount = 0;

        for (GtfsRealtime.FeedEntity entity : feedMessage.getEntityList()) {
            if (entity.hasTripUpdate()) {
                tripUpdateCount++;
                GtfsRealtime.TripUpdate tripUpdate = entity.getTripUpdate();
                String problem = getTimestampProblem(currentTimeMillis, tripUpdate.hasTimestamp() ? tripUpdate.getTimestamp() : 0);
                if (problem != null) {
                    // W014 - TripUpdate timestamp missing or stale
                    RuleUtils.addOccurrence(W014, GtfsUtils.getTripId(entity, tripUpdate) + " timestamp " + problem, w014List, _log);
                }
            }
            if (entity.hasVehicle()) {
                vehiclePositionCount++;
                GtfsRealtime.VehiclePosition vehicle = entity.getVehicle();
                String problem = getTimestampProblem(currentTimeMillis, vehicle.hasTimestamp() ? vehicle.getTimestamp() : 0);
                if (problem != null) {
                    // W015 - VehiclePosition timestamp missing or stale
                    RuleUtils.addOccurrence(W015, GtfsUtils.getVehicleId(entity, vehicle) + " timestamp " + problem, w015List, _log);
                }
            }
        }

        if (tripUpdateCount > 0 && isAtOrAboveThreshold(w014List.size(), tripUpdateCount)) {
            // W016 - Many TripUpdate timestamps missing or stale
            RuleUtils.addOccurrence(W016, describeFeedWide(w014List.size(), tripUpdateCount, "TripUpdates"), w016List, _log);
        }
        if (vehiclePositionCount > 0 && isAtOrAboveThreshold(w015List.size(), vehiclePositionCount)) {
            // W017 - Many VehiclePosition timestamps missing or stale
            RuleUtils.addOccurrence(W017, describeFeedWide(w015List.size(), vehiclePositionCount, "VehiclePositions"), w017List, _log);
        }

        List<ErrorListHelperModel> errors = new ArrayList<>();
        if (!w014List.isEmpty()) {
            errors.add(new ErrorListHelperModel(new MessageLogModel(W014), w014List));
        }
        if (!w015List.isEmpty()) {
            errors.add(new ErrorListHelperModel(new MessageLogModel(W015), w015List));
        }
        if (!w016List.isEmpty()) {
            errors.add(new ErrorListHelperModel(new MessageLogModel(W016), w016List));
        }
        if (!w017List.isEmpty()) {
            errors.add(new ErrorListHelperModel(new MessageLogModel(W017), w017List));
        }
        return errors;
    }

    /**
     * Returns a description of the problem with the provided entity timestamp, or null if there is no problem.
     * Timestamps in the future are not considered stale (they are covered by E050).
     *
     * @param currentTimeMillis the current system time, in milliseconds
     * @param timestampSeconds  the entity timestamp, in POSIX seconds, or 0 if it isn't populated
     * @return a description of the problem with the timestamp, or null if it is populated and not stale
     */
    private static String getTimestampProblem(long currentTimeMillis, long timestampSeconds) {
        if (timestampSeconds == 0) {
            return "is missing";
        }
        long ageMillis = getAge(currentTimeMillis, timestampSeconds);
        if (ageMillis > TimeUnit.SECONDS.toMillis(MAX_AGE_SECONDS)) {
            long ageSeconds = TimeUnit.MILLISECONDS.toSeconds(ageMillis);
            return timestampSeconds + " is " + ageSeconds / 60 + " min " + ageSeconds % 60 + " sec old, which is greater than the recommended maximum of " + MAX_AGE_SECONDS + " seconds";
        }
        return null;
    }

    private static boolean isAtOrAboveThreshold(int problemCount, int totalCount) {
        return problemCount > 0 && ((double) problemCount / totalCount) >= FEED_WIDE_THRESHOLD;
    }

    private static String describeFeedWide(int problemCount, int totalCount, String feedName) {
        return problemCount + " of " + totalCount + " (" + Math.round(100.0d * problemCount / totalCount) + "%) entities in the " + feedName + " feed have a missing or stale timestamp,";
    }
}
