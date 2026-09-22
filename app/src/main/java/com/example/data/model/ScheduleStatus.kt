package com.example.data.model

import com.example.util.CampusTimeUtils
import java.util.Calendar
import java.util.TimeZone

enum class ScheduleDutyState {
    ON_DUTY,
    LUNCH_BREAK,
    OFF_DUTY
}

data class ScheduleStatus(
    val isAvailable: Boolean,
    val message: String,
    val dutyState: ScheduleDutyState = ScheduleDutyState.OFF_DUTY,
    val isLunchBreak: Boolean = false,
    val isAfterHours: Boolean = false
) {
    val statusBadgeLabel: String
        get() = when (dutyState) {
            ScheduleDutyState.ON_DUTY -> "🟢 On Duty"
            ScheduleDutyState.LUNCH_BREAK -> "🟡 Lunch Break"
            ScheduleDutyState.OFF_DUTY -> "🔴 Off Duty"
        }

    companion object {
        fun getCurrentStatus(
            overrideHours: Boolean = false,
            isDriverAvailable: Boolean = false
        ): ScheduleStatus {
            if (overrideHours) {
                return ScheduleStatus(
                    isAvailable = true,
                    message = "🟢 On Duty (Testing Override Mode Active)",
                    dutyState = ScheduleDutyState.ON_DUTY
                )
            }

            val calendar = Calendar.getInstance(TimeZone.getTimeZone(CampusTimeUtils.CAMPUS_TIMEZONE_ID))
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
            val isWeekend = (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY)

            if (isWeekend) {
                return ScheduleStatus(
                    isAvailable = false,
                    message = "🔴 Off Duty (Service closed on weekends: Mon-Fri 8 AM - 6 PM)",
                    dutyState = ScheduleDutyState.OFF_DUTY,
                    isAfterHours = true
                )
            }

            val hour = calendar.get(Calendar.HOUR_OF_DAY) // 0..23 in Asia/Kolkata

            if (hour == 13) {
                return ScheduleStatus(
                    isAvailable = false,
                    message = "🟡 Lunch Break (1:00 PM - 2:00 PM). Driver unavailable.",
                    dutyState = ScheduleDutyState.LUNCH_BREAK,
                    isLunchBreak = true
                )
            }

            if (hour !in 8..17) {
                return ScheduleStatus(
                    isAvailable = false,
                    message = "🔴 Off Duty (Service closed after 6:00 PM. Hours: 8 AM - 6 PM)",
                    dutyState = ScheduleDutyState.OFF_DUTY,
                    isAfterHours = true
                )
            }

            // During operating hours (8:00 AM - 6:00 PM):
            return if (isDriverAvailable) {
                ScheduleStatus(
                    isAvailable = true,
                    message = "🟢 On Duty (Driver Active on Campus)",
                    dutyState = ScheduleDutyState.ON_DUTY
                )
            } else {
                ScheduleStatus(
                    isAvailable = false,
                    message = "🔴 Driver Not Available (Driver is outside campus or offline)",
                    dutyState = ScheduleDutyState.OFF_DUTY
                )
            }
        }
    }
}

