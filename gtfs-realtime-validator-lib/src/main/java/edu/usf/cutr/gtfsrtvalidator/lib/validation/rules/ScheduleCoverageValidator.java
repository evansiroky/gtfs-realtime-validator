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
import edu.usf.cutr.gtfsrtvalidator.lib.util.RuleUtils;
import edu.usf.cutr.gtfsrtvalidator.lib.validation.GtfsMetadata;
import edu.usf.cutr.gtfsrtvalidator.lib.validation.interfaces.FeedEntityValidator;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.gtfs.model.ServiceCalendar;
import org.onebusaway.gtfs.model.ServiceCalendarDate;
import org.onebusaway.gtfs.model.StopTime;
import org.onebusaway.gtfs.model.Trip;
import org.onebusaway.gtfs.model.calendar.ServiceDate;
import org.onebusaway.gtfs.services.GtfsMutableDao;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static edu.usf.cutr.gtfsrtvalidator.lib.validation.ValidationRules.*;

/**
 * Compares the trips that the GTFS data says should be in service at the time the feed was downloaded to the trips
 * that are in the GTFS-realtime feeds.  All rules in this class are warnings.
 *
 * Trips defined in frequencies.txt are not examined, as they don't have fixed start and end times.
 *
 * W010 - Scheduled in-service trip_id not in TripUpdates feed
 * W011 - Most scheduled in-service trips not in TripUpdates feed
 * W012 - Most scheduled in-service trips not in VehiclePositions feed
 * W013 - Scheduled in-service trip_id not in VehiclePositions feed
 */
public class ScheduleCoverageValidator implements FeedEntityValidator {

    private static final org.slf4j.Logger _log = LoggerFactory.getLogger(ScheduleCoverageValidator.class);

    /**
     * Fraction (0.0 - 1.0) of scheduled in-service trips that a feed should include to cover the "vast majority" of trips
     */
    public final static double MINIMUM_COVERAGE = 0.9d;

