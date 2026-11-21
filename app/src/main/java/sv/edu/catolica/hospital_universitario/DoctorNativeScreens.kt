package sv.edu.catolica.hospital_universitario

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class DoctorNativeHomeActivity:DoctorNativeActivity() {
    override val titleRes=R.string.doc_home
    override fun render() {
        clear();retry={render()}
        work({repo.workspace(ReceptionClock.today())}) { w ->
            binding.title.text=getString(R.string.doc_greeting,w.value("doctor_name"))
            binding.subtitle.text=w.value("facility_name")
            val hero=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL;setBackgroundResource(R.drawable.bg_doctor_hero)
                layoutParams=LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(18)}
            }
            binding.content.addView(hero)
            fun white(text:String,size:Float) {
                hero.addView(TextView(this).apply {
                    this.text=text;textSize=size;setTextColor(getColor(R.color.white))
                    setPadding(0,dp(4),0,dp(8));setTypeface(typeface,Typeface.BOLD)
                })
            }
            white(getString(R.string.doc_today),20f)
            white(ReceptionClock.today().format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM",Locale.forLanguageTag("es-SV"))),14f)
            val all=w.optJSONArray("appointments")?.objects().orEmpty()
            val active=all.filter {it.value("status") !in setOf("cancelled","rejected","no_show")}
            white(getString(R.string.doc_summary,active.size,active.count {it.value("status") in setOf("scheduled","confirmed","in_progress")},active.count {it.value("status")=="completed"}),14f)
            section(R.string.doc_next)
            val next=active.firstOrNull {it.value("status")=="in_progress"}?:active.filter {it.value("status") in setOf("scheduled","confirmed") && (DoctorDates.start(it)?.toInstant()?.isAfter(java.time.Instant.now())==true)}.minByOrNull {DoctorDates.start(it)!!.toInstant()}
            if(next==null) note(getString(R.string.doc_none)) else appointment(next)
            section(R.string.desk_access)
            tileGrid(listOf(
                Triple(R.string.doc_agenda,R.drawable.ic_reception_calendar,R.string.doc_today to {Navigator.goToDoctorAppointments(this)}),
                Triple(R.string.doc_patients,R.drawable.ic_reception_patient,R.string.doc_chart to {Navigator.goToDoctorRecords(this)}),
                Triple(R.string.doc_consult,R.drawable.ic_reception_requests,R.string.doc_history to {Navigator.goToDoctorConsult(this)}),
                Triple(R.string.doc_labs,R.drawable.ic_reception_plus,R.string.doc_published to {Navigator.goToDoctorLab(this)}),
                Triple(R.string.doc_rx,R.drawable.ic_reception_requests,R.string.doc_rx_lines to {Navigator.goToDoctorPrescriptions(this)}),
                Triple(R.string.doc_admissions,R.drawable.ic_reception_hospital,R.string.doc_tracking to {Navigator.goToDoctorAdmissions(this)})
            ))
        }
    }
}

fun DoctorNativeActivity.appointment(a:JSONObject) {
    val start=DoctorDates.start(a)?.atZoneSameInstant(ReceptionClock.zone)
        ?.format(DateTimeFormatter.ofPattern("hh:mm a",java.util.Locale.US))
        ?.lowercase(java.util.Locale.US).orEmpty()
    recordCard(a.value("patient_name"),status(a.value("status")),
        listOf(getString(R.string.doc_record,a.value("record_number")),getString(R.string.doc_ticket_number,a.value("ticket_number")),a.value("room_code")).filter {it.isNotBlank()}.joinToString(" · "),start) {
        if(ctx.pending!=null || ctx.prescriptionLines.length()>0) {showError(DoctorFailure(R.string.doc_pending));return@recordCard}
        val select={ctx.selectAppointment(a);Navigator.goToDoctorTicket(this)}
        if(ctx.dirty()) confirm(getString(R.string.doc_discard),select) else select()
    }
}

