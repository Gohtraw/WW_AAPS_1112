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

@Singleton
class DateUtilImpl @Inject constructor(private val context: Context) : DateUtil {

    override fun fromISODateString(isoDateString: String): Long =
        Instant.parse(isoDateString).toEpochMilli()

    override fun toISOString(date: Long): String =
        ISO_INSTANT_FORMATTER.format(Instant.ofEpochMilli(date))

    override fun toISOAsUTC(timestamp: Long): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'0000Z'", Locale.US)
        return formatter.withZone(ZoneId.of("UTC")).format(Instant.ofEpochMilli(timestamp))
    }

    override fun toISONoZone(timestamp: Long): String =
        Instant.ofEpochMilli(timestamp).atZone(systemZone).format(ISO_LOCAL_FORMATTER)

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

    override fun dateString(mills: Long): String =
        Instant.ofEpochMilli(mills).atZone(systemZone).format(getLocalizedDateFormatter())

    override fun dateStringRelative(mills: Long, rh: ResourceHelper): String {
        val day = dateString(mills)
        val beginOfToday = beginOfDay(now())
        return if (mills < now()) {// Past
            when {
                mills > beginOfToday                     -> rh.gs(R.string.today)
                mills > beginOfToday - T.days(1).msecs() -> rh.gs(R.string.yesterday)
                mills > beginOfToday - T.days(7).msecs() -> dayAgo(mills, rh, true)
                else                                     -> day
            }
        } else { // Future
            when {
                mills < beginOfToday + T.days(1).msecs() -> rh.gs(R.string.later_today)
                mills < beginOfToday + T.days(2).msecs() -> rh.gs(R.string.tomorrow)
                mills < beginOfToday + T.days(7).msecs() -> dayAgo(mills, rh, true)
                else                                     -> day
            }
        }
    }

    override fun dateStringShort(mills: Long): String {
        val pattern = if (AndroidDateFormat.is24HourFormat(context)) "dd/MM" else "MM/dd"
        val formatter = DateTimeFormatter.ofPattern(pattern)
        return Instant.ofEpochMilli(mills).atZone(systemZone).format(formatter)
    }

    override fun timeString(): String = timeString(now())
    override fun timeString(mills: Long): String {
        val pattern = if (AndroidDateFormat.is24HourFormat(context)) "HH:mm" else "hh:mm a"
        val formatter = DateTimeFormatter.ofPattern(pattern, displayLocale) // Use locale for "a" (AM/PM)
        return Instant.ofEpochMilli(mills).atZone(systemZone).format(formatter)
    }

    override fun secondString(): String = secondString(now())
    override fun secondString(mills: Long): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(mills), systemZone)
        .format(DateTimeFormatter.ofPattern("ss"))

    override fun minuteString(): String = minuteString(now())
    override fun minuteString(mills: Long): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(mills), systemZone)
        .format(DateTimeFormatter.ofPattern("mm"))

    override fun hourString(): String = hourString(now())
    override fun hourString(mills: Long): String {
        val pattern = if (AndroidDateFormat.is24HourFormat(context)) "H" else "h"
        val formatter = DateTimeFormatter.ofPattern(pattern)
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(mills), systemZone).format(formatter)
    }

    override fun amPm(): String = amPm(now())
    override fun amPm(mills: Long): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(mills), systemZone)
        .format(DateTimeFormatter.ofPattern("a", displayLocale))

    override fun dayNameString(format: String): String = dayNameString(now(), format)
    override fun dayNameString(mills: Long, format: String): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(mills), systemZone)
        .format(DateTimeFormatter.ofPattern(format, displayLocale))

    override fun dayString(): String = dayString(now())
    override fun dayString(mills: Long): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(mills), systemZone)
        .format(DateTimeFormatter.ofPattern("dd"))

    override fun monthString(format: String): String = monthString(now(), format)
    override fun monthString(mills: Long, format: String): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(mills), systemZone)
        .format(DateTimeFormatter.ofPattern(format, displayLocale))

    override fun weekString(): String = weekString(now())

    override fun weekString(mills: Long): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(mills), systemZone)
        .format(DateTimeFormatter.ofPattern("ww"))

    override fun timeStringWithSeconds(mills: Long): String {
        val pattern = if (AndroidDateFormat.is24HourFormat(context)) "HH:mm:ss" else "hh:mm:ss a"
        val formatter = DateTimeFormatter.ofPattern(pattern, displayLocale)
        return Instant.ofEpochMilli(mills).atZone(systemZone).format(formatter)
    }

    override fun dateAndTimeRangeString(start: Long, end: Long): String =
        dateAndTimeString(start) + " - " + timeString(end)

    override fun timeRangeString(start: Long, end: Long): String =
        timeString(start) + " - " + timeString(end)

    override fun dateAndTimeString(mills: Long): String =
        if (mills == 0L) "" else dateString(mills) + " " + timeString(mills)

    override fun dateAndTimeStringNullable(mills: Long?): String? =
        if (mills == null || mills == 0L) null else dateString(mills) + " " + timeString(mills)

    override fun dateAndTimeAndSecondsString(mills: Long): String =
        if (mills == 0L) "" else dateString(mills) + " " + timeStringWithSeconds(mills)

    override fun minAgo(rh: ResourceHelper, time: Long?): String {
        if (time == null) return ""
        val minutes = ((now() - time) / 1000 / 60).toInt()
        return if (abs(minutes) > 9999) "" else rh.gs(R.string.minago, minutes)
    }

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

    override fun minAgoShort(time: Long?): String {
        if (time == null) return ""
        val minutes = ((time - now()) / 1000 / 60).toInt()
        return if (abs(minutes) > 9999) ""
        else "(" + (if (minutes > 0) "+" else "") + minutes + ")"
    }

    override fun minAgoLong(rh: ResourceHelper, time: Long?): String {
        if (time == null) return ""
        val minutes = ((now() - time) / 1000 / 60).toInt()
        return if (abs(minutes) > 9999) "" else rh.gs(R.string.minago_long, minutes)
    }

    override fun hourAgo(time: Long, rh: ResourceHelper): String {
        val hours = (now() - time) / 1000.0 / 60 / 60
        return rh.gs(R.string.hoursago, hours)
    }

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

    override fun beginOfDay(mills: Long): Long =
        Instant.ofEpochMilli(mills).atZone(systemZone)
            .truncatedTo(ChronoUnit.DAYS)
            .toInstant().toEpochMilli()

    override fun timeStringFromSeconds(seconds: Int): String {
        val cached = timeStrings[seconds.toLong()]
        if (cached != null) return cached
        val t = timeString(secondsOfTheDayToMilliseconds(seconds))
        timeStrings.put(seconds.toLong(), t)
        return t
    }

    override fun timeFrameString(timeInMillis: Long, rh: ResourceHelper): String {
        var remainingTimeMinutes = timeInMillis / (1000 * 60)
        val remainingTimeHours = remainingTimeMinutes / 60
        remainingTimeMinutes %= 60
        return "(" + (if (remainingTimeHours > 0) remainingTimeHours.toString() + rh.gs(R.string.shorthour) + " " else "") + remainingTimeMinutes + "')"
    }

    override fun sinceString(timestamp: Long, rh: ResourceHelper): String =
        timeFrameString(System.currentTimeMillis() - timestamp, rh)

    override fun untilString(timestamp: Long, rh: ResourceHelper): String =
         timeFrameString(timestamp - System.currentTimeMillis(), rh)

    override fun now(): Long =
        System.currentTimeMillis()

    override fun nowWithoutMilliseconds(): Long =
        Instant.now().truncatedTo(ChronoUnit.SECONDS).toEpochMilli()

    override fun isOlderThan(date: Long, minutes: Long): Boolean =
        Instant.ofEpochMilli(date).isBefore(Instant.now().minus(minutes, ChronoUnit.MINUTES))

    override fun getTimeZoneOffsetMs(): Long { //TODO: This was and is not DST aware. Check if intended.
        val standardOffset = systemZone.rules.getStandardOffset(Instant.now())
        return standardOffset.totalSeconds * 1000L
    }

    override fun getTimeZoneOffsetMinutes(timestamp: Long): Int {
        val actualOffset = systemZone.rules.getOffset(Instant.ofEpochMilli(timestamp))
        return actualOffset.totalSeconds / 60
    }

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

    override fun isAfterNoon(): Boolean =
        ZonedDateTime.now(systemZone).hour >= 12

    override fun isSameDayGroup(timestamp1: Long, timestamp2: Long): Boolean {
        val now = now()
        if (now in (timestamp1 + 1) until timestamp2 || now in (timestamp2 + 1) until timestamp1) return false
        return isSameDay(timestamp1, timestamp2)
    }

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

    override fun formatHHMM(timeAsSeconds: Int): String {
        val hours = timeAsSeconds / 3600
        val minutes = (timeAsSeconds % 3600) / 60
        // "%02d" means "format an integer (d) to be at least 2 digits wide, padding with zeros (0) if necessary."
        return "%02d:%02d".format(hours, minutes)
    }

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

    override fun timeStampToUtcDateMillis(timestamp: Long): Long =
        Instant.ofEpochMilli(timestamp).truncatedTo(ChronoUnit.DAYS).toEpochMilli()
    /*    //TODO this has a different output than the old function.
             Since that seems to be desired behaviour in the history browser,
             the functionality was refactored in getTimestampWithCurrentTimeOfDay()

    override fun timeStampToUtcDateMillis(timestamp: Long): Long {
        val current = Calendar.getInstance().apply { timeInMillis = timestamp }
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, current[Calendar.YEAR])
            set(Calendar.MONTH, current[Calendar.MONTH])
            set(Calendar.DAY_OF_MONTH, current[Calendar.DAY_OF_MONTH])
        }.timeInMillis
    }*/


    override fun getTimestampWithCurrentTimeOfDay(timestamp: Long): Long {
        val inputDate = Instant.ofEpochMilli(timestamp).atZone(systemZone).toLocalDate()
        val timeOfNow = ZonedDateTime.now(systemZone).toLocalTime()
        return inputDate.atTime(timeOfNow).atZone(systemZone).toInstant().toEpochMilli()
    }

    override fun mergeUtcDateToTimestamp(timestamp: Long, dateUtcMillis: Long): Long {
        val localTime = Instant.ofEpochMilli(timestamp).atZone(systemZone).toLocalTime()
        val utcDate = Instant.ofEpochMilli(dateUtcMillis).atZone(ZoneId.of("UTC")).toLocalDate()
        val finalDateTime = utcDate.atTime(localTime).atZone(systemZone)
        return finalDateTime.toInstant().toEpochMilli()
    }

    override fun mergeHourMinuteToTimestamp(timestamp: Long, hour: Int, minute: Int, randomSecond: Boolean): Long {
        val originalDateTime = Instant.ofEpochMilli(timestamp).atZone(systemZone)
        var updatedDateTime = originalDateTime.withHour(hour).withMinute(minute)
        if (randomSecond) updatedDateTime = updatedDateTime.withSecond(seconds++)
        return updatedDateTime.toInstant().toEpochMilli()
    }

    /**
     * Creates a date formatter based on the user's current device locale (e.g., MM/dd/yyyy for US, dd/MM/yyyy for UK).
     * @return A `DateTimeFormatter` configured for a short, localized date.
     */
    private fun getLocalizedDateFormatter(): DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(displayLocale)

    companion object {

         /** The timezone is captured each time systemZone is accessed.*/
        private val systemZone: ZoneId get() = ZoneId.systemDefault()
        /** The timezone is captured ONCE when the app starts.*/
        private val systemZoneOnce: ZoneId = ZoneId.systemDefault()
        /** The locale used for formatting strings (e.g., date formats, AM/PM) is captured each time displayLocale is accessed.*/
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
