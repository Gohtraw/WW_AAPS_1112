package app.aaps.shared.impl.utils

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.text.format.DateFormat
import android.util.DisplayMetrics
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.mockStatic
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/**
 * Unit tests for [DateUtilImpl].
 *
 * This class validates that the refactored `DateUtilImpl` is both correct and
 * produces output identical to the legacy `DateUtilOldImpl`. It uses a fixed
 * timezone and locale to ensure tests are repeatable.
 */
@ExtendWith(MockitoExtension::class) // JUNIT 5 STYLE
class DateUtilImplVsOIdTest {

    @Mock
    private lateinit var mockContext: Context

    /** The refactored class*/
    private lateinit var dateUtilImpl: DateUtilImpl
    /** A persistent mock for the static DateFormat class*/
//    private lateinit var mockedDateFormat: MockedStatic<DateFormat>
    /** The old class to compare against*/
    private lateinit var dateUtilOldImpl: DateUtilOldImpl

    @BeforeEach
    fun setUp() {

        // Set a fixed default timezone and locale to make tests deterministic.
        // This is necessary for both java.time and the legacy Joda-Time library.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
//        DateTimeZone.setDefault(DateTimeZone.forID("America/New_York"))
        Locale.setDefault(Locale.US)

        // Instantiate the classes under test
        dateUtilImpl = DateUtilImpl(mockContext)
        dateUtilOldImpl = DateUtilOldImpl(mockContext)

        // Set up the static mock AFTER instantiation
//        mockedDateFormat = mockStatic(DateFormat::class.java)
//        mockedDateFormat.`when`<Boolean> { DateFormat.is24HourFormat(mockContext) }.thenReturn(false)
    }

    @AfterEach
    fun tearDown() {
        // Closing the static mock after each test
        //       mockedDateFormat.close()
    }

    //region ISO and Timestamp Conversion Tests
    @Test
    fun `fromISODateString works for full UTC timestamp and matches old behavior`() {
        val isoString = "2023-10-26T09:07:03.344Z"
        val expectedMillis = 1698311223344L

        val newResult = dateUtilImpl.fromISODateString(isoString)
        val oldResult = dateUtilOldImpl.fromISODateString(isoString)

        assertThat(newResult).isEqualTo(expectedMillis)
        assertThat(newResult).isEqualTo(oldResult)
    }

    @Test
    fun `toISOString works and matches old behavior`() {
        val millis = 1698311223344L
        val expectedString = "2023-10-26T09:07:03.344Z"

        val newResult = dateUtilImpl.toISOString(millis)
        val oldResult = dateUtilOldImpl.toISOString(millis)

        assertThat(newResult).isEqualTo(expectedString)
        assertThat(newResult).isEqualTo(oldResult)
    }

    @Test
    fun `toISONoZone works and matches old behavior`() {
        val millis = 1698311223344L // This is 10:00 AM UTC
        val expectedString = "2023-10-26T05:07:03" // 05:07 AM in New York (EDT)

        val newResult = dateUtilImpl.toISONoZone(millis)
        val oldResult = dateUtilOldImpl.toISONoZone(millis)

        assertThat(newResult).isEqualTo(expectedString)
        assertThat(newResult).isEqualTo(oldResult)
    }

    @Test
    fun `dateString works and matches old behavior`() {
        val millis = 1698393600000L
        val expected = "10/27/23"

        val newResult = dateUtilImpl.dateString(millis)
        val oldResult = dateUtilOldImpl.dateString(millis)

        assertThat(newResult).isEqualTo(expected)
        assertThat(newResult).isEqualTo(oldResult)
    }

    @Test
    fun `timeString works for 12-hour format`() {
        val millis = 1698436800000L // 10:00 PM EDT
        val expected = "04:00 PM"

        val newResult = dateUtilImpl.timeString(millis)
        val oldResult = dateUtilOldImpl.timeString(millis)

        assertThat(newResult).isEqualTo(expected)
        assertThat(newResult).isEqualTo(oldResult)
    }