class DoctorNativeAgendaActivity:DoctorNativeActivity() {
    override val titleRes=R.string.doc_agenda
    private var day=ReceptionClock.today()
    private var query=""
    private var filter=""
    override fun onCreate(state:Bundle?) {
        day=state?.getString("day")?.let {LocalDate.parse(it)}?:ReceptionClock.today()
        query=state?.getString("query").orEmpty();filter=state?.getString("filter").orEmpty()
        super.onCreate(state)
    }
    override fun onSaveInstanceState(out:Bundle) {
        out.putString("day",day.toString());out.putString("query",query);out.putString("filter",filter)
        super.onSaveInstanceState(out)
    }
    override fun render() {
        clear();retry={render()}
        val controls=card(getString(R.string.doc_agenda))
        date(day,controls) {day=it;render()}
        val input=field(R.string.doc_search,controls,query,120)
        input.hint=getString(R.string.doc_search_hint)
        action(R.string.doc_search,controls) {query=input.text.toString().trim();render()}
        chipStrip(listOf("" to getString(R.string.doc_all),"scheduled" to status("scheduled"),"confirmed" to status("confirmed"),"in_progress" to status("in_progress"),"completed" to status("completed"),"cancelled" to status("cancelled"),"no_show" to status("no_show")),filter) {filter=it;query=input.text.toString().trim();render()}
        val list=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        binding.content.addView(list)
        work({repo.workspace(day)}) { w ->
            val items=w.optJSONArray("appointments")?.objects().orEmpty().filter {
                (filter.isEmpty() || it.value("status")==filter) && listOf("patient_name","record_number","ticket_number").any {k->it.value(k).contains(query,true)}
            }.sortedBy {DoctorDates.start(it)?.toInstant()}
            if(items.isEmpty()) note(getString(R.string.doc_none),list)
            items.forEach { a ->
                appointment(a)
            }
        }
    }
}

class DoctorNativeTicketActivity:DoctorNativeActivity() {
    override val titleRes=R.string.doc_ticket
    override fun render() {
        clear()
        val a=ctx.appointment
        if(a==null) {note(getString(R.string.doc_patient_required));action(R.string.doc_agenda){Navigator.goToDoctorAppointments(this)};return}
        val body=card(a.value("patient_name"),getString(R.string.doc_record,a.value("record_number")))
        note(getString(R.string.doc_ticket_number,a.value("ticket_number")),body)
        note(DoctorDates.start(a)?.atZoneSameInstant(ReceptionClock.zone)
            ?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm a",java.util.Locale.US))
            ?.lowercase(java.util.Locale.US).orEmpty(),body)
        note(status(a.value("status")),body)
        listOf(R.string.doc_reason to a.value("reason"),R.string.doc_symptoms to (a.optJSONArray("symptoms")?.let {arr->(0 until arr.length()).map {arr.optString(it)}.joinToString(", ")}.orEmpty()+" "+a.value("other_symptoms")),R.string.doc_specialty to a.value("specialty"),R.string.doc_contact to a.value("phone")).forEach {(label,value)->if(value.isNotBlank()) {note(getString(label),body);note(value,body)}}
        if(pendingAction {render()}) return
        action(R.string.doc_view_chart,body){Navigator.goToDoctorChart(this)}
        if(a.value("encounter_id").isNotBlank()) action(R.string.doc_continue,body){Navigator.goToDoctorConsult(this)}
        else if(a.value("status") in setOf("scheduled","confirmed","in_progress")) action(R.string.doc_start,body){
            confirm {send("start",args("p_appointment" to a.value("id"))) { value->
                ctx.encounter=value.toString();a.put("encounter_id",ctx.encounter);Navigator.goToDoctorConsult(this,true)
            }}
        }
    }
}

class DoctorNativePatientsActivity:DoctorNativeActivity() {
    override val titleRes=R.string.doc_patients
    private var query=""
    private var offset=0
    private var list:LinearLayout?=null
    override fun render() {
        clear();offset=0;retry={render()}
        val controls=card(getString(R.string.doc_patients))
        val input=field(R.string.doc_search,controls,query,120);input.hint=getString(R.string.doc_search_hint)
        action(R.string.doc_search,controls){query=input.text.toString().trim();render()}
        list=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL};binding.content.addView(list)
        page()
    }
    private fun page() {
        work({repo.patients(query,offset)}) { data ->
            val target=list?:return@work
            val items=data.optJSONArray("patients")?.objects().orEmpty()
            if(items.isEmpty() && offset==0) note(getString(R.string.doc_none),target)
            items.forEach { p->
                recordCard(p.value("full_name"),p.value("record_number"),
                    listOf(p.value("dui"),p.value("phone"),p.value("email")).filter {it.isNotBlank()}.joinToString(" · "),"",target) {
                    choosePatient(p){Navigator.goToDoctorChart(this)}
                }
            }
            if(data.optBoolean("has_more")) {
                var more:Button?=null
                more=action(R.string.doc_more_page,target){more?.visibility=View.GONE;offset=data.optInt("next_offset");retry={page()};page()}
            }
        }
    }
}

