package app.aaps.shared.impl.utils

import android.content.Context
import androidx.collection.LongSparseArray
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.R
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.SafeParse
import app.aaps.core.utils.pump.ThreadUtil
import java.security.SecureRandom
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import android.text.format.DateFormat as AndroidDateFormat

/**
 * The Class DateUtil. A simple wrapper around SimpleDateFormat to ease the handling of iso date string &lt;-&gt; date obj
 * with TZ
 */
@Singleton
class DateUtilImpl @Inject constructor(private val context: Context) : DateUtil {

    /**
     * Creates a time formatter based on the user's current device locale.
     * @param withSeconds If true, includes seconds in the format (e.g., 1:23:45 PM). Otherwise, omits them (e.g., 1:23 PM).
     * @return A `DateTimeFormatter` configured for localized time.
     */
    private fun getLocalizedTimeFormatter(withSeconds: Boolean = false): DateTimeFormatter { //new
        val style = if (withSeconds) FormatStyle.MEDIUM else FormatStyle.SHORT
        return DateTimeFormatter.ofLocalizedTime(style).withLocale(displayLocale)
    }

    /**
     * Creates a date formatter based on the user's current device locale (e.g., MM/dd/yyyy for US, dd/MM/yyyy for UK).
     * @return A `DateTimeFormatter` configured for a short, localized date.
     */
    private fun getLocalizedDateFormatter(): DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(displayLocale)

    /* old description
     * Takes in an ISO date string of the following format:
     * yyyy-mm-ddThh:mm:ss.ms+HoMo
     *
     * @param isoDateString the iso date string
     * @return the date
     */
    /**
     * Parses a standard ISO-8601 date string into a Unix timestamp in milliseconds.
     * @param isoDateString The string to parse (e.g., "2023-10-27T10:30:00Z").
     * @return The number of milliseconds since the Unix epoch.
     * Unlike the old Joda-Time, this will throw an exception if the provided
     * string is not in the correct format.
     */
    override fun fromISODateString(isoDateString: String): Long =
        Instant.parse(isoDateString).toEpochMilli()

    /**
     * Converts a Unix timestamp in milliseconds into a standard ISO-8601 formatted string in UTC.
     * @param date The timestamp in milliseconds.
     * @return An ISO-formatted date string (e.g., "2023-10-27T10:30:00Z").
     */
    override fun toISOString(date: Long): String =
        ISO_INSTANT_FORMATTER.format(Instant.ofEpochMilli(date))

