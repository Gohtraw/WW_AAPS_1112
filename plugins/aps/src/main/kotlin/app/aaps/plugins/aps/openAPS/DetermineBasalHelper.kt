package app.aaps.plugins.aps.openAPS

import android.icu.text.DecimalFormat
//import app.aaps.plugins.aps.openAPSAutoISF.DetermineBasalAutoISF.Companion.consoleLog
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.OapsProfileAutoIsf
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.profile.ProfileUtil
import dagger.Reusable
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.pow

@Reusable
class DetermineBasalHelper @Inject constructor(
    private val profileUtil: ProfileUtil
) {
    // --- UTILITY FUNCTIONS ---

    /**
     * Rounds value to 'digits' decimal places
     * different for negative numbers fun round(value: Double, digits: Int): Double = BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
     */
    fun round(value: Double, digits: Int): Double {
        if (value.isNaN()) return Double.NaN
        val scale = 10.0.pow(digits.toDouble())
        return Math.round(value * scale) / scale
    }
    /**
     * Returns string representation of double value with two decimal places without trailing zeroes
     */
//    fun Double.withoutZeros(): String = DecimalFormat("0.##").format(this)
    fun withoutZeros(value: Double): String {
        return DecimalFormat("0.##").format(value)
    }
    /**
     * Rounds value to two decimal places with trailing zeroes as needed.
     * This is only used for logging and not for calculations.
     */
    fun toFixed2(value: Double): String {
        return DecimalFormat("0.00#").format(value)
    }
    /**
     * converts original mg/dL Double value to String representation of the units set in profile (mmol/L or mg/dL)
     */
    fun convertBg(value: Double): String =
        profileUtil.fromMgdlToStringInUnits(value).replace("-0.0", "0.0")
    /**
     * Adds reason to rT.reason and consoleLog (eventually)
     */
    fun reason(rT: RT, msg: String) {
        if (rT.reason.toString().isNotEmpty()) rT.reason.append(". ")
        rT.reason.append(msg)
        //consoleLog.add(msg) //as I wrote: eventually
    }
    /**
     * Adds reason to rT.reason only
     */
    fun appendReason(rT: RT, msg: String) {
        if (rT.reason.toString().isNotEmpty() && !rT.reason.endsWith(". ")) rT.reason.append(". ")
        rT.reason.append(msg)
    }


    // --- CORE ALGORITHM HELPERS ---

    fun calculateExpectedDelta(targetBg: Double, eventualBg: Double, bgi: Double): Double {
        // (hours * mins_per_hour) / 5 = how many 5 minute periods in 2h = 24
        val fiveMinBlocks = (2 * 60) / 5
        val targetDelta = targetBg - eventualBg
        return round(bgi + (targetDelta / fiveMinBlocks), 1)
    }

    fun getMaxSafeBasal(profile: OapsProfile): Double =
        min(profile.max_basal, min(profile.max_daily_safety_multiplier * profile.max_daily_basal, profile.current_basal_safety_multiplier * profile.current_basal))

    fun getMaxSafeBasal(profile: OapsProfileAutoIsf): Double =
        min(profile.max_basal, min(profile.max_daily_safety_multiplier * profile.max_daily_basal, profile.current_basal_safety_multiplier * profile.current_basal))

    fun setTempBasal(rate: Double, duration: Int, profile: OapsProfile, rT: RT, currenttemp: CurrentTemp): RT {
        return newTempBasal(rate, duration, profile.current_basal, profile.skip_neutral_temps, getMaxSafeBasal(profile), rT, currenttemp)
    }
    fun setTempBasal(rate: Double, duration: Int, profile: OapsProfileAutoIsf, rT: RT, currenttemp: CurrentTemp): RT {
        return newTempBasal(rate, duration, profile.current_basal, profile.skip_neutral_temps, getMaxSafeBasal(profile), rT, currenttemp)
    }
    private fun newTempBasal(rate: Double, duration: Int, currentBasal: Double, skipNeutralTemps: Boolean, maxSafeBasal: Double, rT: RT, currenttemp: CurrentTemp): RT {
        val suggestedRate = when {
            rate < 0 -> 0.0
            rate > maxSafeBasal -> maxSafeBasal
            else -> rate
        }
        if (currenttemp.duration > (duration - 10) &&
            currenttemp.duration <= 120 &&
            suggestedRate <= currenttemp.rate * 1.2 &&
            suggestedRate >= currenttemp.rate * 0.8 &&
            duration > 0) {
            rT.reason.append(" ${currenttemp.duration}m left and ${withoutZeros(currenttemp.rate)} ~ req ${withoutZeros(suggestedRate)}U/hr: no temp required")
            return rT
        }
        if (suggestedRate == currentBasal) {
            if (skipNeutralTemps) {
                if (currenttemp.duration > 0) {
                    appendReason(rT, "Suggested rate is same as profile rate, a temp basal is active, canceling current temp")
                    rT.duration = 0
                    rT.rate = 0.0
                } else {
                    appendReason(rT, "Suggested rate is same as profile rate, no temp basal is active, doing nothing")
                }
            } else {
                appendReason(rT, "Setting neutral temp basal of ${currentBasal}U/hr")
                rT.duration = duration
                rT.rate = suggestedRate
            }
        } else {
            // new temp is not neutral
            appendReason(rT, "Setting temp basal of ${withoutZeros(suggestedRate)}U/hr for $duration min")
            rT.duration = duration
            rT.rate = suggestedRate
        }
        return rT
    }
    fun enableSMB(profile: OapsProfile, microBolusAllowed: Boolean, meal_data: MealData, target_bg: Double, consoleError: MutableList<String>): Boolean {
        return enable_smb(profile.allowSMB_with_high_temptarget, profile.temptargetSet,  profile.enableSMB_always, profile.enableSMB_with_COB, profile.enableSMB_after_carbs, profile.enableSMB_with_temptarget, microBolusAllowed, meal_data, target_bg, consoleError)
    }
    fun enableSMB(profile: OapsProfileAutoIsf, microBolusAllowed: Boolean, meal_data: MealData, target_bg: Double, consoleError: MutableList<String>): Boolean {
        return enable_smb(profile.allowSMB_with_high_temptarget, profile.temptargetSet, profile.enableSMB_always, profile.enableSMB_with_COB, profile.enableSMB_after_carbs, profile.enableSMB_with_temptarget, microBolusAllowed, meal_data, target_bg, consoleError)
    }

    private fun enable_smb(
        allowSMB_with_high_temptarget: Boolean,
        temptargetSet: Boolean,
        enableSMB_always: Boolean,
        enableSMB_with_COB: Boolean,
        enableSMB_after_carbs: Boolean,
        enableSMB_with_temptarget: Boolean,
        microBolusAllowed: Boolean,
        meal_data: MealData,
        target_bg: Double,
        consoleError: MutableList<String>
    ): Boolean {
        // disable SMB when a high temptarget is set
        if (!microBolusAllowed) {
            consoleError.add("SMB disabled (!microBolusAllowed)")
            return false
        } else if (!allowSMB_with_high_temptarget && temptargetSet && target_bg > 100) {
            consoleError.add("SMB disabled due to high temptarget of $target_bg")
            return false
        }
        // enable SMB/UAM if always-on (unless previously disabled for high temptarget)
        if (enableSMB_always) {
            consoleError.add("SMB enabled due to enableSMB_always")
            return true
        }
        // enable SMB/UAM (if enabled in preferences) while we have COB
        if (enableSMB_with_COB && meal_data.mealCOB != 0.0) {
            consoleError.add("SMB enabled for COB of ${meal_data.mealCOB}")
            return true
        }
        // enable SMB/UAM (if enabled in preferences) for a full 6 hours after any carb entry
        // (6 hours is defined in carbWindow in lib/meal/total.js)
        if (enableSMB_after_carbs && meal_data.carbs != 0.0) {
            consoleError.add("SMB enabled for 6h after carb entry")
            return true
        }
        // enable SMB/UAM (if enabled in preferences) if a low temptarget is set
        if (enableSMB_with_temptarget && (temptargetSet && target_bg < 100)) {
            consoleError.add("SMB enabled for temptarget of ${convertBg(target_bg)}")
            return true
        }
        consoleError.add("SMB disabled (no enableSMB preferences active or no condition satisfied)")
        return false
    }
}