package app.aaps.plugins.aps.openAPS

import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.RT
import dagger.Reusable
import java.text.DecimalFormat
import javax.inject.Inject

// private file-level extension function, scoped to this file
private fun Double.withoutZeros(): String = DecimalFormat("0.##").format(this)

@Reusable
class CgmDataGuard @Inject constructor() {

    /**
     * Checks for critical CGM data issues. If an issue is found, it populates the
     * RT object with a reason and the appropriate corrective temp basal action.
     *
     * @return `true` if a critical issue was found, indicating the main algorithm
     *         should return immediately. `false` otherwise.
     */
    fun checkDataAndAdjustTempBasal(
        glucoseStatus: GlucoseStatus,
        minAgo: Double,
        flatBGsDetected: Boolean,
        currenttemp: CurrentTemp,
        basal: Double,
        rT: RT,
        dBH: DetermineBasalHelper
    ): Boolean {
        val isLowOrErrorBG = glucoseStatus.glucose <= 10 || glucoseStatus.glucose == 38.0
        val isNoiseHigh = glucoseStatus.noise >= 3
        val isDataStaleOrFuture = minAgo > 12 || minAgo < -5
        val isDataFlatAndConcerning = glucoseStatus.glucose > 60 && flatBGsDetected

        // Check if any of the critical conditions are met
        if (!isLowOrErrorBG && !isNoiseHigh && !isDataStaleOrFuture && !isDataFlatAndConcerning) {
            return false // No critical CGM issue triggered an action/early return
        }
        // Append all relevant reasons
        if (isLowOrErrorBG || isNoiseHigh) { dBH.appendReason(rT, "CGM is calibrating, in ??? state, or noise is high (${glucoseStatus.noise}). ") }
        if (isDataStaleOrFuture) { dBH.appendReason(rT, "BG data is stale or from the future (${minAgo}m). ") }
        if (isDataFlatAndConcerning) { dBH.appendReason(rT, "Error: CGM data is unchanged for the past ~45m. ") }


        // Decide on the action
        if (currenttemp.rate > basal) {
            dBH.appendReason(rT, "Reverting high temp basal of ${currenttemp.rate.withoutZeros()}U/hr to neutral basal rate of ${basal.withoutZeros()}U/hr. ")
            rT.duration = 30
            rT.rate = basal
        } else if (currenttemp.rate == 0.0 && currenttemp.duration > 30) {
            dBH.appendReason(rT, "Shortening ${currenttemp.duration}m long zero temp to 30m. ")
            rT.duration = 30
            rT.rate = 0.0
        } else {
            dBH.appendReason(rT, "Temp ${currenttemp.rate.withoutZeros()}U/hr (${currenttemp.duration}m) is not a high temp and not a long zero temp. ")
            // Even if no action is taken, we still want the algorithm to stop,
            // so we return true after adding the reason.
        }
        return true // Signal to return rT
    }
}