    /**
     * Converts a timestamp to an ISO-like string, forced into UTC and with a specific "Z" suffix format.
     * @param timestamp The timestamp in milliseconds.
     * @return A UTC formatted string like "2023-10-27T10:30:00.1230000Z".
     */
    override fun toISOAsUTC(timestamp: Long): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'0000Z'", Locale.US)
        return formatter.withZone(ZoneId.of("UTC")).format(Instant.ofEpochMilli(timestamp))
    }

    /**
     * Converts a timestamp to an ISO-like string representing the local date and time in the app's `systemZone`, without any timezone information.
     * @param timestamp The timestamp in milliseconds.
     * @return A local formatted string like "2023-10-27T06:30:00".
     */
    override fun toISONoZone(timestamp: Long): String =
        Instant.ofEpochMilli(timestamp).atZone(systemZone).format(ISO_LOCAL_FORMATTER)

    /**
     * Converts a number of seconds from the beginning of today into a full Unix timestamp for that time.
     * @param seconds The number of seconds past midnight.
     * @return The full timestamp in milliseconds for that time on the current day.
     */
    override fun secondsOfTheDayToMilliseconds(seconds: Int): Long {
        val startOfToday = LocalDate.now(systemZone).atStartOfDay(systemZone)
        val targetTime = startOfToday.plusSeconds(seconds.toLong())
        return targetTime.toInstant().toEpochMilli()
    }
    /*//TODO: check: original version returned ms from epoch for "minutes of today if today were in january"
        override fun secondsOfTheDayToMilliseconds(seconds: Int): Long {
        val calendar: Calendar = GregorianCalendar()
        calendar[Calendar.MONTH] = 0 // Set january to be sure we miss DST changing
        calendar[Calendar.HOUR_OF_DAY] = seconds / 60 / 60
        calendar[Calendar.MINUTE] = seconds / 60 % 60
        calendar[Calendar.SECOND] = 0
        return calendar.timeInMillis
    }*/

    /**
     * Parses a time string (e.g., "14:30", "2:30 PM") into the total number of seconds from the start of the day.
     * @param hhColonMm The time string to parse.
     * @return The total number of seconds from midnight.
     */
    override fun toSeconds(hhColonMm: String): Int {
        val p = Pattern.compile("(\\d+):(\\d+)( a.m.| p.m.| AM| PM|AM|PM|)")
        val m = p.matcher(hhColonMm)
        var retVal = 0
        if (m.find()) {
            var hour = SafeParse.stringToInt(m.group(1))
            val minute = SafeParse.stringToInt(m.group(2))
            val amPm = m.group(3)?.trim()?.uppercase(Locale.US) ?: ""
            if (amPm.endsWith("AM") && hour == 12) hour = 0 // Midnight case
            if (amPm.endsWith("PM") && hour != 12) hour += 12 // Afternoon case
            retVal = (hour * 3600) + (minute * 60)
        }
        return retVal
    }

    /**
     * Formats a timestamp into a localized date string (e.g., "10/27/2023").
     * @param mills The timestamp in milliseconds.
     * @return The formatted date string.
     */
    override fun dateString(mills: Long): String =
        Instant.ofEpochMilli(mills).atZone(systemZone).format(getLocalizedDateFormatter())

    /**
     * Formats a timestamp into a user-friendly, relative date string (e.g., "Today", "Yesterday", "Tomorrow").
     * Falls back to a standard date format for dates further in the past or future.
     * @param mills The timestamp in milliseconds.
     * @param rh A resource helper to get localized strings like "Today".
     * @return The relative date string.
     */
    override fun dateStringRelative(mills: Long, rh: ResourceHelper): String {
        val day = dateString(mills)
        val beginOfToday = beginOfDay(now())
        val now = now() // Now
        return if (mills < now) { // Past
            when {
                mills > beginOfToday                     -> rh.gs(R.string.today)
                mills > beginOfToday - T.days(1).msecs() -> rh.gs(R.string.yesterday)
                mills > beginOfToday - T.days(7).msecs() -> dayAgo(mills, rh, true)
                else                                     -> day
            }
        } else {// Future
            when {
                mills < beginOfToday + T.days(1).msecs() -> rh.gs(R.string.later_today)
                mills < beginOfToday + T.days(2).msecs() -> rh.gs(R.string.tomorrow)
                mills < beginOfToday + T.days(7).msecs() -> dayAgo(mills, rh, true)
                else                                     -> day
            }
        }
    }

    /**
     * Formats a timestamp into a short date string (e.g., "10/27" or "27/10") based on the user's 12/24 hour preference.
     * @param mills The timestamp in milliseconds.
     * @return A short date string.
     */
    override fun dateStringShort(mills: Long): String {
        val pattern = if (AndroidDateFormat.is24HourFormat(context)) "dd/MM" else "MM/dd"
        val formatter = DateTimeFormatter.ofPattern(pattern)
        return Instant.ofEpochMilli(mills).atZone(systemZone).format(formatter)
    }

    /**
     * Gets the current time formatted as a string (e.g., "10:30 AM"), respecting the device's 12/24 hour setting.
     * @return The formatted time string.
     */
    override fun timeString(): String = timeString(now())

    /**
     * Formats a timestamp as a string (e.g., "10:30 AM"), respecting the device's 12/24 hour setting.
     * @param mills The timestamp in milliseconds.
     * @return The formatted time string.
     */
    override fun timeString(mills: Long): String {
        val pattern = if (AndroidDateFormat.is24HourFormat(context)) "HH:mm" else "hh:mm a"
        val formatter = DateTimeFormatter.ofPattern(pattern, displayLocale) // Use locale for "a" (AM/PM)
        return Instant.ofEpochMilli(mills).atZone(systemZone).format(formatter)
    }

    /**
     * Gets the seconds part of the current time as a two-digit string (e.g., "05").
     * @return The formatted seconds string.
     */
    override fun secondString(): String = secondString(now())

    /**
     * Extracts the seconds part of a timestamp as a two-digit string (e.g., "05").
     * @param mills The timestamp in milliseconds.
     * @return The formatted seconds string.
     */
    override fun secondString(mills: Long): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(mills), systemZone)
        .format(DateTimeFormatter.ofPattern("ss"))

    /**
     * Gets the minutes part of the current time as a two-digit string (e.g., "30").
     * @return The formatted minutes string.
     */
    override fun minuteString(): String = minuteString(now())

    /**
     * Extracts the minutes part of a timestamp as a two-digit string (e.g., "30").
     * @param mills The timestamp in milliseconds.
     * @return The formatted minutes string.
     */
    override fun minuteString(mills: Long): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(mills), systemZone)
        .format(DateTimeFormatter.ofPattern("mm"))

    /**
     * Gets the hour part of the current time as a string, respecting the 12/24 hour format.
     * @return The formatted hour string.
     */
    override fun hourString(): String = hourString(now())

    /**
     * Extracts the hour part of a timestamp as a string, respecting the 12/24 hour format.
     * @param mills The timestamp in milliseconds.
     * @return The formatted hour string.
     */
    override fun hourString(mills: Long): String {
        val pattern = if (AndroidDateFormat.is24HourFormat(context)) "H" else "h"
        val formatter = DateTimeFormatter.ofPattern(pattern)
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(mills), systemZone).format(formatter)
    }

    /**
     * Gets the localized AM/PM designator for the current time.
     * @return The AM/PM string (e.g., "AM", "PM").
     */
    override fun amPm(): String = amPm(now())

    /**
     * Gets the localized AM/PM designator for a given timestamp.
     * @param mills The timestamp in milliseconds.
     * @return The AM/PM string.
     */
    override fun amPm(mills: Long): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(mills), systemZone)
        .format(DateTimeFormatter.ofPattern("a", displayLocale))

    /**
     * Gets the day name for the current date, formatted according to the given pattern.
     * @param format A pattern like "EEE" (short name) or "EEEE" (full name).
     * @return The formatted day name.
     */
    override fun dayNameString(format: String): String = dayNameString(now(), format)

    /**
     * Gets the day name for a given timestamp, formatted according to the given pattern.
     * @param mills The timestamp in milliseconds.
     * @param format A pattern like "EEE" (short name) or "EEEE" (full name).
     * @return The formatted day name.
     */
    override fun dayNameString(mills: Long, format: String): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(mills), systemZone)
        .format(DateTimeFormatter.ofPattern(format, displayLocale))

    /**
     * Gets the day of the month for the current date as a two-digit string (e.g., "27").
     * @return The formatted day string.
     */
    override fun dayString(): String = dayString(now())

    /**
     * Extracts the day of the month from a timestamp as a two-digit string (e.g., "27").
     * @param mills The timestamp in milliseconds.
     * @return The formatted day string.
     */
    override fun dayString(mills: Long): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(mills), systemZone)
        .format(DateTimeFormatter.ofPattern("dd"))

    /**
     * Gets the month for the current date, formatted according to the given pattern.
     * @param format A pattern like "M" (number), "MMM" (short name), or "MMMM" (full name).
     * @return The formatted month string.
     */
    override fun monthString(format: String): String = monthString(now(), format)

    /**
     * Extracts the month from a timestamp, formatted according to the given pattern.
     * @param mills The timestamp in milliseconds.
     * @param format A pattern like "M" (number), "MMM" (short name), or "MMMM" (full name).
     * @return The formatted month string.
     */
    override fun monthString(mills: Long, format: String): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(mills), systemZone)
        .format(DateTimeFormatter.ofPattern(format, displayLocale))

    /**
     * Gets the week of the year for the current date as a string.
     * @return The formatted week string.
     */
    override fun weekString(): String = weekString(now())

    /**
     * Extracts the week of the year from a timestamp as a string.
     * @param mills The timestamp in milliseconds.
     * @return The formatted week string.
     */
    override fun weekString(mills: Long): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(mills), systemZone)
        .format(DateTimeFormatter.ofPattern("ww"))

    /**
     * Formats a timestamp into a time string that includes seconds (e.g., "10:30:05 AM").
     * Respects the device's 12/24 hour setting.
     * @param mills The timestamp in milliseconds.
     * @return The formatted time string with seconds.
     */
    override fun timeStringWithSeconds(mills: Long): String {
        val pattern = if (AndroidDateFormat.is24HourFormat(context)) "HH:mm:ss" else "hh:mm:ss a"
        val formatter = DateTimeFormatter.ofPattern(pattern, displayLocale)
        return Instant.ofEpochMilli(mills).atZone(systemZone).format(formatter)
    }

    /**
     * Creates a string representing a date and time range.
     * @param start The start timestamp in milliseconds.
     * @param end The end timestamp in milliseconds.
     * @return A formatted string like "10/27/2023 10:00 AM - 11:00 AM".
     */
    override fun dateAndTimeRangeString(start: Long, end: Long): String =
        dateAndTimeString(start) + " - " + timeString(end)

    /**
     * Creates a string representing a time range.
     * @param start The start timestamp in milliseconds.
     * @param end The end timestamp in milliseconds.
     * @return A formatted string like "10:00 AM - 11:00 AM".
     */
    override fun timeRangeString(start: Long, end: Long): String =
        timeString(start) + " - " + timeString(end)

    /**
     * Combines the localized date and time strings for a given timestamp.
     * @param mills The timestamp in milliseconds. Returns empty string if 0.
     * @return A formatted string like "10/27/2023 10:30 AM".
     */
    override fun dateAndTimeString(mills: Long): String =
        if (mills == 0L) "" else dateString(mills) + " " + timeString(mills)

    /**
     * Combines the localized date and time strings for a given timestamp, returning null if the timestamp is null or 0.
     * @param mills The nullable timestamp in milliseconds.
     * @return A formatted string like "10/27/2023 10:30 AM", or null.
     */
    override fun dateAndTimeStringNullable(mills: Long?): String? =
        if (mills == null || mills == 0L) null else dateString(mills) + " " + timeString(mills)

    /**
     * Combines the localized date and time (with seconds) strings for a given timestamp.
     * @param mills The timestamp in milliseconds. Returns empty string if 0.
     * @return A formatted string like "10/27/2023 10:30:05 AM".
     */
    override fun dateAndTimeAndSecondsString(mills: Long): String =
        if (mills == 0L) "" else dateString(mills) + " " + timeStringWithSeconds(mills)

    /**
     * Returns a string describing how many minutes ago a timestamp was (e.g., "5 min ago").
     * @param rh Resource helper for localized strings.
     * @param time The timestamp in milliseconds. Can be null.
     * @return The relative time string.
     */
    override fun minAgo(rh: ResourceHelper, time: Long?): String {
        if (time == null) return ""
        val minutes = ((now() - time) / 1000 / 60).toInt()
        return if (abs(minutes) > 9999) "" else rh.gs(R.string.minago, minutes)
    }

    /**
     * Returns a string describing how many minutes or seconds ago a timestamp was.
     * Uses seconds if under 2 minutes, otherwise minutes.
     * @param rh Resource helper for localized strings.
     * @param time The timestamp in milliseconds. Can be null.
     * @return The relative time string (e.g., "30 sec ago" or "3 min ago").
     */
    override fun minOrSecAgo(rh: ResourceHelper, time: Long?): String {
        if (time == null) return ""
        //val minutes = ((now() - time) / 1000 / 60).toInt()
        val seconds = (now() - time) / 1000
        return if (seconds > 119) {
            rh.gs(R.string.minago, (seconds / 60).toInt())
        } else {
            rh.gs(R.string.secago, seconds.toInt())
        }
    }

    /**
     * Returns a short string showing the difference in minutes between now and a given time, with a sign.
     * E.g., "(+5)" for 5 minutes in the future, "(-10)" for 10 minutes in the past.
     * @param time The timestamp in milliseconds. Can be null.
     * @return The short formatted difference string.
     */
    override fun minAgoShort(time: Long?): String {
        if (time == null) return ""
        val minutes = ((time - now()) / 1000 / 60).toInt()
        return if (abs(minutes) > 9999) ""
        else "(" + (if (minutes > 0) "+" else "") + minutes + ")"
    }

    /**
     * Returns a verbose string describing how many minutes ago a timestamp was.
     * @param rh Resource helper for localized strings.
     * @param time The timestamp in milliseconds. Can be null.
     * @return The verbose relative time string.
     */
    override fun minAgoLong(rh: ResourceHelper, time: Long?): String {
        if (time == null) return ""
        val minutes = ((now() - time) / 1000 / 60).toInt()
        return if (abs(minutes) > 9999) "" else rh.gs(R.string.minago_long, minutes)
    }

    /**
     * Returns a string describing how many hours ago a timestamp was.
     * @param time The timestamp in milliseconds.
     * @param rh Resource helper for localized strings.
     * @return The relative hours string.
     */
    override fun hourAgo(time: Long, rh: ResourceHelper): String {
        val hours = (now() - time) / 1000.0 / 60 / 60
        return rh.gs(R.string.hoursago, hours)
    }

    /**
     * Returns a string describing how many days ago (or in how many days) a timestamp is.
     * @param time The timestamp in milliseconds.
     * @param rh Resource helper for localized strings.
     * @param round If true, rounds to the nearest whole day. Otherwise uses fractional days.
     * @return The relative days string.
     */
    override fun dayAgo(time: Long, rh: ResourceHelper, round: Boolean): String {
        var days = (now() - time) / 1000.0 / 60 / 60 / 24
        if (round) {
            return if (now() > time) {
                days = ceil(days)
                rh.gs(R.string.days_ago_round, days)
            } else {
                days = floor(days)
                rh.gs(R.string.in_days_round, days)
            }
        }
        return if (now() > time)
            rh.gs(R.string.days_ago, days)
        else
            rh.gs(R.string.in_days, days)
    }

    /**
     * Calculates the timestamp for the beginning of the day (midnight) for a given timestamp.
     * @param mills The timestamp in milliseconds.
     * @return The timestamp in milliseconds for midnight at the start of that day.
     */
    override fun beginOfDay(mills: Long): Long =
        Instant.ofEpochMilli(mills).atZone(systemZone)
            .truncatedTo(ChronoUnit.DAYS)
            .toInstant().toEpochMilli()

    /**
     * Converts seconds from the start of the day to a formatted time string, with caching for performance.
     * @param seconds The number of seconds past midnight.
     * @return A formatted time string (e.g., "10:30 AM").
     */
    override fun timeStringFromSeconds(seconds: Int): String {
        val cached = timeStrings[seconds.toLong()]
        if (cached != null) return cached
        val t = timeString(secondsOfTheDayToMilliseconds(seconds))
        timeStrings.put(seconds.toLong(), t)
        return t
    }

    /**
     * Formats a duration in milliseconds into a human-readable string with hours and minutes.
     * @param timeInMillis The duration in milliseconds.
     * @param rh Resource helper for localized units (e.g., "h").
     * @return A formatted duration string like "(1h 30m)".
     */
    override fun timeFrameString(timeInMillis: Long, rh: ResourceHelper): String {
        var remainingTimeMinutes = timeInMillis / (1000 * 60)
        val remainingTimeHours = remainingTimeMinutes / 60
        remainingTimeMinutes %= 60
        return "(" + (if (remainingTimeHours > 0) remainingTimeHours.toString() + rh.gs(R.string.shorthour) + " " else "") + remainingTimeMinutes + "')"
    }

    /**
     * Calculates the elapsed time since a given timestamp and formats it as a duration.
     * @param timestamp The past timestamp in milliseconds.
     * @param rh Resource helper.
     * @return A formatted duration string of the elapsed time.
     */
    override fun sinceString(timestamp: Long, rh: ResourceHelper): String =
        timeFrameString(System.currentTimeMillis() - timestamp, rh)

    /**
     * Calculates the time remaining until a future timestamp and formats it as a duration.
     * @param timestamp The future timestamp in milliseconds.
     * @param rh Resource helper.
     * @return A formatted duration string of the remaining time.
     */
    override fun untilString(timestamp: Long, rh: ResourceHelper): String =
         timeFrameString(timestamp - System.currentTimeMillis(), rh)

    /**
     * Gets the current system time in milliseconds.
     * @return The current timestamp.
     */
    override fun now(): Long =
        System.currentTimeMillis()

    /**
     * Gets the current system time in milliseconds, with the millisecond part set to zero.
     * @return The current timestamp, truncated to the second.
     */
    override fun nowWithoutMilliseconds(): Long =
        Instant.now().truncatedTo(ChronoUnit.SECONDS).toEpochMilli()

    /**
     * Checks if a given timestamp is older than a specified number of minutes from now.
     * @param date The timestamp to check, in milliseconds.
     * @param minutes The number of minutes to check against.
     * @return True if the date is older, false otherwise.
     */
    override fun isOlderThan(date: Long, minutes: Long): Boolean =
        Instant.ofEpochMilli(date).isBefore(Instant.now().minus(minutes, ChronoUnit.MINUTES))

    /**
     * Gets the standard (non-DST) timezone offset for the app's `systemZone` in milliseconds.
     * @return The standard offset in milliseconds.
     */
    override fun getTimeZoneOffsetMs(): Long { //TODO: This was and is not DST aware. Check if intended.
        val standardOffset = systemZone.rules.getStandardOffset(Instant.now())
        return standardOffset.totalSeconds * 1000L
    }

    /**
     * Gets the timezone offset in minutes for a specific moment in time.
     * This correctly handles Daylight Saving Time (DST), returning the actual offset for that instant.
     * @param timestamp The timestamp in milliseconds to check.
     * @return The total offset from UTC in minutes (e.g., -240 for EDT, -300 for EST).
     */
    override fun getTimeZoneOffsetMinutes(timestamp: Long): Int {
        val actualOffset = systemZone.rules.getOffset(Instant.ofEpochMilli(timestamp))
        return actualOffset.totalSeconds / 60
    }

    /**
     * Checks if two timestamps occur on the same calendar day in the app's `systemZone`.
     * This is DST-safe and correctly handles timestamps that might be on different UTC dates but the same local date.
     * @param timestamp1 The first timestamp in milliseconds.
     * @param timestamp2 The second timestamp in milliseconds.
     * @return True if they are on the same local calendar day, false otherwise.
     */
    override fun isSameDay(timestamp1: Long, timestamp2: Long): Boolean {
        // Convert each millisecond timestamp to an Instant (UTC)
        val instant1 = Instant.ofEpochMilli(timestamp1)
        val instant2 = Instant.ofEpochMilli(timestamp2)
        // Apply the system's timezone to get the correct local date for each instant
        val date1 = instant1.atZone(systemZone).toLocalDate()
        val date2 = instant2.atZone(systemZone).toLocalDate()
        // The .equals() method for LocalDate is a reliable way to check if they are the same day.
        return date1.isEqual(date2)
    }

    /**
     * Checks if the current time in the app's `systemZone` is past noon (12:00 PM).
     * @return True if the hour is 12 or greater, false otherwise.
     */
    override fun isAfterNoon(): Boolean =
        ZonedDateTime.now(systemZone).hour >= 12

    /**
     * A specialized check to see if two timestamps are on the same day, with an additional
     * condition that the current time (`now`) does not fall between them.
     * @param timestamp1 The first timestamp in milliseconds.
     * @param timestamp2 The second timestamp in milliseconds.
     * @return False if `now` is between the two timestamps, otherwise the result of `isSameDay()`.
     */
    override fun isSameDayGroup(timestamp1: Long, timestamp2: Long): Boolean {
        val now = now()
        if (now in (timestamp1 + 1) until timestamp2 || now in (timestamp2 + 1) until timestamp1) return false
        return isSameDay(timestamp1, timestamp2)
    }

    /**
     * Computes the difference between two timestamps and breaks it down into whole days, hours, and minutes.
     * @param date1 The start timestamp in milliseconds.
     * @param date2 The end timestamp in milliseconds.
     * @return A map containing the total number of full days, leftover hours, and leftover minutes.
     */
    //Map:{DAYS=1, HOURS=3, MINUTES=46, SECONDS=40, MILLISECONDS=0, MICROSECONDS=0, NANOSECONDS=0}
    override fun computeDiff(date1: Long, date2: Long): Map<TimeUnit, Long> {
        val duration = java.time.Duration.ofMillis(date2 - date1)
        val days = duration.toDays()
        val hours = duration.toHours() % 24
        val minutes = duration.toMinutes() % 60
        val seconds = duration.seconds % 60
        val millis = duration.toMillis() % 1000
        val micros = duration.toNanos() / 1000 % 1000
        val nanos = duration.toNanos() % 1000

        return mapOf(
            TimeUnit.DAYS to days,
            TimeUnit.HOURS to hours,
            TimeUnit.MINUTES to minutes,
            TimeUnit.SECONDS to seconds,
            TimeUnit.MILLISECONDS to millis,
            TimeUnit.MICROSECONDS to micros,
            TimeUnit.NANOSECONDS to nanos
        )
    }

    /**
     * Converts a duration in milliseconds into a human-readable "age" string (e.g., "5 days 3 hours").
     * @param milliseconds The duration to format.
     * @param useShortText If true, uses abbreviated units (e.g., "d", "h"). If false, uses full names (e.g., "days", "hours").
     * @param rh Resource helper to get localized unit strings.
     * @return The formatted age string.
     */
    override fun age(milliseconds: Long, useShortText: Boolean, rh: ResourceHelper): String {
        val duration = java.time.Duration.ofMillis(milliseconds)
        if (duration.toDays() > 1000) return rh.gs(R.string.forever)
        val daysUnit = if (useShortText) rh.gs(R.string.shortday) else rh.gs(R.string.days)
        val hoursUnit = if (useShortText) rh.gs(R.string.shorthour) else rh.gs(R.string.hours)
        val minutesUnit = if (useShortText) rh.gs(R.string.shortminute) else rh.gs(R.string.unit_minutes)
        val days = duration.toDays()
        val hours = duration.toHours() % 24
        val minutes = duration.toMinutes() % 60
        return when {
            days > 0 -> "$days $daysUnit $hours $hoursUnit"
            hours > 0 -> "$hours $hoursUnit $minutes $minutesUnit"
            else -> "${duration.toMinutes()} $minutesUnit"
        }.trim()
    }

    /**
     * Converts a duration in milliseconds into a simplified, human-readable string with the largest appropriate unit.
     * (e.g., 120000ms becomes "2 minutes"). It handles pluralization for different languages.
     * @param time The duration in milliseconds.
     * @param rh Resource helper to get localized unit strings (e.g., "second", "seconds").
     * @return The formatted string with a single unit (e.g., "5 days").
     */
    override fun niceTimeScalar(time: Long, rh: ResourceHelper): String {
        var t = time
        var unit = rh.gs(R.string.unit_second)
        t /= 1000
        if (t != 1L) unit = rh.gs(R.string.unit_seconds)
        if (t > 59) {
            unit = rh.gs(R.string.unit_minute)
            t /= 60
            if (t != 1L) unit = rh.gs(R.string.unit_minutes)
            if (t > 59) {
                unit = rh.gs(R.string.unit_hour)
                t /= 60
                if (t != 1L) unit = rh.gs(R.string.unit_hours)
                if (t > 24) {
                    unit = rh.gs(R.string.unit_day)
                    t /= 24
                    if (t != 1L) unit = rh.gs(R.string.unit_days)
                    if (t > 28) {
                        unit = rh.gs(R.string.unit_week)
                        t /= 7
                        @Suppress("KotlinConstantConditions")
                        if (t != 1L) unit = rh.gs(R.string.unit_weeks)
                    }
                }
            }
        }
        //if (t != 1) unit = unit + "s"; //implemented plurality in every step, because in other languages plurality of time is not every time adding the same character
        return qs(t.toDouble(), 0) + " " + unit
    }

    /**
     * A thread-safe, locale-agnostic utility to format a double into a string with a specific number of decimal digits.
     * It is optimized to use a cached formatter on the UI thread.
     * @param x The double value to format.
     * @param numDigits The number of decimal digits. If -1, it attempts to auto-detect.
     * @return The formatted string.
     */
    override fun qs(x: Double, numDigits: Int): String {
        var digits = numDigits
        if (digits == -1) {
            digits = 0
            if ((x.toInt() % x == 0.0)) {
                digits++
                if ((x.toInt() * 10 / 10).toDouble() != x) {
                    digits++
                    if ((x.toInt() * 100 / 100).toDouble() != x) digits++
                }
            }
        }
        if (dfs == null) {
            val localDfs = DecimalFormatSymbols()
            localDfs.decimalSeparator = '.'
            dfs = localDfs // avoid race condition
        }
        val thisDf: DecimalFormat?
        // use singleton if on ui thread otherwise allocate new as DecimalFormat is not thread safe
        if (ThreadUtil.threadId() == 1L) {
            if (df == null) {
                val localDf = DecimalFormat("#", dfs)
                localDf.minimumIntegerDigits = 1
                df = localDf // avoid race condition
            }
            thisDf = df
        } else {
            thisDf = DecimalFormat("#", dfs)
        }
        thisDf?.maximumFractionDigits = digits
        return thisDf?.format(x) ?: ""
    }

    /**
     * Formats a total number of seconds into a zero-padded HH:mm string.
     * @param timeAsSeconds The total duration in seconds.
     * @return A formatted string like "08:05".
     */
    override fun formatHHMM(timeAsSeconds: Int): String {
        val hours = timeAsSeconds / 3600
        val minutes = (timeAsSeconds % 3600) / 60
        // "%02d" means "format an integer (d) to be at least 2 digits wide, padding with zeros (0) if necessary."
        return "%02d:%02d".format(hours, minutes)
    }

    /**
     * Attempts to find a representative IANA timezone name (e.g., "America/New_York")
     * that matches a given offset in milliseconds at the present time.
     * @param offsetInMilliseconds The timezone offset from UTC.
     * @return A matching timezone ID string, or "UTC" if no match is found.
     */
    override fun timeZoneByOffset(offsetInMilliseconds: Long): String {
        if (offsetInMilliseconds == 0L) return "UTC"
        val offsetInSeconds = (offsetInMilliseconds / 1000).toInt()
        val now = Instant.now()
        return ZoneId.getAvailableZoneIds()
            .firstOrNull { zoneIdString ->
                val zoneId = ZoneId.of(zoneIdString)
                // Compare the zone's current offset in seconds to the requested offset.
                zoneId.rules.getOffset(now).totalSeconds == offsetInSeconds
            }
            ?: "UTC" // Default to "UTC" if no match is found.
    }
    /*    //TODO: changed return type so I had to modify ProfileSealed accordingly
    override fun timeZoneByOffset(offsetInMilliseconds: Long): TimeZone =
        TimeZone.getTimeZone(
            if (offsetInMilliseconds == 0L) ZoneId.of("UTC")
            else ZoneId.getAvailableZoneIds()
                .stream()
                .map(ZoneId::of)
                .filter { z -> z.rules.getOffset(Instant.now()).totalSeconds == ZoneOffset.ofHours((offsetInMilliseconds / 1000 / 3600).toInt()).totalSeconds }
                .collect(Collectors.toList())
                .firstOrNull() ?: ZoneId.of("UTC")
        )*/

    /**
     * Calculates the timestamp for midnight UTC at the beginning of the day for a given timestamp.
     * This effectively strips the time-of-day information, keeping the UTC date.
     * @param timestamp The timestamp in milliseconds.
     * @return A timestamp in milliseconds, representing the start of the UTC day (00:00:00Z).
     */
    override fun timeStampToUtcDateMillis(timestamp: Long): Long =
        Instant.ofEpochMilli(timestamp).truncatedTo(ChronoUnit.DAYS).toEpochMilli()
    /*    //TODO this has a different output than the old function.
             Since that seems to be desired behaviour in the history browser,
             I'm keeping it in getTimestampWithCurrentTimeOfDay()
    override fun timeStampToUtcDateMillis(timestamp: Long): Long {
        val current = Calendar.getInstance().apply { timeInMillis = timestamp }
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, current[Calendar.YEAR])
            set(Calendar.MONTH, current[Calendar.MONTH])
            set(Calendar.DAY_OF_MONTH, current[Calendar.DAY_OF_MONTH])
        }.timeInMillis
    }*/

    /**
     * [LEGACY SUPPORT FUNCTION] - Creates a new timestamp by combining the DATE from the input
     * with the TIME OF DAY from the current system time, interpreted in the local timezone.
     * Only used in HistoryBrowser.
     * @param timestamp The timestamp providing the date.
     * @return A new timestamp mixing the input date with the current time of day.
     */
    override fun getTimestampWithCurrentTimeOfDay(timestamp: Long): Long {
        val inputDate = Instant.ofEpochMilli(timestamp).atZone(systemZone).toLocalDate()
        val timeOfNow = ZonedDateTime.now(systemZone).toLocalTime()
        return inputDate.atTime(timeOfNow).atZone(systemZone).toInstant().toEpochMilli()
    }

    /**
     * Merges a UTC date with a local time from another timestamp.
     * It takes the date part from `dateUtcMillis` and the time-of-day part from `timestamp`.
     * @param timestamp The timestamp providing the time-of-day (in the app's `systemZone`).
     * @param dateUtcMillis The timestamp providing the date (in UTC).
     * @return A new timestamp combining the UTC date and the local time.
     */
    override fun mergeUtcDateToTimestamp(timestamp: Long, dateUtcMillis: Long): Long {
        val localTime = Instant.ofEpochMilli(timestamp).atZone(systemZone).toLocalTime()
        val utcDate = Instant.ofEpochMilli(dateUtcMillis).atZone(ZoneId.of("UTC")).toLocalDate()
        val finalDateTime = utcDate.atTime(localTime).atZone(systemZone)
        return finalDateTime.toInstant().toEpochMilli()
    }

    /**
     * Creates a new timestamp by replacing the hour and minute of an existing timestamp.
     * @param timestamp The base timestamp to modify.
     * @param hour The new hour to set (0-23).
     * @param minute The new minute to set (0-59).
     * @param randomSecond If true, sets the second to a pseudo-random, incrementing value.
     * @return The new, updated timestamp in milliseconds.
     */
    override fun mergeHourMinuteToTimestamp(timestamp: Long, hour: Int, minute: Int, randomSecond: Boolean): Long {
        val originalDateTime = Instant.ofEpochMilli(timestamp).atZone(systemZone)
        var updatedDateTime = originalDateTime.withHour(hour).withMinute(minute)
        if (randomSecond) updatedDateTime = updatedDateTime.withSecond(seconds++)
        return updatedDateTime.toInstant().toEpochMilli()
    }

    companion object {

        /**
         * The timezone used for all date/time calculations.
         * `ZoneId.systemDefault()` is executed every time this property is accessed.
         */
        private val systemZone: ZoneId get() = ZoneId.systemDefault()

        /**
         * The timezone used for all date/time calculations.
         * This is captured ONCE when the app starts to ensure stability and avoid issues from
         * faulty network-provided timezones. It will not update if the user travels.
         */
        private val systemZoneOnce: ZoneId = ZoneId.systemDefault()

        /**
         * The locale used for formatting strings (e.g., date formats, AM/PM).
         * This is fetched EVERY time it's used to ensure the UI immediately reflects
         * any changes the user makes to their device's language or region settings.
         */
        private val displayLocale: Locale get() = Locale.getDefault()

        /** Formatter for converting an Instant to a standard ISO-8601 UTC string (e.g., "2023-10-27T10:30:00Z"). */
        private val ISO_INSTANT_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
        /** Formatter for creating a local ISO-like string without a timezone (e.g., "2023-10-27T10:30:00"). */
        private val ISO_LOCAL_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        private val timeStrings = LongSparseArray<String>()
        private var seconds: Int = (SecureRandom().nextDouble() * 59.0).toInt()

        // singletons to avoid repeated allocation
        private var dfs: DecimalFormatSymbols? = null
        private var df: DecimalFormat? = null
    }
}