    @Override
    public List<ErrorListHelperModel> validate(long currentTimeMillis, GtfsMutableDao gtfsData, GtfsMetadata gtfsMetadata, GtfsRealtime.FeedMessage feedMessage, GtfsRealtime.FeedMessage previousFeedMessage, GtfsRealtime.FeedMessage combinedFeedMessage) {
        List<ErrorListHelperModel> errors = new ArrayList<>();

        boolean checkTripUpdates = false;
        boolean checkVehiclePositions = false;
        for (GtfsRealtime.FeedEntity entity : feedMessage.getEntityList()) {
            checkTripUpdates |= entity.hasTripUpdate();
            checkVehiclePositions |= entity.hasVehicle();
        }
        if (!checkTripUpdates && !checkVehiclePositions) {
            return errors;
        }

        // If multiple feeds are being monitored, use all of them to determine which trips are accounted for
        GtfsRealtime.FeedMessage source = combinedFeedMessage != null ? combinedFeedMessage : feedMessage;
        Set<String> tripUpdateTripIds = new HashSet<>();
        Set<String> vehicleTripIds = new HashSet<>();
        Set<String> canceledTripIds = new HashSet<>();
        for (GtfsRealtime.FeedEntity entity : source.getEntityList()) {
            if (entity.hasTripUpdate() && entity.getTripUpdate().getTrip().hasTripId()) {
                GtfsRealtime.TripDescriptor trip = entity.getTripUpdate().getTrip();
                tripUpdateTripIds.add(trip.getTripId());
                if (trip.getScheduleRelationship() == GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED) {
                    canceledTripIds.add(trip.getTripId());
                }
            }
            if (entity.hasVehicle() && entity.getVehicle().getTrip().hasTripId()) {
                vehicleTripIds.add(entity.getVehicle().getTrip().getTripId());
            }
        }

        Set<String> inServiceTripIds = getInServiceTripIds(currentTimeMillis, gtfsData, gtfsMetadata);
        if (inServiceTripIds.isEmpty()) {
            return errors;
        }

        if (checkTripUpdates) {
            List<OccurrenceModel> w010List = new ArrayList<>();
            List<OccurrenceModel> w011List = new ArrayList<>();
            int covered = 0;
            for (String tripId : inServiceTripIds) {
                if (tripUpdateTripIds.contains(tripId)) {
                    covered++;
                } else {
                    // W010 - Scheduled in-service trip_id not in TripUpdates feed
                    RuleUtils.addOccurrence(W010, "trip_id " + tripId, w010List, _log);
                }
            }
            if (isBelowMinimumCoverage(covered, inServiceTripIds.size())) {
                // W011 - Most scheduled in-service trips not in TripUpdates feed
                RuleUtils.addOccurrence(W011, describeCoverage(covered, inServiceTripIds.size(), "TripUpdates"), w011List, _log);
            }
            addIfNotEmpty(errors, W010, w010List);
            addIfNotEmpty(errors, W011, w011List);
        }

        if (checkVehiclePositions) {
            // Canceled trips aren't expected to have a vehicle
            Set<String> expected = new TreeSet<>(inServiceTripIds);
            expected.removeAll(canceledTripIds);
            if (!expected.isEmpty()) {
                List<OccurrenceModel> w012List = new ArrayList<>();
                List<OccurrenceModel> w013List = new ArrayList<>();
                int covered = 0;
                for (String tripId : expected) {
                    if (vehicleTripIds.contains(tripId)) {
                        covered++;
                    } else {
                        // W013 - Scheduled in-service trip_id not in VehiclePositions feed
                        RuleUtils.addOccurrence(W013, "trip_id " + tripId, w013List, _log);
                    }
                }
                if (isBelowMinimumCoverage(covered, expected.size())) {
                    // W012 - Most scheduled in-service trips not in VehiclePositions feed
                    RuleUtils.addOccurrence(W012, describeCoverage(covered, expected.size(), "VehiclePositions"), w012List, _log);
                }
                addIfNotEmpty(errors, W012, w012List);
                addIfNotEmpty(errors, W013, w013List);
            }
        }
        return errors;
    }

    private static void addIfNotEmpty(List<ErrorListHelperModel> errors, edu.usf.cutr.gtfsrtvalidator.lib.model.ValidationRule rule, List<OccurrenceModel> list) {
        if (!list.isEmpty()) {
            errors.add(new ErrorListHelperModel(new MessageLogModel(rule), list));
        }
    }

    private static boolean isBelowMinimumCoverage(int covered, int total) {
        return ((double) covered / total) < MINIMUM_COVERAGE;
    }

    private static String describeCoverage(int covered, int total, String feedName) {
        return "Only " + covered + " of " + total + " (" + Math.round(100.0d * covered / total) + "%) trips scheduled to be in service are in the " + feedName + " feed,";
    }

