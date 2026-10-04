package io.github.madeye.meow.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp

/**
 * The iOS app uses SF text styles semantically rather than a custom font; the
 * Android equivalent is the system font on the same semantic scale. The mapping
 * from iOS style to M3 role:
 *
 * | iOS                        | M3 role        |
 * |----------------------------|----------------|
 * | `.title2.semibold` (hero)  | headlineSmall  |
 * | `.headline` (card titles)  | titleMedium    |
 * | `.subheadline` (rows)      | bodyMedium     |
 * | `.caption` (secondary)     | bodySmall      |
 * | `.caption2.semibold.upper` | labelSmall     |
 *
 * Every style lays text out in the direction of its first strong character,
 * like a View's default `textDirection="firstStrong"`. Compose otherwise forces
 * the UI's direction onto every paragraph, so under Persian a Latin value such
 * as `0 B/s` or a proxy named `Tokyo (IPv6)` is reordered as if it were Persian:
 * `B/s 0`, `(Tokyo (IPv6`.
 */
val MeowTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        bodyMedium = bodyMedium.copy(fontSize = 15.sp),
        bodySmall = bodySmall.copy(fontSize = 12.sp),
        labelSmall = labelSmall.copy(
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
        ),
    )
}.withTextDirection(TextDirection.Content)

private fun Typography.withTextDirection(direction: TextDirection) = copy(
    displayLarge = displayLarge.copy(textDirection = direction),
    displayMedium = displayMedium.copy(textDirection = direction),
    displaySmall = displaySmall.copy(textDirection = direction),
    headlineLarge = headlineLarge.copy(textDirection = direction),
    headlineMedium = headlineMedium.copy(textDirection = direction),
    headlineSmall = headlineSmall.copy(textDirection = direction),
    titleLarge = titleLarge.copy(textDirection = direction),
    titleMedium = titleMedium.copy(textDirection = direction),
    titleSmall = titleSmall.copy(textDirection = direction),
    bodyLarge = bodyLarge.copy(textDirection = direction),
    bodyMedium = bodyMedium.copy(textDirection = direction),
    bodySmall = bodySmall.copy(textDirection = direction),
    labelLarge = labelLarge.copy(textDirection = direction),
    labelMedium = labelMedium.copy(textDirection = direction),
    labelSmall = labelSmall.copy(textDirection = direction),
)

object MeowTextStyles {
    /**
     * Tabular figures. Byte counters, latencies and chart axes all update once a
     * second — proportional digits make them visibly jitter as glyph widths change.
     *
     * Readouts also stay left to right unless they open with a right-to-left
     * letter: `1.0.8 (1000025)` or an IP list has no letter to take a direction
     * from, and Persian would print it as `(1000025) 1.0.8`.
     */
    val monoDigits = TextStyle(fontFeatureSettings = "tnum", textDirection = TextDirection.ContentOrLtr)

    val chartLabel = TextStyle(
        fontSize = 9.sp,
        fontFeatureSettings = "tnum",
        letterSpacing = 0.5.sp,
        textDirection = TextDirection.Ltr,
    )
}
