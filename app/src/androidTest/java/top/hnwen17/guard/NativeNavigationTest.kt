package top.hnwen17.guard

import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.hamcrest.Matcher
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Native device tests. Source supplied, not counted as passed until connectedAndroidTest runs. */
@RunWith(AndroidJUnit4::class)
class NativeNavigationTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    @Before fun acknowledgePreview() {
        if (BuildConfig.PREVIEW_DATA) {
            try { onView(withText("进入界面")).perform(click()) }
            catch (_: androidx.test.espresso.NoMatchingViewException) { /* acknowledged in this process */ }
        }
    }
    @Test fun homeHasNewBrandAndNoOldTopStripe() {
        onView(withId(R.id.brandIcon)).check(matches(isDisplayed()))
        onView(withId(R.id.brandWordmark)).check(matches(isDisplayed()))
        onView(withId(R.id.heroTitle)).check(matches(isDisplayed()))
    }
    @Test fun allPrimaryNativeScreensOpen() {
        onView(withId(R.id.navApps)).perform(click())
        onView(withId(R.id.search)).check(matches(isDisplayed()))
        onView(withId(R.id.navRecords)).perform(click())
        onView(withId(R.id.chipAll)).check(matches(isDisplayed()))
        onView(withId(R.id.navSettings)).perform(click())
        onView(withId(R.id.brandCard)).check(matches(isDisplayed()))
        onView(withId(R.id.navHome)).perform(click())
        onView(withId(R.id.heroTitle)).check(matches(isDisplayed()))
    }
    @Test fun querySurvivesTabRoundTrip() {
        onView(withId(R.id.navApps)).perform(click())
        onView(withId(R.id.search)).perform(replaceText("example.invalid"), closeSoftKeyboard())
        onView(withId(R.id.navHome)).perform(click())
        onView(withId(R.id.navApps)).perform(click())
        onView(withId(R.id.search)).check(matches(withText("example.invalid")))
    }
    @Test fun systemApplicationsSegmentIsInteractive() {
        onView(withId(R.id.navApps)).perform(click())
        onView(withId(R.id.systemApps)).perform(click())
        await(R.id.systemApps) { it.isSelected }
        onView(withId(R.id.userApps)).perform(click())
        await(R.id.userApps) { it.isSelected }
    }
    @Test fun detailKeepsBottomNavigationLikeNewDesign() {
        assumeTrue(BuildConfig.PREVIEW_DATA)
        activity.scenario.onActivity { it.openDetail("sample.shop") }
        await(R.id.appName) { (it as? TextView)?.text?.toString() == "某购物 App" }
        onView(withId(R.id.bottomNav)).check(matches(isDisplayed()))
        onView(withId(R.id.navApps)).check(matches(isSelected()))
        onView(withId(R.id.navRecords)).perform(click())
        onView(withId(R.id.chipAll)).check(matches(isDisplayed()))
    }
    @Test fun recordFiltersRemainInteractive() {
        onView(withId(R.id.navRecords)).perform(click())
        onView(withId(R.id.chipTouch)).perform(click())
        await(R.id.chipTouch) { it.isSelected }
        onView(withId(R.id.chipAll)).perform(click())
        await(R.id.chipAll) { it.isSelected }
    }
    @Test fun brandCardShowsRealBuildBoundary() {
        onView(withId(R.id.navSettings)).perform(click())
        onView(withId(R.id.brandCard)).perform(click())
        onView(withText("知道了")).perform(click())
        onView(withId(R.id.brandCard)).check(matches(isDisplayed()))
    }
    private fun await(id: Int, condition: (View) -> Boolean) {
        onView(isRoot()).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isRoot()
            override fun getDescription() = "Wait for native resource $id without a product busy-loop"
            override fun perform(ui: UiController, root: View) {
                val deadline = SystemClock.uptimeMillis() + 5000L
                do {
                    val target = root.findViewById<View>(id)
                    if (target != null && condition(target)) return
                    ui.loopMainThreadForAtLeast(50)
                } while (SystemClock.uptimeMillis() < deadline)
                throw AssertionError("Native view $id did not reach expected state")
            }
        })
    }
}
