package sv.edu.catolica.hospital_universitario

import android.text.InputType
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import org.json.JSONObject
import org.json.JSONArray

class DoctorNativeConsultActivity:DoctorNativeActivity() {
    override val titleRes=R.string.doc_consult
    override fun render() {
        clear();retry={render()}
        val patient=selectedPatient()
        if(patient==null) {note(getString(R.string.doc_patient_required));action(R.string.doc_agenda){Navigator.goToDoctorAppointments(this)};action(R.string.doc_select){Navigator.goToDoctorRecords(this)};return}
        if(pendingAction {render()}) return
        work({repo.chart(patient)}) { data->
            ctx.applyChart(data,!ctx.dirty())
            display()
        }
    }
    private fun display() {
        val p=ctx.chart?.optJSONObject("patient")
        val head=card(p?.value("full_name").orEmpty(),getString(R.string.doc_record,p?.value("record_number").orEmpty()))
        action(R.string.doc_view_chart,head){Navigator.goToDoctorChart(this)}
        if(ctx.encounter==null) {
            note(getString(R.string.doc_no_active))
            action(R.string.doc_agenda){Navigator.goToDoctorAppointments(this)}
            return
        }
        if(!ctx.openEncounter()) {note(getString(R.string.doc_closed));return}
        note(getString(R.string.doc_version,ctx.version))
        val form=card(getString(R.string.doc_consult))
        listOf("subjective" to R.string.doc_subjective,"objective" to R.string.doc_objective,"assessment" to R.string.doc_assessment,"plan" to R.string.doc_plan).forEach {(key,label)->
            field(label,form,ctx.draft.value(key),10000).doAfterTextChanged {ctx.draft.put(key,it.toString())}
        }
        field(R.string.doc_diagnosis,form,ctx.draft.value("diagnosis_code"),50).doAfterTextChanged {ctx.draft.put("diagnosis_code",it.toString())}
        note(getString(R.string.doc_no_diagnosis_catalog),form)
        val vital=card(getString(R.string.doc_vitals))
        val values=ctx.draft.optJSONObject("vitals")?:JSONObject().also {ctx.draft.put("vitals",it)}
        listOf("weight_kg" to R.string.doc_weight,"height_cm" to R.string.doc_height,"systolic" to R.string.doc_systolic,"diastolic" to R.string.doc_diastolic,"temperature_c" to R.string.doc_temperature,"heart_rate" to R.string.doc_heart).forEach {(key,label)->
            field(label,vital,values.value(key),30).apply {
                inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                doAfterTextChanged {if(it.isNullOrBlank()) values.remove(key) else values.put(key,it.toString())}
            }
        }
        action(R.string.doc_save) {
            val snapshot=JSONObject(ctx.draft.toString())
            val v=snapshot.optJSONObject("vitals")
            if(v!=null && v.keys().asSequence().any {v.value(it).toBigDecimalOrNull()==null}) {showError(DoctorFailure(R.string.doc_required));return@action}
            send("save",args("p_encounter" to ctx.encounter,"p_version" to ctx.version,"p_values" to snapshot)) { result->
                ctx.version=(result as? Number)?.toInt()?:result.toString().toInt()
                ctx.baseline=JSONObject(snapshot.toString())
                Toast.makeText(this,R.string.doc_saved,Toast.LENGTH_SHORT).show();render()
            }
        }
        val row=filterRow()
        compact(action(R.string.doc_labs,row){Navigator.goToDoctorLab(this)})
        compact(action(R.string.doc_rx,row){Navigator.goToDoctorPrescriptions(this)})
        action(R.string.doc_request_admission){Navigator.goToDoctorAdmissions(this)}
        action(R.string.doc_reload) {confirm(getString(R.string.doc_reload_confirm)){
            work({repo.chart(selectedPatient()!!)}) {ctx.applyChart(it,true);render()}
        }}
        action(R.string.doc_finish) {
            if(ctx.dirty() || ctx.pending!=null || ctx.prescriptionLines.length()>0 || listOf("subjective","objective","assessment","plan").any {ctx.baseline.value(it).isBlank()}) {
                showError(DoctorFailure(R.string.doc_save_first));return@action
            }
            confirm(getString(R.string.doc_finish_confirm)) {
                send("finish",args("p_encounter" to ctx.encounter,"p_version" to ctx.version)) {
                    Toast.makeText(this,R.string.doc_finished,Toast.LENGTH_LONG).show();render()
                }
            }
        }
    }
}