    /**
     * Returns the trip_ids of all non-frequency-based trips that are scheduled to be in service at the provided time,
     * i.e., trips with a service_id that is active on the service date, and where the time is between the first stop
     * time and the last stop time of the trip.  Yesterday's service date is also examined, for trips with stop times
     * after 24:00:00.
     */
    private Set<String> getInServiceTripIds(long currentTimeMillis, GtfsMutableDao gtfsData, GtfsMetadata gtfsMetadata) {
        Set<String> result = new TreeSet<>();
        TimeZone timeZone = gtfsMetadata.getTimeZone();
        Calendar today = Calendar.getInstance(timeZone);
        today.setTimeInMillis(currentTimeMillis);
        for (int daysAgo = 0; daysAgo <= 1; daysAgo++) {
            Calendar day = (Calendar) today.clone();
            day.add(Calendar.DAY_OF_MONTH, -daysAgo);
            ServiceDate serviceDate = new ServiceDate(day);
            // GTFS times are relative to "noon minus 12h" on the service date
            Calendar noon = serviceDate.getAsCalendar(timeZone);
            noon.set(Calendar.HOUR_OF_DAY, 12);
            long serviceDayStartMillis = noon.getTimeInMillis() - TimeUnit.HOURS.toMillis(12);
            long secondsIntoServiceDay = TimeUnit.MILLISECONDS.toSeconds(currentTimeMillis - serviceDayStartMillis);

            Set<AgencyAndId> serviceIds = getActiveServiceIds(gtfsData, serviceDate, day.get(Calendar.DAY_OF_WEEK));
            if (serviceIds.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, Trip> entry : gtfsMetadata.getTrips().entrySet()) {
                String tripId = entry.getKey();
                if (!serviceIds.contains(entry.getValue().getServiceId())
                        || gtfsMetadata.getExactTimesZeroTripIds().contains(tripId)
                        || gtfsMetadata.getExactTimesOneTrips().containsKey(tripId)) {
                    continue;
                }
                List<StopTime> stopTimes = gtfsMetadata.getTripStopTimes().get(tripId);
                if (stopTimes == null || stopTimes.isEmpty()) {
                    continue;
                }
                int start = getFirstTime(stopTimes);
                int end = getLastTime(stopTimes);
                if (start != Integer.MIN_VALUE && end != Integer.MIN_VALUE && secondsIntoServiceDay >= start && secondsIntoServiceDay <= end) {
                    result.add(tripId);
                }
            }
        }
        return result;
    }

    /**
     * Returns the service_ids that are active on the provided service date, based on calendar.txt and calendar_dates.txt
     */
    private static Set<AgencyAndId> getActiveServiceIds(GtfsMutableDao gtfsData, ServiceDate date, int dayOfWeek) {
        Set<AgencyAndId> active = new HashSet<>();
        for (ServiceCalendar calendar : gtfsData.getAllCalendars()) {
            if (date.compareTo(calendar.getStartDate()) < 0 || date.compareTo(calendar.getEndDate()) > 0) {
                continue;
            }
            int runs;
            switch (dayOfWeek) {
                case Calendar.MONDAY: runs = calendar.getMonday(); break;
                case Calendar.TUESDAY: runs = calendar.getTuesday(); break;
                case Calendar.WEDNESDAY: runs = calendar.getWednesday(); break;
                case Calendar.THURSDAY: runs = calendar.getThursday(); break;
                case Calendar.FRIDAY: runs = calendar.getFriday(); break;
                case Calendar.SATURDAY: runs = calendar.getSaturday(); break;
                default: runs = calendar.getSunday(); break;
            }
            if (runs == 1) {
                active.add(calendar.getServiceId());
            }
        }
        for (ServiceCalendarDate exception : gtfsData.getAllCalendarDates()) {
            if (date.equals(exception.getDate())) {
                if (exception.getExceptionType() == ServiceCalendarDate.EXCEPTION_TYPE_ADD) {
                    active.add(exception.getServiceId());
                } else if (exception.getExceptionType() == ServiceCalendarDate.EXCEPTION_TYPE_REMOVE) {
                    active.remove(exception.getServiceId());
                }
            }
        }
        return active;
    }

    /**
     * Returns the time of the first stop in seconds after midnight, or Integer.MIN_VALUE if none of the stops have a time
     */
    private static int getFirstTime(List<StopTime> stopTimes) {
        for (StopTime stopTime : stopTimes) {
            if (stopTime.isDepartureTimeSet()) return stopTime.getDepartureTime();
            if (stopTime.isArrivalTimeSet()) return stopTime.getArrivalTime();
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Returns the time of the last stop in seconds after midnight, or Integer.MIN_VALUE if none of the stops have a time
     */
    private static int getLastTime(List<StopTime> stopTimes) {
        for (int i = stopTimes.size() - 1; i >= 0; i--) {
            StopTime stopTime = stopTimes.get(i);
            if (stopTime.isArrivalTimeSet()) return stopTime.getArrivalTime();
            if (stopTime.isDepartureTimeSet()) return stopTime.getDepartureTime();
        }
        return Integer.MIN_VALUE;
    }
}
