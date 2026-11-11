package com.callflow.ai

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** EMI maths. rate = percent per year, n = number of months. */
object Emi {
    fun emi(p: Double, rate: Double, n: Double): Double {
        val r = rate / 1200
        return if (r == 0.0) p / n else p * r * (1 + r).pow(n) / ((1 + r).pow(n) - 1)
    }
    fun principal(e: Double, rate: Double, n: Double): Double {
        val r = rate / 1200
        return if (r == 0.0) e * n else e * ((1 + r).pow(n) - 1) / (r * (1 + r).pow(n))
    }
    fun months(p: Double, e: Double, rate: Double): Double? {
        val r = rate / 1200
        if (r == 0.0) return p / e
        if (e <= p * r) return null
        return ln(e / (e - p * r)) / ln(1 + r)
    }
    /** One row per month: [principal, interest, balance, payment]. Interest is charged on the reducing balance. */
    fun schedule(p: Double, rate: Double, emi: Double, n: Int): List<DoubleArray> {
        val r = rate / 1200
        var bal = p
        val out = ArrayList<DoubleArray>()
        for (m in 1..n) {
            val interest = bal * r
            val principal = if (m == n) bal else minOf(emi - interest, bal)
            bal = if (m == n) 0.0 else bal - principal
            out.add(doubleArrayOf(principal, interest, bal, principal + interest))
        }
        return out
    }
    fun rate(p: Double, e: Double, n: Double): Double? {
        if (e * n < p) return null
        if (abs(e * n - p) < 1e-9) return 0.0
        var lo = 0.0
        var hi = 1.0
        repeat(100) {
            val mid = (lo + hi) / 2
            if (emi(p, mid * 1200, n) < e) lo = mid else hi = mid
        }
        return (lo + hi) / 2 * 1200
    }
}