/** Pedidos y resultados publicados: no se muestra el result_summary del workspace antiguo. */
open class DoctorNativeLabsActivity:DoctorNativeActivity() {
    override val titleRes=R.string.doc_labs
    open val patientOnly=false
    private var offset=0
    private val selected=linkedSetOf<Long>()
    private var reason=""
    private var catalog:List<JSONObject> = emptyList()
    override fun render() {
        clear();offset=0;retry={render()}
        if(pendingAction {selected.clear();reason="";render()}) return
        if(patientOnly && selectedPatient()==null) {note(getString(R.string.doc_patient_required));return}
        if(!patientOnly && selectedPatient()!=null && ctx.encounter!=null) {
            val top=card(ctx.patient?.value("full_name").orEmpty())
            action(R.string.doc_order_lab,top){loadCatalog()}
        }
        section(if(patientOnly) R.string.doc_published else R.string.doc_orders)
        page()
    }
    private fun page() {
        work({repo.call(if(patientOnly) "patient_labs" else "lab_orders",
            if(patientOnly) args("p_patient" to selectedPatient(),"p_offset" to offset) else args("p_offset" to offset)) as JSONObject}) {data->
            val items=data.optJSONArray("items")?.objects().orEmpty()
            if(items.isEmpty() && offset==0) note(getString(R.string.doc_none))
            items.forEach {o->
                val c=card(o.value("test_name"),listOf(o.value("patient_name"),o.value("record_number"),time(o.value("ordered_at")),o.value("status")).filter {it.isNotBlank()}.joinToString(" · "))
                note(o.value("reason"),c)
                val summary=o.value("result_summary")
                note(if(summary.isBlank()) getString(R.string.doc_not_published) else summary,c)
                o.optJSONArray("parameters")?.objects().orEmpty().forEach {v->
                    note(listOf(v.value("label"),v.value("value"),v.value("unit")).filter {it.isNotBlank()}.joinToString(" "),c)
                    if(v.value("reference").isNotBlank()) note(getString(R.string.doc_reference)+getString(R.string.desk_separator)+v.value("reference"),c)
                    if(v.value("observation").isNotBlank()) note(v.value("observation"),c)
                }
                if(o.value("published_at").isNotBlank()) note(time(o.value("published_at")),c)
            }
            if(data.optBoolean("has_more")) {
                var more:Button?=null
                more=action(R.string.doc_more_page){more?.visibility=android.view.View.GONE;offset=data.optInt("next_offset");retry={page()};page()}
            }
        }
    }
    private fun loadCatalog() {
        work({Pair(repo.chart(selectedPatient()!!),repo.call("lab_catalog") as JSONArray)}) {(chart,values)->
            ctx.applyChart(chart,false)
            if(!ctx.openEncounter()) {showError(DoctorFailure(R.string.doc_no_active));return@work}
            catalog=values.objects();form()
        }
    }
    private fun form() {
        clear()
        val head=card(getString(R.string.doc_lab_catalog),ctx.patient?.value("full_name").orEmpty())
        val query=field(R.string.doc_search,head,"",120)
        val categories=listOf("" to getString(R.string.doc_all))+catalog.map {it.value("category")}.filter {it.isNotBlank()}.distinct().map {it to it}
        var category=""
        val results=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        var refresh:()->Unit={}
        picker(R.string.doc_kind,categories,category,head){category=it;refresh()}
        head.addView(results)
        val count=TextView(this);head.addView(count)
        refresh={
            results.removeAllViews()
            count.text=getString(R.string.doc_lab_selected,selected.size)
            catalog.filter {(category.isBlank() || it.value("category")==category) && (it.value("name")+" "+it.value("code")).contains(query.text.toString(),true)}.forEach {test->
                val check=CheckBox(this).apply {
                    text=listOf(test.value("name"),test.value("code")).filter {it.isNotBlank()}.joinToString(" · ")
                    isChecked=selected.contains(test.optLong("id"))
                    minHeight=dp(48)
                    setOnCheckedChangeListener {_,on->
                        if(on && selected.size>=30) {isChecked=false;return@setOnCheckedChangeListener}
                        if(on) selected.add(test.optLong("id")) else selected.remove(test.optLong("id"))
                        count.text=getString(R.string.doc_lab_selected,selected.size)
                    }
                }
                results.addView(check)
            }
            if(results.childCount==0) note(getString(R.string.doc_none),results)
        }
        query.doAfterTextChanged {refresh()};refresh()
        field(R.string.doc_lab_reason,head,reason,10000).doAfterTextChanged {reason=it.toString()}
        action(R.string.doc_send_lab) {
            if(selected.isEmpty() || reason.isBlank()) {showError(DoctorFailure(R.string.doc_required));return@action}
            val payload=args("p_encounter" to ctx.encounter,"p_tests" to JSONArray(selected.toList()),"p_reason" to reason.trim())
            val description=catalog.filter {selected.contains(it.optLong("id"))}.joinToString("\n"){it.value("name")}+"\n\n"+reason
            confirm(description) {send("labs",payload) {selected.clear();reason="";Toast.makeText(this,R.string.doc_done,Toast.LENGTH_SHORT).show();render()}}
        }
    }
}
class DoctorNativePatientLabsActivity:DoctorNativeLabsActivity() { override val patientOnly=true }

