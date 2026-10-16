package ph.capstone.archeryclub.prototype

import java.time.LocalDate

/**
 * Fictional, in-memory fixtures ONLY. Nothing here calls Supabase, stores credentials,
 * or writes to production tables. State is discarded when the demo activity is recreated.
 */
internal data class DemoResult(val accepted: Boolean, val message: String)
private fun success(message: String) = DemoResult(true, message)
private fun failure(message: String) = DemoResult(false, message)

internal enum class Hand { LEFT, RIGHT, AMBIDEXTROUS }
internal enum class GearCondition { READY, MAINTENANCE, RETIRED }
internal enum class BorrowStatus { PENDING, APPROVED, RETURNED, DECLINED }
internal enum class ReviewStatus { SUBMITTED, APPROVED, REJECTED }
internal enum class FinanceStatus { SUBMITTED, APPROVED, VERIFIED, REJECTED }

internal data class Gear(
    val id: String, val description: String, val hand: Hand,
    val drawWeight: Int, var condition: GearCondition = GearCondition.READY,
)
internal data class Borrowing(
    val id: String, val member: String, val hand: Hand, val gearId: String,
    val start: LocalDate, val end: LocalDate, var status: BorrowStatus,
)
internal data class Application(
    val id: String, val person: String, val kind: String, val requestedCategory: String,
    var status: ReviewStatus = ReviewStatus.SUBMITTED,
)
internal data class Membership(val person: String, val category: String)
internal data class Candidate(val id: String, val person: String, val position: String)
internal data class FinanceRecord(
    val id: String, val submitter: String, val purpose: String,
    val amount: Double, val proofReference: String, var status: FinanceStatus = FinanceStatus.SUBMITTED,
)
internal data class Goal(val label: String, val target: Int, var achieved: Int)

