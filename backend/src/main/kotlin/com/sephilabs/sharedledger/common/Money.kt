package com.sephilabs.sharedledger.common

import java.math.BigDecimal
import java.math.RoundingMode

object Money {
    const val SCALE: Int = 2

    fun normalize(value: BigDecimal): BigDecimal = value.setScale(SCALE, RoundingMode.HALF_EVEN)

}