class DoctorNativePrescriptionsActivity:DoctorNativeActivity() {
    override val titleRes=R.string.doc_rx
    private var medications:List<JSONObject> = emptyList()
    override fun render() {
        clear();retry={render()}
        if(selectedPatient()==null) {note(getString(R.string.doc_patient_required));action(R.string.doc_select){Navigator.goToDoctorRecords(this)};return}
        if(pendingAction {ctx.prescriptionLines=JSONArray();render()}) return
        work({Pair(repo.chart(selectedPatient()!!),repo.workspace(ReceptionClock.today()))}) {(chart,w)->
            ctx.applyChart(chart,false)
            medications=w.optJSONArray("medications")?.objects().orEmpty()
            val head=card(chart.optJSONObject("patient")?.value("full_name").orEmpty(),getString(R.string.doc_rx))
            if(ctx.openEncounter()) {
                action(R.string.doc_add_line,head){addMedication()}
                showLines(head)
                action(R.string.doc_issue_rx,head) {
                    if(ctx.prescriptionLines.length()==0) {showError(DoctorFailure(R.string.doc_rx_empty));return@action}
                    val payload=args("p_encounter" to ctx.encounter,"p_lines" to JSONArray(ctx.prescriptionLines.toString()))
                    confirm(getString(R.string.doc_issue_rx)+"\n\n"+ctx.prescriptionLines.objects().joinToString("\n"){line->
                        (medications.firstOrNull {it.value("id")==line.value("medication_id")}?.value("name").orEmpty())+" · "+line.value("dose")+" "+line.value("dose_unit")
                    }) {send("prescribe",payload){ctx.prescriptionLines=JSONArray();Toast.makeText(this,R.string.doc_done,Toast.LENGTH_SHORT).show();render()}}
                }
            } else note(getString(R.string.doc_no_active),head)
            section(R.string.doc_history)
            val items=chart.optJSONArray("prescriptions")?.objects().orEmpty()
            if(items.isEmpty()) note(getString(R.string.doc_none))
            items.forEach {p->
                val c=card(p.value("code"),time(p.value("issued_at")))
                note(p.value("status"),c)
                note(p.optJSONArray("lines")?.let {arr->(0 until arr.length()).joinToString("\n"){arr.optString(it)}}.orEmpty(),c)
            }
        }
    }
    private fun showLines(target:LinearLayout) {
        ctx.prescriptionLines.objects().forEachIndexed {i,line->
            val c=card(medications.firstOrNull {it.value("id")==line.value("medication_id")}?.value("name").orEmpty(),target=target)
            note(listOf(line.value("dose")+" "+line.value("dose_unit"),getString(R.string.doc_frequency)+getString(R.string.desk_separator)+line.value("frequency_hours"),line.value("route"),getString(R.string.doc_quantity)+getString(R.string.desk_separator)+line.value("quantity"),getString(R.string.doc_duration)+getString(R.string.desk_separator)+line.value("duration_days"),line.value("observations")).joinToString("\n"),c)
            action(R.string.doc_remove,c){ctx.prescriptionLines.remove(i);render()}
        }
    }
    private fun addMedication() {
        if(ctx.prescriptionLines.length()>=30) {showError(DoctorFailure(R.string.doc_validation));return}
        clear()
        val form=card(getString(R.string.doc_add_line))
        var medication=""
        var route=""
        picker(R.string.doc_medication,listOf("" to getString(R.string.doc_choose))+medications.map {it.value("id") to listOf(it.value("name"),it.value("strength"),it.value("presentation")).filter {it.isNotBlank()}.joinToString(" · ")},"",form){medication=it}
        val dose=field(R.string.doc_dose,form,"",40).apply {inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL}
        val unit=field(R.string.doc_dose_unit,form,"",40)
        val frequency=field(R.string.doc_frequency,form,"",3).apply {inputType=InputType.TYPE_CLASS_NUMBER}
        picker(R.string.doc_route,listOf("" to getString(R.string.doc_choose),"oral" to getString(R.string.doc_oral),"inyectable" to getString(R.string.doc_injectable)),"",form){route=it}
        val quantity=field(R.string.doc_quantity,form,"",5).apply {inputType=InputType.TYPE_CLASS_NUMBER}
        val duration=field(R.string.doc_duration,form,"",3).apply {inputType=InputType.TYPE_CLASS_NUMBER}
        val observations=field(R.string.doc_observation,form,"",2000)
        action(R.string.doc_add_line,form){
            if(medication.isBlank() || route.isBlank() || dose.text.toString().toBigDecimalOrNull()?.let {it.signum()>0}!=true || unit.text.isBlank() ||
                frequency.text.toString().toIntOrNull() !in 1..720 || quantity.text.toString().toIntOrNull() !in 1..10000 || duration.text.toString().toIntOrNull() !in 1..365 || observations.text.isBlank()) {
                showError(DoctorFailure(R.string.doc_required));return@action
            }
            ctx.prescriptionLines.put(args("medication_id" to medication,"dose" to dose.text.toString(),"dose_unit" to unit.text.toString().trim(),
                "frequency_hours" to frequency.text.toString().toInt(),"route" to route,"quantity" to quantity.text.toString().toInt(),
                "duration_days" to duration.text.toString().toInt(),"observations" to observations.text.toString().trim()))
            render()
        }
        action(R.string.desk_cancel){render()}
    }
}

