/*
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.openhab.habdroid.model

import android.graphics.Color
import android.location.Location
import android.os.Parcelable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.IllegalFormatException
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.roundToInt
import kotlinx.parcelize.Parcelize
import org.openhab.habdroid.util.asColorTemperatureToKelvin

@Parcelize
@ConsistentCopyVisibility
data class HsvState internal constructor(val hue: Float, val saturation: Float, val value: Float) : Parcelable {
    fun toColor(includeValue: Boolean = true): Int =
        Color.HSVToColor(floatArrayOf(hue, saturation, if (includeValue) value else 100F))
}

@Parcelize
@ConsistentCopyVisibility
data class ParsedState internal constructor(
    val asString: String,
    val asBoolean: Boolean,
    val asNumber: NumberState?,
    val asHsv: HsvState?,
    val asBrightness: Int?,
    val asLocation: Location?,
    val asDateTime: LocalDateTime?
) : Parcelable {
    override fun equals(other: Any?) = other is ParsedState && asString == other.asString

    override fun hashCode() = asString.hashCode()

    companion object {
        internal fun parseAsBoolean(state: String): Boolean {
            // If state is ON for switches return True
            if (state == "ON") {
                return true
            }

            val floatValue = state.toFloatOrNull()
            if (floatValue != null) {
                return floatValue > 0
            }

            val brightness = parseAsBrightness(state)
            if (brightness != null) {
                return brightness != 0
            }

            return false
        }

        internal fun parseAsNumber(state: String, format: String?): NumberState? {
            return when (state) {
                "ON" -> NumberState(100F)
                "OFF" -> NumberState(0F)
                else -> {
                    val spacePos = state.indexOf(' ')
                    val number = if (spacePos >= 0) state.substring(0, spacePos) else state
                    val unit = if (spacePos >= 0) state.substring(spacePos + 1) else null
                    try {
                        return NumberState(number.toFloat(), unit, format)
                    } catch (e: NumberFormatException) {
                        // fall through
                    }

                    val brightness = parseAsBrightness(state)
                    if (brightness != null) {
                        return NumberState(brightness.toFloat())
                    }

                    return null
                }
            }
        }

        internal fun parseAsHsv(state: String): HsvState? {
            val stateSplit = state.split(",")
            if (stateSplit.size == 3) { // We need exactly 3 numbers to operate this
                try {
                    return HsvState(
                        stateSplit[0].toFloat(),
                        stateSplit[1].toFloat() / 100,
                        stateSplit[2].toFloat() / 100
                    )
                } catch (e: NumberFormatException) {
                    // fall through
                }
            }
            return null
        }

        internal fun parseAsLocation(state: String): Location? {
            val splitState = state.split(",")
            // Valid states are either "latitude,longitude" or "latitude,longitude,elevation",
            if (splitState.size == 2 || splitState.size == 3) {
                try {
                    val l = Location("openhab")
                    l.latitude = splitState[0].toDouble()
                    l.longitude = splitState[1].toDouble()
                    l.time = System.currentTimeMillis()
                    if (splitState.size == 3) {
                        l.altitude = splitState[2].toDouble()
                    }
                    // Do our best to avoid parsing e.g. HSV values into location by
                    // sanity checking the values
                    if (Math.abs(l.latitude) <= 90 && Math.abs(l.longitude) <= 180) {
                        return l
                    }
                } catch (e: NumberFormatException) {
                    // ignored
                }
            }
            return null
        }

        internal fun parseAsBrightness(state: String): Int? {
            val hsbMatcher = HSB_PATTERN.matcher(state)
            if (hsbMatcher.find()) {
                try {
                    return hsbMatcher.group(3)?.toFloat()?.roundToInt()
                } catch (e: NumberFormatException) {
                    // fall through
                }
            }
            val stateAsFloat = state.toFloatOrNull() ?: return null
            return when (stateAsFloat) {
                in 1f..100f, 0f -> {
                    stateAsFloat.toInt()
                }
                in 0f..1f -> {
                    1
                }
                else -> null
            }
        }

        private val HSB_PATTERN = Pattern.compile("^([0-9]*\\.?[0-9]+),([0-9]*\\.?[0-9]+),([0-9]*\\.?[0-9]+)$")

        internal fun parseAsDateTime(state: String): LocalDateTime? = try {
            LocalDateTime.parse(state.split(".")[0], DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    @Parcelize
    @ConsistentCopyVisibility
    data class NumberState internal constructor(
        val value: Float,
        val unit: String? = null,
        val format: String? = null
    ) : Parcelable {
        override fun toString() = toString(Locale.getDefault())

        /**
         * Like [toString][.toString], but using a specific locale for formatting.
         */
        fun toString(locale: Locale): String {
            if (!format.isNullOrEmpty()) {
                val actualFormat = format
                    .replace("%unit%", unit.orEmpty())
                    // In case of 'one' unit, the unit is part of the format pattern, but not part of the value
                    // sent by the server. Avoid ending the value with a space in that case.
                    .trim()
                try {
                    return String.format(locale, actualFormat, getActualValue())
                } catch (e: IllegalFormatException) {
                    // State format pattern doesn't match the actual data type
                    // -> ignore and fall back to our own formatting
                }
            }
            return if (unit == null) formatValue() else "${formatValue()} $unit"
        }

        fun formatValue(): String = getActualValue().toString()

        private fun getActualValue(): Number =
            if (format != null && format.contains("%d")) value.roundToInt() else value
    }
}

fun ParsedState.NumberState?.withValue(value: Float): ParsedState.NumberState =
    ParsedState.NumberState(value, this?.unit, this?.format)

fun ParsedState.NumberState.toColorTemperatureInKelvin() =
    ParsedState.NumberState(value.asColorTemperatureToKelvin(), "K", "%.0f %unit%")

/**
 * Parses a state string into the parsed representation.
 *
 * @param formatPattern Format to use when parsing the input as number or date
 * @return null if state string is null, parsed representation otherwise
 */
fun String?.toParsedState(formatPattern: String? = null): ParsedState? {
    if (this == null) {
        return null
    }
    return ParsedState(
        this,
        ParsedState.parseAsBoolean(this),
        ParsedState.parseAsNumber(this, formatPattern),
        ParsedState.parseAsHsv(this),
        ParsedState.parseAsBrightness(this),
        ParsedState.parseAsLocation(this),
        ParsedState.parseAsDateTime(this)
    )
}
