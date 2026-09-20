package top.hnwen17.guard.core

import kotlin.test.Test

/** Executes the existing 46 assertions through Gradle instead of leaving them outside CI. */
class CoreRegressionTest {
    @Test fun originalStateAndFilteringRegression() {
        top.hnwen17.guard.tests.main()
    }
}