    @Test
    fun `timeString works for 24-hour format`() {
        // Use a self-closing block to mock the static method only for this test.
        mockStatic(DateFormat::class.java).use { mockedStatic ->
            // Arrange: Configure the mock to return 'true'.
            mockedStatic.`when`<Boolean> { DateFormat.is24HourFormat(mockContext) }.thenReturn(true)

            // The values for the test
            val millis = 1698458400000L // 10:00 PM EDT, which is 22:00
            val expected = "22:00"

            // Act: Call the function under test
            val newResult = dateUtilImpl.timeString(millis)
            val oldResult = dateUtilOldImpl.timeString(millis)

            // Assert: Check if the output is correct
            assertThat(newResult).isEqualTo(expected)
            assertThat(newResult).isEqualTo(oldResult)
        }
    }

   @Test
    fun `toSeconds works and matches old behavior`() {
        val timeString = "2:30 PM"
        val expectedSeconds = (14 * 3600) + (30 * 60)

        val newResult = dateUtilImpl.toSeconds(timeString)
        val oldResult = dateUtilOldImpl.toSeconds(timeString)

        assertThat(newResult).isEqualTo(expectedSeconds)
        assertThat(newResult).isEqualTo(oldResult)
    }

    @Test
    fun `beginOfDay works and matches old behavior`() {
        val millis = 1698455400000L // 2023-10-27 10:30 PM EDT
        val expectedMillis = 1698379200000L // 2023-10-27 00:00:00 EDT

        val newResult = dateUtilImpl.beginOfDay(millis)
        val oldResult = dateUtilOldImpl.beginOfDay(millis)

        assertThat(newResult).isEqualTo(expectedMillis)
        assertThat(newResult).isEqualTo(oldResult)
    }

    @Test
    fun `age works for duration over a day and matches old behavior`() {
        val millis = 95400 * 1000L // 1 day, 2 hours, 30 minutes
        val rh = FakeResourceHelper()
        val expected = "1 d 2 h "

        val newResult = dateUtilImpl.age(millis, true, rh)
        val oldResult = dateUtilOldImpl.age(millis, true, rh)

        assertThat(newResult).isEqualTo(expected)
        assertThat(newResult).isEqualTo(oldResult)
        // TODO: Check if trailing whitespaces are needed
        // expected                           : 1 d 2 h
        // but was missing trailing whitespace: ␣
    }

    @Test
    fun `age works for duration less than a day and matches old behavior`() {
        val millis = 12600 * 1000L // 3 hours, 30 minutes
        val rh = FakeResourceHelper()
        val expected = "3 h 30 m "

        val newResult = dateUtilImpl.age(millis, true, rh)
        val oldResult = dateUtilOldImpl.age(millis, true, rh)

        assertThat(newResult).isEqualTo(expected)
        assertThat(newResult).isEqualTo(oldResult)
    }

    @Test
    fun `getTimeZoneOffsetMinutes works and matches old behavior`() {
        val summerTimestamp = Instant.parse("2023-07-15T12:00:00Z").toEpochMilli()
        val expectedMinutes = -4 * 60 // EDT is -4 hours

        val newResult = dateUtilImpl.getTimeZoneOffsetMinutes(summerTimestamp)
        val oldResult = dateUtilOldImpl.getTimeZoneOffsetMinutes(summerTimestamp)

        assertThat(newResult).isEqualTo(expectedMinutes)
        assertThat(newResult).isEqualTo(oldResult)
    }

    @Test
    fun `timeString correctly formats time during non-existent DST spring forward gap`() {
        // On March 12, 2023, in New York, 2:30 AM does not exist.
        // A timestamp that would fall into this gap is automatically moved forward.
        // 06:30:00 UTC corresponds to the non-existent 02:30:00 EST.
        // java.time will represent this as 03:30:00 EDT.
        val nonExistentTimeMillis = 1678602600000L
        val expected = "3:30 AM"

        mockStatic(DateFormat::class.java).use { mockedStatic ->
            mockedStatic.`when`<Boolean> { DateFormat.is24HourFormat(mockContext) }.thenReturn(false)
            val newResult = dateUtilImpl.timeString(nonExistentTimeMillis)
            assertThat(newResult).isEqualTo(expected)
        }
    }

