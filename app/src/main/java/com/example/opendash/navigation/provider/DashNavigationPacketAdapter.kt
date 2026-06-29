package com.example.opendash.navigation.provider

import com.example.opendash.dash.DashSession
import com.example.opendash.dash.protocol.DashCommands
import java.util.Calendar

object DashNavigationPacketAdapter {
    data class PacketUpdate(
        val maneuver: Int,
        val primaryDist: Int,
        val primaryUnit: Int,
        val totalDist: Int,
        val totalUnit: Int,
        val etaHHMM: String?,
    )

    fun toPacketUpdate(progress: NavigationProgress): PacketUpdate {
        val (primaryDist, primaryUnit) = toDashDistance(progress.distanceToNextManeuverMeters)
        val (totalDist, totalUnit) = toDashDistance(progress.remainingDistanceMeters)
        val maneuverCode = progress.nextManeuver
            ?.let { MapboxManeuverMapper.toDashManeuverCode(it.type) }
            ?: DashCommands.NAV_MANEUVER_CONTINUE
        val eta = progress.etaEpochMillis.takeIf { it > 0L }?.let { etaMillis ->
            Calendar.getInstance().apply { timeInMillis = etaMillis }.let {
                "%02d%02d".format(
                    it.get(Calendar.HOUR_OF_DAY),
                    it.get(Calendar.MINUTE),
                )
            }
        }
        return PacketUpdate(
            maneuver = maneuverCode,
            primaryDist = primaryDist,
            primaryUnit = primaryUnit,
            totalDist = totalDist,
            totalUnit = totalUnit,
            etaHHMM = eta,
        )
    }

    fun applyToDash(session: DashSession, progress: NavigationProgress) {
        val update = toPacketUpdate(progress)
        session.updateNavInfo(
            maneuver = update.maneuver,
            primaryDist = update.primaryDist,
            primaryUnit = update.primaryUnit,
            totalDist = update.totalDist,
            totalUnit = update.totalUnit,
            etaHHMM = update.etaHHMM,
        )
    }

    private fun toDashDistance(meters: Double): Pair<Int, Int> =
        if (meters < 1000.0) {
            meters.toInt().coerceIn(0, 0xFFFF) to DashCommands.NAV_UNIT_METERS
        } else {
            (meters / 100.0).toInt().coerceIn(0, 0xFFFF) to DashCommands.NAV_UNIT_KM_TENTHS
        }
}
