package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate

data class MemberTask(val module:Int,val table:String,val row:JsonObject,val title:String,val status:String)

/** Tasks come from current source records, independently of notification acknowledgement. */
fun memberTasks(workspace:JsonObject,now:Instant=Instant.now(),today:LocalDate=LocalDate.now()):List<MemberTask> {
    val user=workspace.text("user_id")
    val result=mutableListOf<MemberTask>()
    fun add(table:String,module:Int,title:String,status:(JsonObject)->String?) {
        workspace.records(table).filter { it.text("user_id")==user }.forEach { row ->
            status(row)?.let { result.add(MemberTask(module,table,row,title,it)) }
        }
    }
    add("renewals",1,"Membership renewal") { row -> when(row.text("status")) {
        "draft"->"Finish and submit your draft"
        "returned_for_correction"->"Review the officer's remarks and resubmit"
        "pending"->"Awaiting membership review"
        else->null
    } }
    add("borrowings",5,"Equipment borrowing") { row -> when(row.text("status")) {
        "returned_for_correction"->"Correct and resubmit your request"
        "pending"->"Awaiting equipment review"
        "approved"->"Approved; awaiting officer release"
        "released"->if(runCatching{Instant.parse(row.text("ends_at")).isBefore(now)}.getOrDefault(false)) "Overdue: arrange return with the equipment officer" else "Return by ${row.text("ends_at")}"
        else->null
    } }
    add("training_records",4,"Personal training") { row -> when(row.text("validation")) {
        "personal"->"Review and submit your personal scorecard"
        "returned_for_correction"->"Review feedback, correct and resubmit"
        "pending"->"Awaiting coach validation"
        else->null
    } }
    add("financial_records",12,"Financial submission") { row -> when(row.text("status")) {
        "draft"->"Attach supporting documents and submit"
        "incomplete","returned_for_correction"->"Review remarks, correct and resubmit"
        "pending_verification"->"Awaiting authorized financial review"
        else->null
    } }
    add("fee_assessments",12,"Outstanding fee") { row ->
        if(row.text("payment_id").isNotBlank()) null
        else if(runCatching{LocalDate.parse(row.text("due_on")).isBefore(today)}.getOrDefault(false)) "Past due: ${row.text("amount")}; contact the Treasurer"
        else "${row.text("amount")} due ${row.text("due_on")}; verification pending"
    }
    return result
}
