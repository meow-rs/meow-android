package io.github.madeye.meow.ui.screens.subscribe

import kotlin.random.Random

/**
 * The name a subscription is stored under: [entered] as given or, when it is
 * blank, `meow-` and six random lowercase letters, such as `meow-qhzkta`.
 *
 * Every way of adding a subscription ends here (the Add dialog, an
 * install-config link and a scanned code), and so does saving the edit
 * dialog with the name cleared, so none is stored without a name.
 */
fun subscriptionName(entered: String, random: Random = Random.Default): String =
    entered.ifBlank {
        buildString(GENERATED_PREFIX.length + GENERATED_LETTERS) {
            append(GENERATED_PREFIX)
            repeat(GENERATED_LETTERS) { append('a' + random.nextInt(26)) }
        }
    }

private const val GENERATED_PREFIX = "meow-"
private const val GENERATED_LETTERS = 6
