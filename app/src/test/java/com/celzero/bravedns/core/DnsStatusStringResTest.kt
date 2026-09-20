package com.celzero.bravedns.core

import com.celzero.bravedns.R
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.util.UIUtils
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the Home DNS headline label mapping.
 *
 * Background: the Home headline rendered "Failing" for a null resolver
 * status, i.e. when the resolver had simply not sampled a transaction for
 * the queried id yet (idle resolver or transient flap). On Poco with a
 * SOCKS exit, a WAF-tarpit flap left the headline stuck on "Failing" long
 * after the tunnel recovered, because a null poll is not evidence of
 * failure and must not extend the lifetime of an earlier error status.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DnsStatusStringResTest {
    @Test
    fun `null status is not a failure`() {
        // A null status means "no data yet" -- it must never map to the
        // failing label; otherwise a healthy idle resolver shows as broken.
        assertEquals(R.string.lbl_starting, UIUtils.getDnsStatusStringRes(null))
    }

    @Test
    fun `start status maps to starting`() {
        assertEquals(R.string.lbl_starting, UIUtils.getDnsStatusStringRes(Transaction.Status.START.id))
    }

    @Test
    fun `complete status is connected`() {
        assertEquals(R.string.dns_connected, UIUtils.getDnsStatusStringRes(Transaction.Status.COMPLETE.id))
    }

    @Test
    fun `internal error is failing`() {
        assertEquals(R.string.status_failing, UIUtils.getDnsStatusStringRes(Transaction.Status.INTERNAL_ERROR.id))
    }

    @Test
    fun `transport error is dns server down`() {
        assertEquals(
            R.string.status_dns_server_down,
            UIUtils.getDnsStatusStringRes(Transaction.Status.TRANSPORT_ERROR.id)
        )
    }

    @Test
    fun `unknown status id defaults to failing`() {
        // Backend ids are go-generic ints; an unmapped id must still be
        // surfaced as a failure rather than silently downgraded.
        assertEquals(
            R.string.status_failing,
            UIUtils.getDnsStatusStringRes(Transaction.Status.fromId(99_999).id)
        )
    }
}