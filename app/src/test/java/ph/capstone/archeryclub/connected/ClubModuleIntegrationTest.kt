package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ClubModuleIntegrationTest {
    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject
    private fun form(action: String, table: String, permission: String, states: String = "[]") =
        obj("""{"action":"$action","table":"$table","permission":"$permission","states":$states}""")

    @Test fun memberBorrowingScreenDoesNotExposeOfficersOtherMemberRecords() {
        val workspace = obj("""{"user_id":"me","equipment":[{"id":"bow"}],
            "borrowings":[{"id":"mine","user_id":"me"},{"id":"other","user_id":"other"}],
            "financial_records":[{"id":"other-payment","user_id":"other"}]}""")
        assertEquals(listOf("mine"), clubModuleRows(5,"borrowings",workspace).map{it.text("id")})
        assertTrue(clubModuleRows(5,"financial_records",workspace).isEmpty())
        assertEquals(1,clubModuleRows(5,"equipment",workspace).size)
        assertEquals(2,clubModuleRows(10,"borrowings",workspace).size)
    }

    @Test fun financeReviewNeverOffersSelfApprovalOrEditingAnotherOwnersDraft() {
        val edit=form("save_financial_request","financial_records","account")
        val approve=form("approve_funds","financial_records","funds","[\"pending_verification\"]")
        val verify=form("verify_financial_request","financial_records","finance","[\"pending_verification\"]")
        val own=obj("""{"id":"f","user_id":"me","kind":"expense","status":"pending_verification"}""")
        assertTrue(recordWorkflowActions(listOf(edit,approve,verify),"financial_records",own,"me").isEmpty())
        val otherDraft=obj("""{"user_id":"other","status":"draft"}""")
        assertTrue(recordWorkflowActions(listOf(edit),"financial_records",otherDraft,"me").isEmpty())
        val payment=obj("""{"user_id":"other","kind":"payment","status":"pending_verification"}""")
        assertEquals(listOf(verify),recordWorkflowActions(listOf(approve,verify),"financial_records",payment,"me"))
    }

    @Test fun borrowingReviewCannotSkipReleaseOrReopenReturnedRequests() {
        val review=form("review_borrowing","borrowings","equipment")
        val field=obj("""{"name":"status"}""")
        assertEquals(listOf("released","rejected"),workflowChoiceOptions(review,field,obj("""{"status":"approved"}"""),obj("{}")))
        assertEquals(listOf("returned"),workflowChoiceOptions(review,field,obj("""{"status":"released"}"""),obj("{}")))
        assertTrue(recordWorkflowActions(listOf(review),"borrowings",obj("""{"status":"returned"}"""),"me").isEmpty())
        val request=form("request_borrowing","borrowings","member")
        assertTrue(recordWorkflowActions(listOf(request),"borrowings",obj("""{"user_id":"other","status":"returned_for_correction"}"""),"me").isEmpty())
    }

    @Test fun financeOfficeChoicesRespectAssignedOfficeAndSeparateApprovers() {
        val approval=form("approve_funds","financial_records","funds")
        val field=obj("""{"name":"office"}""");val record=obj("""{"id":"f"}""")
        assertEquals(listOf("finance"),workflowChoiceOptions(approval,field,record,obj("""{"user_id":"me","permissions":["finance"]}""")))
        val both=obj("""{"user_id":"me","permissions":["finance","president"],"financial_approvals":[{"record_id":"f","office":"president","approved_by":"me"}]}""")
        assertTrue(workflowChoiceOptions(approval,field,record,both).isEmpty())
    }

    @Test fun reportExportPreservesServerAmountsPeriodsAndMissingValuesWithoutInventingMetrics() {
        val report=obj("""{"from":"2026-10-01","finance":{"verified_payments":100.25},"goals":[{"title":"A, \"B\"","actual":null}]}""")
        assertEquals("\"Field\",\"Value\"\r\n\"from\",\"2026-10-01\"\r\n\"finance.verified_payments\",\"100.25\"\r\n\"goals[1].title\",\"A, \"\"B\"\"\"\r\n\"goals[1].actual\",\"Not available\"\r\n",clubReportCsv(report))
        assertFalse(clubReportCsv(report).contains("membership"))
    }
}
