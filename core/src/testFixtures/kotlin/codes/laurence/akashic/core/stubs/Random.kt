package codes.laurence.akashic.core.stubs

import kotlin.math.absoluteValue
import kotlin.random.Random

fun randString(): String = "GENERATED${Random.nextInt().absoluteValue}"

fun randInt(): Int = Random.nextInt(0, Int.MAX_VALUE)