internal class OfficerDemoStore {
    val gear = mutableListOf(
        Gear("EQ-001", "Core Recurve 68 in", Hand.RIGHT, 24),
        Gear("EQ-002", "Club Recurve 66 in", Hand.LEFT, 22),
        Gear("EQ-003", "Training Recurve 70 in", Hand.RIGHT, 28),
        Gear("EQ-004", "Club Recurve 68 in", Hand.LEFT, 26, GearCondition.MAINTENANCE),
    )
    val borrowings = mutableListOf(
        Borrowing("BR-001", "Demo Member", Hand.LEFT, "EQ-002", LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 18), BorrowStatus.APPROVED),
        Borrowing("BR-002", "Alex Cruz", Hand.RIGHT, "EQ-001", LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 18), BorrowStatus.PENDING),
    )
    val applications = mutableListOf(
        Application("AP-001", "Jamie Santos", "New applicant", "Trainee"),
        Application("RN-002", "Sam Reyes", "Renewal", "Regular"),
        Application("AP-003", "Taylor Lim", "New applicant", "Trainee"),
    )
    val memberships = mutableListOf(Membership("Demo Member", "Regular"), Membership("Sam Reyes", "Regular"))
    val candidates = mutableListOf(
        Candidate("C-01", "Alex Cruz", "President"),
        Candidate("C-02", "Jamie Santos", "President"),
        Candidate("C-03", "Sam Reyes", "Vice President"),
        Candidate("C-04", "Taylor Lim", "Vice President"),
    )
    val positions = listOf("President", "Vice President")
    var electionOpen = true
        private set
    // Fake local ballots for ONE simulated member only. No real elections are conducted.
    val ballots = mutableMapOf<String, String>()
    val finances = mutableListOf(
        FinanceRecord("FN-001", "Demo Member", "Tournament reimbursement", 1200.0, "SIM-RECEIPT-001"),
        FinanceRecord("FN-002", "Jamie Santos", "Club equipment maintenance", 650.0, "SIM-RECEIPT-002", FinanceStatus.APPROVED),
    )
    val goals = mutableListOf(
        Goal("Attendance check-ins", 60, 38),
        Goal("Training sessions", 20, 12),
        Goal("Processed applications", 10, 6),
    )
    private var nextId = 100
    private fun id(prefix: String) = "$prefix-${nextId++}"

    fun addGear(description: String, hand: Hand, drawWeight: Int): DemoResult {
        if (description.isBlank()) return failure("Enter an equipment description.")
        if (drawWeight !in 10..70) return failure("Enter a draw weight from 10 to 70 lb.")
        gear += Gear(id("EQ"), description.trim(), hand, drawWeight)
        return success("Equipment added to this demo session.")
    }

    fun changeCondition(gearId: String, condition: GearCondition): DemoResult {
        val item = gear.find { it.id == gearId } ?: return failure("Equipment not found.")
        if (condition != GearCondition.READY && borrowings.any { it.gearId == gearId && it.status == BorrowStatus.APPROVED }) {
            return failure("Return approved equipment before changing its condition.")
        }
        item.condition = condition
        return success("Condition updated locally: ${condition.name.lowercase()}.")
    }

    private fun overlaps(aStart: LocalDate, aEnd: LocalDate, bStart: LocalDate, bEnd: LocalDate) =
        !aEnd.isBefore(bStart) && !bEnd.isBefore(aStart)

    fun conflictingApproval(gearId: String, start: LocalDate, end: LocalDate, excluding: String = ""): Boolean =
        borrowings.any { it.id != excluding && it.gearId == gearId && it.status == BorrowStatus.APPROVED && overlaps(it.start, it.end, start, end) }

    fun requestBorrow(member: String, hand: Hand, gearId: String, start: LocalDate, end: LocalDate): DemoResult {
        val item = gear.find { it.id == gearId } ?: return failure("Choose an equipment item.")
        if (member.isBlank()) return failure("Enter the borrowing member's name.")
        if (start.isAfter(end)) return failure("End date must be on or after start date.")
        if (item.condition != GearCondition.READY) return failure("This item is not ready for borrowing.")
        if (item.hand != hand && item.hand != Hand.AMBIDEXTROUS) return failure("This bow setup does not match the member's shooting hand.")
        if (conflictingApproval(gearId, start, end)) return failure("Allocation conflict: this exact item is already approved for those dates.")
        borrowings += Borrowing(id("BR"), member.trim(), hand, gearId, start, end, BorrowStatus.PENDING)
        return success("Borrowing request created locally; officer approval is still required.")
    }

    fun reviewBorrow(id: String, approve: Boolean): DemoResult {
        val row = borrowings.find { it.id == id } ?: return failure("Request not found.")
        if (row.status != BorrowStatus.PENDING) return failure("Only pending requests can be reviewed.")
        if (approve) {
            val item = gear.find { it.id == row.gearId } ?: return failure("Missing equipment record.")
            if (item.condition != GearCondition.READY) return failure("Equipment is not in ready condition.")
            if (item.hand != row.hand && item.hand != Hand.AMBIDEXTROUS) return failure("Incompatible equipment handedness.")
            if (conflictingApproval(row.gearId, row.start, row.end, row.id)) return failure("Cannot approve: another booking overlaps these dates.")
        }
        row.status = if (approve) BorrowStatus.APPROVED else BorrowStatus.DECLINED
        return success(if (approve) "Borrowing request approved in demo." else "Borrowing request declined in demo.")
    }

    fun returnBorrow(id: String): DemoResult {
        val row = borrowings.find { it.id == id } ?: return failure("Request not found.")
        if (row.status != BorrowStatus.APPROVED) return failure("Only approved borrowings can be returned.")
        row.status = BorrowStatus.RETURNED
        return success("Equipment return recorded in demo.")
    }

    fun submitApplication(person: String, kind: String, category: String): DemoResult {
        if (person.isBlank()) return failure("Enter the applicant or member name.")
        if (kind !in setOf("New applicant", "Renewal")) return failure("Select an application type.")
        if (category !in setOf("Trainee", "Regular", "Associate")) return failure("Choose a membership category.")
        if (applications.any { it.person.equals(person.trim(), true) && it.status == ReviewStatus.SUBMITTED })
            return failure("This person already has a pending application.")
        applications += Application(id(if (kind == "Renewal") "RN" else "AP"), person.trim(), kind, category)
        return success("Application added to the demo review queue.")
    }

    fun reviewApplication(id: String, approve: Boolean): DemoResult {
        val row = applications.find { it.id == id } ?: return failure("Application not found.")
        if (row.status != ReviewStatus.SUBMITTED) return failure("This application has already been reviewed.")
        row.status = if (approve) ReviewStatus.APPROVED else ReviewStatus.REJECTED
        if (approve) {
            memberships.removeAll { it.person == row.person }
            memberships += Membership(row.person, row.requestedCategory)
        }
        return success("${row.kind} ${if (approve) "approved" else "rejected"} in demo.")
    }

    fun nominate(person: String, position: String): DemoResult {
        if (person.isBlank()) return failure("Enter a candidate name.")
        if (position !in positions) return failure("Choose a valid position.")
        if (!electionOpen) return failure("The election is closed.")
        if (candidates.any { it.person.equals(person.trim(), true) && it.position == position }) return failure("Candidate is already nominated for this position.")
        candidates += Candidate(id("C"), person.trim(), position)
        return success("Nomination added to the demo election.")
    }

    fun castBallot(selections: Map<String, String>): DemoResult {
        if (!electionOpen) return failure("Voting is closed.")
        if (ballots.isNotEmpty()) return failure("This simulated member has already voted. One ballot per member.")
        if (!positions.all { position -> candidates.any { it.id == selections[position] && it.position == position } })
            return failure("Choose one candidate for each position.")
        ballots.putAll(selections)
        return success("Ballot recorded locally. Choices cannot be changed in this session.")
    }

    fun closeElection(): DemoResult {
        if (!electionOpen) return failure("Election is already closed.")
        electionOpen = false
        return success("Demo election closed. Sample tallies are now visible.")
    }

    fun submitFinance(person: String, purpose: String, amount: Double, proof: String): DemoResult {
        if (person.isBlank() || purpose.isBlank()) return failure("Enter the submitter and purpose.")
        if (!amount.isFinite() || amount <= 0.0) return failure("Enter an amount greater than zero.")
        if (proof.isBlank()) return failure("Include a supporting proof reference (fictional for demo).")
        finances += FinanceRecord(id("FN"), person.trim(), purpose.trim(), amount, proof.trim())
        return success("Financial record submitted for verification in demo.")
    }

    fun reviewFinance(id: String, decision: String): DemoResult {
        val row = finances.find { it.id == id } ?: return failure("Record not found.")
        when (decision) {
            "approve" -> { if (row.status != FinanceStatus.SUBMITTED) return failure("Only submitted records can be approved.");row.status = FinanceStatus.APPROVED }
            "reject" -> { if (row.status != FinanceStatus.SUBMITTED) return failure("Only submitted records can be rejected.");row.status = FinanceStatus.REJECTED }
            "verify" -> { if (row.status != FinanceStatus.APPROVED) return failure("Officer approval is required before verification.");row.status = FinanceStatus.VERIFIED }
            else -> return failure("Unknown finance action.")
        }
        return success("Financial record ${row.status.name.lowercase()} in demo (no payment processed).")
    }

    fun incrementGoal(label: String): DemoResult {
        val goal = goals.find { it.label == label } ?: return failure("Goal not found.")
        goal.achieved += 1
        return success("Demo goal progress updated.")
    }

    fun summaryCsv(): String {
        val rows = listOf(
            listOf("Measure", "Count"),
            listOf("Equipment items", gear.size.toString()),
            listOf("Ready equipment", gear.count { it.condition == GearCondition.READY }.toString()),
            listOf("Pending borrowing requests", borrowings.count { it.status == BorrowStatus.PENDING }.toString()),
            listOf("Approved borrowing requests", borrowings.count { it.status == BorrowStatus.APPROVED }.toString()),
            listOf("Pending membership reviews", applications.count { it.status == ReviewStatus.SUBMITTED }.toString()),
            listOf("Approved membership records", memberships.size.toString()),
            listOf("Financial records awaiting review", finances.count { it.status == FinanceStatus.SUBMITTED }.toString()),
            listOf("Financial records verified", finances.count { it.status == FinanceStatus.VERIFIED }.toString()),
            listOf("Demo votes cast", if (ballots.isEmpty()) "0" else "1"),
        )
        return rows.joinToString("\r\n", postfix = "\r\n") { cells -> cells.joinToString(",") { cell -> "\"" + cell.replace("\"", "\"\"") + "\"" } }
    }
}