    @Test
    fun `timeString correctly formats ambiguous time during DST fall back`() {
        // On Nov 5, 2023, in New York, 1:30 AM happens twice.
        val timeBeforeFallback = 1699162200000L // This is 1:30 AM EDT
        val timeAfterFallback = 1699165800000L  // This is 1:30 AM EST (one hour later)

        mockStatic(DateFormat::class.java).use { mockedStatic ->
            mockedStatic.`when`<Boolean> { DateFormat.is24HourFormat(mockContext) }.thenReturn(false)

            // Both should appear as "1:30 AM" to the user, even though they are an hour apart.
            val resultBefore = dateUtilImpl.timeString(timeBeforeFallback)
            val resultAfter = dateUtilImpl.timeString(timeAfterFallback)

            assertThat(resultBefore).isEqualTo("1:30 AM")
            assertThat(resultAfter).isEqualTo("1:30 AM")
        }
    }

    @Test
    fun `age calculation is correct across DST spring forward`() {
        // A duration that starts just before the DST gap and ends just after.
        // In New York on March 12, 2023, time jumps from 1:59:59 EST to 3:00:00 EDT.
        // The duration between 1:00 AM EST and 3:00 AM EDT is only ONE hour, not two.
        val startTime = 1678599600000L // 2023-03-12 01:00:00 EST
        val endTime = 1678606800000L   // 2023-03-12 03:00:00 EDT
        val durationMillis = endTime - startTime

        val rh = FakeResourceHelper()
        val expected = "1 h 0 m" // It should be 1 hour, not 2
        val newResult = dateUtilImpl.age(durationMillis, true, rh)
        assertThat(newResult).isEqualTo(expected)
    }

    @Test
    fun `getTimeZoneOffsetMinutes reports correct offset for standard and daylight time`() {
        // A time in the winter (Standard Time, EST, UTC-5)
        val winterTimestamp = Instant.parse("2023-01-15T12:00:00Z").toEpochMilli()
        val expectedWinterMinutes = -5 * 60

        // A time in the summer (Daylight Time, EDT, UTC-4)
        val summerTimestamp = Instant.parse("2023-07-15T12:00:00Z").toEpochMilli()
        val expectedSummerMinutes = -4 * 60

        val winterResult = dateUtilImpl.getTimeZoneOffsetMinutes(winterTimestamp)
        val summerResult = dateUtilImpl.getTimeZoneOffsetMinutes(summerTimestamp)

        assertThat(winterResult).isEqualTo(expectedWinterMinutes)
        assertThat(summerResult).isEqualTo(expectedSummerMinutes)
    }

    @Test
    fun `age calculation is correct across DST fall back`() {
        // In New York on Nov 5, 2023, the clock goes from 1:59 EDT to 1:00 EST.
        // The duration between 1:00 AM EDT and 2:00 AM EST is TWO hours.
        val startTime = 1699160400000L // 2023-11-05 01:00:00 EDT
        val endTime = 1699171200000L   // 2023-11-05 02:00:00 EST
        val durationMillis = endTime - startTime

        val rh = FakeResourceHelper()
        val expected = "2 h 0 m " // It should be 2 hours
        val newResult = dateUtilImpl.age(durationMillis, true, rh)
        assertThat(newResult).isEqualTo(expected)
    }

    @Test
    fun `isSameDay is false for times just under 24 hours apart across DST`() {
        // Spring forward day (March 12) is only 23 hours long.
        val startOfDay = 1678597200000L // Mar 12, 00:00:00 EST
        val endOfDay = startOfDay + (23 * 60 * 60 * 1000) - 1 // 22:59:59 from start

        val isSame = dateUtilImpl.isSameDay(startOfDay, endOfDay)
        val isDifferent = dateUtilImpl.isSameDay(startOfDay, endOfDay + 1) // Now on the next day

        assertThat(isSame).isTrue()
        assertThat(isDifferent).isFalse()
    }

