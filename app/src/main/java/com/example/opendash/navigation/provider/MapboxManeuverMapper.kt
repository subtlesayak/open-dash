package com.example.opendash.navigation.provider

import com.example.opendash.dash.protocol.DashCommands
import java.util.Locale

object MapboxManeuverMapper {
    fun toDashType(type: String?, modifier: String?): DashManeuverType {
        val t = type.normalized()
        val m = modifier.normalized()
        return when (t) {
            "depart" -> DashManeuverType.DEPART
            "arrive" -> DashManeuverType.ARRIVE
            "roundabout", "rotary" -> DashManeuverType.ROUNDABOUT
            "merge", "on ramp", "off ramp" -> DashManeuverType.MERGE
            "fork" -> DashManeuverType.FORK
            "turn", "end of road", "new name", "continue" -> when (m) {
                "left" -> DashManeuverType.TURN_LEFT
                "right" -> DashManeuverType.TURN_RIGHT
                "slight left" -> DashManeuverType.SLIGHT_LEFT
                "slight right" -> DashManeuverType.SLIGHT_RIGHT
                "sharp left" -> DashManeuverType.SHARP_LEFT
                "sharp right" -> DashManeuverType.SHARP_RIGHT
                "uturn" -> DashManeuverType.UTURN
                "straight" -> DashManeuverType.STRAIGHT
                else -> DashManeuverType.STRAIGHT
            }
            else -> DashManeuverType.UNKNOWN
        }
    }

    fun toDashManeuverCode(type: DashManeuverType): Int =
        when (type) {
            // Only CONTINUE is verified on the current dash firmware. Keep the packet layer
            // conservative while the experimental provider validates icon codes in the field.
            else -> DashCommands.NAV_MANEUVER_CONTINUE
        }

    private fun String?.normalized(): String =
        this?.trim()?.lowercase(Locale.US).orEmpty()
}
