package com.expenselens

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sanity check that runs on-device. Real verification is done in unit tests
 * for the parser/classifier; this just makes sure the instrumentation context
 * is healthy.
 */
@RunWith(AndroidJUnit4::class)
class SanityCheck {
    @Test fun appLaunches() {
        // Intentionally trivial — presence of this test passes when the
        // instrumented test target boots.
    }
}