    @Test
    fun `beginOfDay works correctly on DST fall back day`() {
        // On Nov 5, the day is 25 hours long, but beginOfDay should still find the start.
        val timeDuringFallback = 1699203600000L // 2023-11-05 12:00:00 EST
        val expectedStartOfDay = 1699156800000L // 2023-11-05 00:00:00 EDT

        val newResult = dateUtilImpl.beginOfDay(timeDuringFallback)
        assertThat(newResult).isEqualTo(expectedStartOfDay)
    }

    @Test
    fun `getTimeZoneOffsetMinutes works for European DST`() {
        // Temporarily set the timezone to a European one for this test
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            // Re-instantiate the class to make it pick up the new default zone
            val localDateUtil = DateUtilImpl(mockContext)

            // A time in winter (Standard Time, CET, UTC+1)
            val winterTimestamp = Instant.parse("2023-01-15T12:00:00Z").toEpochMilli()
            val expectedWinterMinutes = 60
            // A time in summer (Daylight Time, CEST, UTC+2)
            val summerTimestamp = Instant.parse("2023-07-15T12:00:00Z").toEpochMilli()
            val expectedSummerMinutes = 120

            val winterResult = localDateUtil.getTimeZoneOffsetMinutes(winterTimestamp)
            val summerResult = localDateUtil.getTimeZoneOffsetMinutes(summerTimestamp)

            assertThat(winterResult).isEqualTo(expectedWinterMinutes)
            assertThat(summerResult).isEqualTo(expectedSummerMinutes)
        } finally {
            // IMPORTANT: Reset the timezone to not affect other tests.
            TimeZone.setDefault(originalZone)
        }
    }

    /**
     * A fake implementation of ResourceHelper for unit testing.
     * This class implements the full ResourceHelper interface, providing predictable values.
     */
    class FakeResourceHelper : app.aaps.core.interfaces.resources.ResourceHelper {

        // --- Methods genuinely used by DateUtil tests ---
        override fun gs(id: Int): String = getStringForId(id)
        override fun gs(id: Int, vararg args: Any?): String = getStringForId(id)
        private fun getStringForId(id: Int): String {
            return when (id) {
                app.aaps.core.interfaces.R.string.shortday     -> "d"
                app.aaps.core.interfaces.R.string.shorthour    -> "h"
                app.aaps.core.interfaces.R.string.shortminute  -> "m"
                app.aaps.core.interfaces.R.string.unit_minutes -> "m"
                app.aaps.core.interfaces.R.string.days         -> "days"
                app.aaps.core.interfaces.R.string.hours        -> "hours"
                app.aaps.core.interfaces.R.string.today        -> "Today"
                app.aaps.core.interfaces.R.string.yesterday    -> "Yesterday"
                app.aaps.core.interfaces.R.string.tomorrow     -> "Tomorrow"
                app.aaps.core.interfaces.R.string.later_today  -> "Later today"
                else                                           -> "" // Default for unhandled resources
            }
        }

        // --- Dummy implementations for the rest of the interface ---
        override fun gq(id: Int, quantity: Int, vararg args: Any?): String = ""
        override fun gsNotLocalised(id: Int, vararg args: Any?): String = ""
        override fun gc(id: Int): Int = 0
        override fun gd(id: Int): Drawable? = null
        override fun gb(id: Int): Boolean = false
        override fun gcs(id: Int): String = ""
        override fun gsa(id: Int): Array<String> = emptyArray()
        override fun openRawResourceFd(id: Int): AssetFileDescriptor? = null
        override fun decodeResource(id: Int): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        override fun getDisplayMetrics(): DisplayMetrics = DisplayMetrics()
        override fun dpToPx(dp: Int): Int = dp
        override fun dpToPx(dp: Float): Int = dp.toInt()
        override fun shortTextMode(): Boolean = true
        override fun gac(attributeId: Int): Int = 0
        override fun gac(context: Context?, attributeId: Int): Int = 0
        override fun getThemedCtx(context: Context): Context = context
    }
}