class DoctorNativeAdmissionsActivity:DoctorNativeActivity() {
    override val titleRes=R.string.doc_admissions
    private var offset=0
    override fun render() {
        clear();offset=0;retry={render()}
        if(pendingAction {render()}) return
        page(true)
    }
    private fun page(first:Boolean=false) {
        work({repo.call("admissions",args("p_offset" to offset)) as JSONObject}) {data->
            if(first) {
                val top=card(getString(R.string.doc_request_admission),ctx.patient?.value("full_name").orEmpty())
                note(getString(R.string.doc_admission_notice),top)
                if(selectedPatient()==null) action(R.string.doc_select,top){Navigator.goToDoctorRecords(this)}
                else action(R.string.doc_request_admission,top){requestForm(data)}
                section(R.string.doc_tracking)
            }
            val items=data.optJSONArray("items")?.objects().orEmpty()
            if(items.isEmpty() && offset==0) note(getString(R.string.doc_none))
            items.forEach {a->
                val c=card(a.value("patient_name"),getString(R.string.doc_record,a.value("record_number")))
                note(listOf(status(a.value("status")),status(a.value("priority")),status(a.value("category")),time(a.value("ordered_at"))).joinToString(" · "),c)
                note(a.value("reason"),c)
                if(a.value("observations").isNotBlank()) note(a.value("observations"),c)
                a.optJSONObject("assignment")?.let {loc->
                    note(getString(R.string.doc_location),c)
                    note(listOf(loc.value("room"),loc.value("bed"),loc.value("floor")).filter {it.isNotBlank()}.joinToString(" · "),c)
                    note(time(loc.value("assigned_at")),c)
                    if(loc.value("released_at").isNotBlank()) note(time(loc.value("released_at")),c)
                }
                if(a.value("decision").isNotBlank()) note(a.value("decision")+getString(R.string.desk_separator)+a.value("decision_reason"),c)
                action(R.string.doc_admission_history,c) {
                    val history=a.optJSONArray("history")?.objects().orEmpty().joinToString("\n\n"){
                        listOf(status(it.value("status")),it.value("decision"),it.value("reason"),time(it.value("at"))).filter {it.isNotBlank()}.joinToString(" · ")
                    }
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(R.string.doc_admission_history)
                        .setMessage(history.ifBlank {getString(R.string.doc_none)}).setPositiveButton(android.R.string.ok,null).show()
                }
            }
            if(data.optBoolean("has_more")) {
                var more:Button?=null
                more=action(R.string.doc_more_page){more?.visibility=android.view.View.GONE;offset=data.optInt("next_offset");retry={page()};page()}
            }
        }
    }
    private fun requestForm(catalog:JSONObject) {
        clear()
        val form=card(getString(R.string.doc_request_admission),ctx.patient?.value("full_name").orEmpty())
        var kind="";var priority=""
        fun options(key:String):List<Pair<String,String>> {
            val a=catalog.optJSONArray(key)?:JSONArray()
            return listOf("" to getString(R.string.doc_choose))+(0 until a.length()).map {a.optString(it) to status(a.optString(it))}
        }
        picker(R.string.doc_kind,options("categories"),kind,form){kind=it}
        picker(R.string.doc_priority,options("priorities"),priority,form){priority=it}
        val reason=field(R.string.doc_reason,form,"",10000)
        val instructions=field(R.string.doc_instructions,form,"",10000)
        note(getString(R.string.doc_admission_notice),form)
        action(R.string.doc_send_admission,form){
            if(kind.isBlank() || priority.isBlank() || reason.text.isBlank()) {showError(DoctorFailure(R.string.doc_required));return@action}
            val payload=args("p_patient" to selectedPatient(),"p_kind" to kind,"p_reason" to reason.text.toString().trim(),
                "p_instructions" to instructions.text.toString().trim(),"p_priority" to priority,"p_encounter" to ctx.encounter)
            confirm(ctx.patient?.value("full_name").orEmpty()+"\n"+status(kind)+" · "+status(priority)+"\n\n"+reason.text+"\n"+instructions.text){
                send("admission_request",payload){Toast.makeText(this,R.string.doc_done,Toast.LENGTH_SHORT).show();render()}
            }
        }
        action(R.string.desk_cancel){render()}
    }
}