class DoctorNativeChartActivity:DoctorNativeActivity() {
    override val titleRes=R.string.doc_chart
    private var query=""
    private var from:LocalDate?=null
    private var to:LocalDate?=null
    override fun render() {
        clear();retry={render()}
        val id=selectedPatient()
        if(id==null) {note(getString(R.string.doc_patient_required));action(R.string.doc_select){Navigator.goToDoctorRecords(this)};return}
        val controls=card(getString(R.string.doc_history))
        val search=field(R.string.doc_search,controls,query,120)
        val row=filterRow(controls)
        compact(action(getString(R.string.doc_filter_from)+(from?.toString()?:""),row){pickDate(from?:ReceptionClock.today()){from=it;query=search.text.toString();render()}})
        compact(action(getString(R.string.doc_filter_to)+(to?.toString()?:""),row){pickDate(to?:ReceptionClock.today()){to=it;query=search.text.toString();render()}})
        action(R.string.doc_search,controls){query=search.text.toString().trim();render()}
        action(R.string.doc_clear_dates,controls){from=null;to=null;render()}
        work({repo.chart(id,from?.atStartOfDay(ReceptionClock.zone)?.toOffsetDateTime()?.toString(),to?.plusDays(1)?.atStartOfDay(ReceptionClock.zone)?.toOffsetDateTime()?.toString(),query)}) {data->
            // Los filtros de historial no reemplazan la versión editable de la consulta.
            val p=data.optJSONObject("patient")?:JSONObject()
            val identity=card(p.value("full_name"),getString(R.string.doc_record,p.value("record_number")))
            listOf("birth_date","sex","phone","email","address","identification").forEach {k->if(p.value(k).isNotBlank()) note(p.value(k),identity)}
            val actions=filterRow(identity)
            compact(action(R.string.doc_continue,actions){Navigator.goToDoctorConsult(this)})
            compact(action(R.string.doc_view_results,actions){Navigator.goToDoctorPatientLabs(this)})
            action(R.string.doc_request_admission,identity){Navigator.goToDoctorAdmissions(this)}
            listOf("allergies" to R.string.doc_allergies,"conditions" to R.string.doc_conditions,"medical_history" to R.string.doc_background).forEach {(key,label)->
                val values=data.optJSONArray(key)
                val c=card(getString(label))
                note(if(values==null || values.length()==0) getString(R.string.doc_none) else (0 until values.length()).map {values.optString(it)}.joinToString("\n"),c)
            }
            val vitals=data.optJSONArray("vitals")?.objects().orEmpty()
            if(vitals.isNotEmpty()) {val c=card(getString(R.string.doc_vitals));vitals.forEach {note(it.value("label")+getString(R.string.desk_separator)+it.value("value"),c)}}
            section(R.string.doc_history)
            val history=data.optJSONArray("history")?.objects().orEmpty()
            if(history.isEmpty()) note(getString(R.string.doc_none))
            history.forEach {h->
                val c=card(time(h.value("started_at")),status(h.value("status")))
                listOf("subjective" to R.string.doc_subjective,"objective" to R.string.doc_objective,"assessment" to R.string.doc_assessment,"plan" to R.string.doc_plan).forEach {(key,label)->if(h.value(key).isNotBlank()){note(getString(label),c);note(h.value(key),c)}}
                h.optJSONObject("vitals")?.let {v->v.keys().forEach {k->note(k+getString(R.string.desk_separator)+v.value(k),c)}}
            }
            section(R.string.doc_rx)
            data.optJSONArray("prescriptions")?.objects().orEmpty().forEach {rx->
                val c=card(rx.value("code"),time(rx.value("issued_at")))
                note(rx.optJSONArray("lines")?.let {a->(0 until a.length()).map {a.optString(it)}.joinToString("\n")}.orEmpty(),c)
            }
        }
    }
    private fun pickDate(day:LocalDate,changed:(LocalDate)->Unit) {
        android.app.DatePickerDialog(this,{_,y,m,d->changed(LocalDate.of(y,m+1,d))},day.year,day.monthValue-1,day.dayOfMonth).show()
    }
}

class DoctorNativeMoreActivity:DoctorNativeActivity() {
    override val titleRes=R.string.doc_more
    override fun render() {
        clear()
        tileGrid(listOf(
            Triple(R.string.doc_patients,R.drawable.ic_reception_patient,R.string.doc_chart to {Navigator.goToDoctorRecords(this)}),
            Triple(R.string.doc_labs,R.drawable.ic_reception_plus,R.string.doc_published to {Navigator.goToDoctorLab(this)}),
            Triple(R.string.doc_rx,R.drawable.ic_reception_requests,R.string.doc_rx_lines to {Navigator.goToDoctorPrescriptions(this)}),
            Triple(R.string.doc_admissions,R.drawable.ic_reception_hospital,R.string.doc_tracking to {Navigator.goToDoctorAdmissions(this)})
        ))
        action(R.string.doc_notifications) {
            work({repo.workspace(ReceptionClock.today())}) {data->
                val c=card(getString(R.string.doc_notifications))
                val notices=data.optJSONArray("notifications")?.objects().orEmpty()
                if(notices.isEmpty()) note(getString(R.string.doc_no_notifications),c)
                notices.forEach {note(it.value("title"),c);note(it.value("body"),c)}
            }
        }
        action(R.string.dialog_logout_title){Navigator.logout(this)}
    }
}
