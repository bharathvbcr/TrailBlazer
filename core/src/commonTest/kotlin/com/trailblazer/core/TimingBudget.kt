package com.trailblazer.core

/**
 * How many times slower than an optimised JVM this test target is allowed to be before a wall-clock budget fails.
 *
 * The timing asserts in the tests are there to catch an algorithm going quadratic, not to benchmark. A Kotlin/Native
 * *debug* test binary has no inlining or optimisation and runs these loops an order of magnitude slower than the JVM,
 * so the same millisecond budget cannot hold on every target. Each platform declares its own multiplier
 * (jvmTest, androidHostTest, appleTest); the budget is still enforced everywhere, just scaled.
 */
internal expect val timingBudgetScale: Long

/** [baseMs] is the budget on the JVM; the result is the budget for the target running the test. */
internal fun budgetMs(baseMs: Long): Long = baseMs * timingBudgetScale
