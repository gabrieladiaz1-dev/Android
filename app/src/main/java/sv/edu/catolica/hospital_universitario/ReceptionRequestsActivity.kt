package sv.edu.catolica.hospital_universitario

import androidx.core.widget.addTextChangedListener
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

class ReceptionRequestsActivity : ReceptionDeskActivity() {
    override val titleRes=R.string.desk_requests
    private var state=""
    private var newest=false
    private var offset=0
    private var rows=listOf<JSONObject>()
    private var specialties=mapOf<Long,String>()
    private var query=""
    private var selected: JSONObject?=null

    override fun render() {
        if(selected!=null) { detail(selected!!);return }
        clear()
        val search=field(R.string.desk_search,value=query)
        val controls=filterRow()
        val states=listOf("","pendiente","en_revision","agendada","rechazada","cancelada")
        compact(picker(R.string.desk_state,states.map {it to if(it.isBlank()) getString(R.string.desk_open) else status(it)},state,controls) {
            state=it;offset=0;render()
        })
        compact(picker(R.string.desk_order,listOf("old" to getString(R.string.desk_old_short),"new" to getString(R.string.desk_new_short)),
            if(newest) "new" else "old",controls) {newest=it=="new";offset=0;render()})
        val indicators=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        binding.content.addView(indicators)
        val list=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        binding.content.addView(list)
        val more=action(R.string.desk_more) {}
        more.visibility=android.view.View.GONE
        fun display() {
            list.removeAllViews()
            val visible=rows.filter {
                (it.optJSONObject("applicant_data")?.toString().orEmpty()+" "+specialties[it.optLong("specialty_id")]).contains(query,true)
            }
            if(visible.isEmpty()) note(getString(R.string.desk_empty),list)
            visible.forEach { row ->
                val person=row.optJSONObject("applicant_data")?:JSONObject()
                val title=listOf(person.value("first_name"),person.value("last_name")).joinToString(" ")
                recordCard(title,status(row.value("status")),
                    listOf(specialties[row.optLong("specialty_id")].orEmpty(),status(row.value("preferred_period"))).joinToString(" · "),
                    time(row.value("created_at")),list) {selected=row;detail(row)}
            }
        }
        search.addTextChangedListener {query=it.toString();display()}
        retry={offset=0;render()}
        work({
            val catalog=repo.rows("specialties","select=id,name")
            Triple(repo.requestPage(state,offset,newest),repo.counts(),catalog)
        }) { (page,counts,catalog) ->
            specialties=catalog.associate {it.getLong("id") to it.value("name")}
            rows=page.first
            chipStrip(counts.map {(s,n)->s to getString(R.string.desk_stat,status(s),n)},state,indicators) {
                state=it;offset=0;render()
            }
            more.visibility=if(page.second) android.view.View.VISIBLE else android.view.View.GONE
            display()
            more.setOnClickListener {
                if(!busy) {
                    val nextOffset=offset+100
                    work({repo.requestPage(state,nextOffset,newest)}) { next ->
                        offset=nextOffset
                        val codes=listOf("pendiente","en_revision","agendada","rechazada","cancelada")
                        rows=(rows+next.first).distinctBy {it.value("id")}.sortedWith {a,b ->
                            val priority=if(state.isBlank()) codes.indexOf(a.value("status"))-codes.indexOf(b.value("status")) else 0
                            if(priority!=0) priority else a.value("created_at").compareTo(b.value("created_at")) * if(newest) -1 else 1
                        }
                        more.visibility=if(next.second) android.view.View.VISIBLE else android.view.View.GONE
                        display()
                    }
                }
            }
        }
    }
    private fun detail(request: JSONObject) {
        selected=request
        clear()
        action(R.string.desk_back) { selected=null;offset=0;render() }
        val person=request.optJSONObject("applicant_data")?:JSONObject()
        val data=card(getString(R.string.desk_request_data),patientText(person))
        note(status(request.value("status")),data)
        note(specialties[request.optLong("specialty_id")].orEmpty(),data)
        note(time(request.value("created_at")),data)
        note(request.value("reason"),data)
        note(request.value("other_symptoms"),data)
        note(status(request.value("preferred_period")),data)
        if(request.value("closure_reason").isNotBlank()) note(request.value("closure_reason"),data)
        val follow=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        binding.content.addView(follow)
        retry={detail(request)}
        work({
            val id=request.value("patient_id")
            val account=request.value("requester_id")
            val patients=when {
                id.isNotBlank()->repo.rows("patients","select=*&id=eq.$id")
                account.isNotBlank()->repo.rows("patients","select=*&profile_id=eq.$account")
                else -> emptyList()
            }
            val symptoms=repo.rows("appointment_request_symptoms",
                "select=symptom_id,symptom_catalog(name)&request_id=eq.${request.value("id")}")
            val appointment=repo.rows("appointments","select=*&request_id=eq.${request.value("id")}&limit=2").map {a ->
                val day=java.time.OffsetDateTime.parse(a.value("starts_at"))
                    .atZoneSameInstant(ReceptionClock.zone).toLocalDate().toString()
                val agenda=(repo.rpc("reception_agenda",args("p_day" to day)) as JSONArray).objects()
                agenda.firstOrNull {it.value("id")==a.value("id")} ?: a
            }
            Triple(patients.singleOrNull()?.let {it to repo.record(it.value("id"))},symptoms,appointment)
        }) { (linked,symptoms,appointment) ->
            note(symptoms.mapNotNull {it.optJSONObject("symptom_catalog")?.value("name")}.joinToString(", "),data)
            appointment.forEach {
                note(getString(R.string.desk_appointment,
                    person.value("first_name")+" "+person.value("last_name"),it.value("doctor_name"),
                    time(it.value("starts_at")),time(it.value("ends_at")),it.value("room_code"),status(it.value("status"))),follow)
            }
            if(request.value("status") !in listOf("pendiente","en_revision")) return@work
            action(R.string.desk_review_action,follow) {
                work({repo.rpc("review_attention_request",args("p_request" to request.value("id"),"p_status" to "en_revision"))}) {
                    request.put("status","en_revision")
                    verify(request,linked)
                }
            }
            action(R.string.desk_reject,follow) {close(request,"rechazada")}
            action(R.string.desk_cancel_request,follow) {close(request,"cancelada")}
        }
    }
    private fun close(request: JSONObject,state: String) {
        reason(if(state=="rechazada") R.string.desk_reject else R.string.desk_cancel_request) {text ->
            work({repo.rpc("close_attention_request",args("p_request" to request.value("id"),"p_status" to state,"p_reason" to text))}) {
                request.put("status",state).put("closure_reason",text);detail(request)
            }
        }
    }
    private fun verify(request: JSONObject,linked: Pair<JSONObject,String?>?) {
        clear()
        action(R.string.desk_back) {detail(request)}
        val person=request.optJSONObject("applicant_data")?:JSONObject()
        card(getString(R.string.desk_request_data),patientText(person))
        if(linked!=null) {
            val body=patientCard(linked.first,linked.second)
            if(linked.second!=null && request.value("patient_id").isNotBlank()) {
                action(R.string.desk_schedule,body) {
                    ReceptionScheduling(this).open(linked.first,request)
                }
                return
            }
        }
        val form=card(getString(R.string.desk_compare))
        val document=field(R.string.desk_dui,form,person.value("dui"),10)
        val results=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        binding.content.addView(results)
        action(R.string.desk_lookup,form) {
            val dui=runCatching {normalizeDocument(document.text.toString())}.getOrElse {showError(it);return@action}
            work({repo.patientByDui(dui)?.let {it to repo.record(it.value("id"))}}) {found ->
                results.removeAllViews()
                if(found!=null) {
                    val body=patientCard(found.first,found.second,results)
                    note(getString(R.string.desk_match_compare,patientText(person),patientText(found.first)),body)
                    val differentAccount=found.first.value("profile_id").isNotBlank() &&
                        found.first.value("profile_id")!=request.value("requester_id")
                    if(differentAccount) note(getString(R.string.desk_other_account),body)
                    val identity=check(target=body)
                    identity.isEnabled=!differentAccount
                    if(found.second!=null && !differentAccount) action(R.string.desk_link,body) {
                        if(!identity.isChecked) {showError(DeskException(R.string.desk_identity_required));return@action}
                        confirm(getString(R.string.desk_confirm_link)) {
                            work({repo.rpc("link_attention_patient",args("p_request" to request.value("id"),
                                "p_patient" to found.first.value("id"),"p_identity_confirmed" to true))}) {
                                request.put("patient_id",found.first.value("id"))
                                verify(request,found)
                            }
                        }
                    }
                } else {
                    note(getString(R.string.desk_not_found),results)
                    val identity=check(target=results)
                    action(R.string.desk_create_link,results) {
                        if(!identity.isChecked) {showError(DeskException(R.string.desk_identity_required));return@action}
                        confirm(getString(R.string.desk_confirm_create)) {
                            work({
                                val id=repo.rpc("create_attention_patient",args("p_request" to request.value("id"),
                                    "p_identity_confirmed" to true)) as String
                                val p=repo.rows("patients","select=*&id=eq.$id").single()
                                p to repo.record(id)
                            }) { patient -> request.put("patient_id",patient.first.value("id"));verify(request,patient) }
                        }
                    }
                }
            }
        }
    }
}
