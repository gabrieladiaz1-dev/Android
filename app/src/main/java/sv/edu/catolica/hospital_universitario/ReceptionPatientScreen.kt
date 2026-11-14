package sv.edu.catolica.hospital_universitario

import android.text.InputType
import android.widget.LinearLayout
import org.json.JSONObject

internal fun ReceptionDeskActivity.patientText(p: JSONObject): String =
    listOf("first_name" to R.string.desk_name,"last_name" to R.string.desk_last,
        "dui" to R.string.desk_dui,"birth_date" to R.string.desk_birth,
        "phone" to R.string.desk_phone,"email" to R.string.desk_email,"city" to R.string.desk_city,
        "patient_code" to R.string.desk_patient_code).mapNotNull { (key,label) ->
        p.value(key).takeIf {it.isNotBlank()}?.let {getString(R.string.desk_identity_row,getString(label),it)}
    }.joinToString("\n")

internal fun normalizeDocument(value: String): String {
    val v=value.trim()
    if(!Regex("^[0-9]{8}-?[0-9]$").matches(v)) throw DeskException(R.string.desk_dui_error)
    val digits=v.replace("-","")
    return digits.take(8)+"-"+digits.takeLast(1)
}

internal fun ReceptionDeskActivity.patientCard(p: JSONObject, record: String?, target: LinearLayout = binding.content): LinearLayout {
    if(record!=null) p.put("record_code",record)
    val body=card(getString(R.string.desk_patient_data),patientText(p),target)
    note(record?.let {getString(R.string.desk_record,it)} ?: getString(R.string.desk_missing_record),body)
    return body
}

class ReceptionPatientsActivity : ReceptionDeskActivity() {
    override val titleRes=R.string.desk_patients
    override fun render() {
        clear()
        val form=card(getString(R.string.desk_lookup))
        val document=field(R.string.desk_dui,form,max=10)
        document.inputType=InputType.TYPE_CLASS_PHONE
        val results=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        binding.content.addView(results)
        action(R.string.desk_lookup,form) {
            val dui=runCatching {normalizeDocument(document.text.toString())}.getOrElse {showError(it);return@action}
            retry={document.requestFocus()}
            results.removeAllViews()
            work({repo.patientByDui(dui)?.let {it to repo.record(it.value("id"))}}) { found ->
                if(found==null) note(getString(R.string.desk_not_found),results)
                else patientCard(found.first,found.second,results)
            }
        }
    }
}
