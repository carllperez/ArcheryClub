package ph.capstone.archeryclub.prototype

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** Frontend-only rules; these tests never connect to Supabase. */
class OfficerDemoStoreTest {
    private val day = LocalDate.of(2026, 10, 19)

    @Test fun rejectsIncompatibleEquipment() {
        val state = OfficerDemoStore()
        assertFalse(state.requestBorrow("Archer", Hand.LEFT, "EQ-001", day, day).accepted)
        assertFalse(state.requestBorrow("Archer", Hand.LEFT, "EQ-004", day, day).accepted)
    }

    @Test fun blocksDoubleBookingAtApproval() {
        val state = OfficerDemoStore()
        assertTrue(state.requestBorrow("First Archer", Hand.RIGHT, "EQ-001", day, day).accepted)
        assertTrue(state.requestBorrow("Second Archer", Hand.RIGHT, "EQ-001", day, day).accepted)
        val first = state.borrowings.find { it.member == "First Archer" }!!
        val second = state.borrowings.find { it.member == "Second Archer" }!!
        assertTrue(state.reviewBorrow(first.id, true).accepted)
        assertFalse(state.reviewBorrow(second.id, true).accepted)
        assertEquals(BorrowStatus.PENDING, second.status)
        assertTrue(state.returnBorrow(first.id).accepted)
        assertTrue(state.reviewBorrow(second.id, true).accepted)
    }

    @Test fun blocksSimultaneousReservationForExistingApprovedGear() {
        val state = OfficerDemoStore()
        assertFalse(state.requestBorrow("Archer", Hand.LEFT, "EQ-002", LocalDate.of(2026,10,18), LocalDate.of(2026,10,19)).accepted)
        assertTrue(state.requestBorrow("Archer", Hand.LEFT, "EQ-002", day, day).accepted)
        assertFalse(state.changeCondition("EQ-002", GearCondition.MAINTENANCE).accepted)
    }

    @Test fun processingReviewChangesMembershipOnlyWhenApproved() {
        val state = OfficerDemoStore()
        assertTrue(state.reviewApplication("AP-001", true).accepted)
        assertEquals("Trainee", state.memberships.find { it.person == "Jamie Santos" }?.category)
        assertFalse(state.reviewApplication("AP-001", true).accepted)
        assertTrue(state.reviewApplication("AP-003", false).accepted)
        assertNull(state.memberships.find { it.person == "Taylor Lim" })
    }

    @Test fun ballotIsOneTimeAndRequiresEachPosition() {
        val state = OfficerDemoStore()
        assertFalse(state.castBallot(mapOf("President" to "C-01")).accepted)
        assertTrue(state.castBallot(mapOf("President" to "C-01", "Vice President" to "C-03")).accepted)
        assertFalse(state.castBallot(mapOf("President" to "C-02", "Vice President" to "C-04")).accepted)
        assertTrue(state.closeElection().accepted)
        assertFalse(state.nominate("Other Archer", "President").accepted)
    }

    @Test fun financialVerificationNeedsApproval() {
        val state = OfficerDemoStore()
        assertFalse(state.reviewFinance("FN-001", "verify").accepted)
        assertTrue(state.reviewFinance("FN-001", "approve").accepted)
        assertTrue(state.reviewFinance("FN-001", "verify").accepted)
        assertFalse(state.reviewFinance("FN-001", "verify").accepted)
        assertFalse(state.submitFinance("Archer", "Demo", 0.0, "proof").accepted)
    }

    @Test fun summaryReflectsSessionChanges() {
        val state = OfficerDemoStore()
        val original = state.summaryCsv()
        assertTrue(state.addGear("New practice bow", Hand.LEFT, 25).accepted)
        assertNotEquals(original, state.summaryCsv())
        assertTrue(state.summaryCsv().contains("Ready equipment"))
    }
}
