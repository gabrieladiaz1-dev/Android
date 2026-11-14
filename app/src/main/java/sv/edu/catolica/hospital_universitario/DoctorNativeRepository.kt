package sv.edu.catolica.hospital_universitario

import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** Contrato iOS contracts/doctor-native-v1.json. La identidad médica la resuelve el servidor. */
interface DoctorNativeRepository {
    fun workspace(day:LocalDate):JSONObject
    fun chart(patient:String,from:String?=null,to:String?=null,query:String=""):JSONObject
    fun patients(query:String,offset:Int):JSONObject
    fun call(operation:String,values:JSONObject=JSONObject()):Any
}
class DoctorFailure(val resource:Int,val detail:String=""):Exception()

class SupabaseDoctorNativeRepository:DoctorNativeRepository {
    private val allowed=setOf("workspace","chart","patients","patient_labs","lab_catalog","lab_orders",
        "labs","start","save","prescribe","finish","admissions","admission_request")
    override fun workspace(day:LocalDate):JSONObject=call("workspace",args(
        "p_from" to day.atStartOfDay(ReceptionClock.zone).toOffsetDateTime().toString(),
        "p_to" to day.plusDays(1).atStartOfDay(ReceptionClock.zone).toOffsetDateTime().toString())) as JSONObject
    override fun chart(patient:String,from:String?,to:String?,query:String):JSONObject=
        call("chart",args("p_patient" to patient,"p_from" to from,"p_to" to to,"p_query" to query)) as JSONObject
    override fun patients(query:String,offset:Int):JSONObject=call("patients",args("p_query" to query,"p_offset" to offset)) as JSONObject

    override fun call(operation:String,values:JSONObject):Any {
        require(operation in allowed)
        if(SessionManager.currentRole!=SessionManager.Role.DOCTOR) throw AuthException(R.string.auth_access_denied)
        val token=SessionManager.requireAccessToken()
        val connection=URL("${BuildConfig.SUPABASE_URL}/rest/v1/rpc/doctor_native_$operation").openConnection() as HttpURLConnection
        try {
            connection.requestMethod="POST";connection.connectTimeout=15000;connection.readTimeout=25000
            connection.instanceFollowRedirects=false;connection.doOutput=true
            connection.setRequestProperty("apikey",BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            connection.setRequestProperty("Authorization","Bearer $token")
            connection.setRequestProperty("Content-Type","application/json")
            connection.outputStream.use {it.write(values.toString().toByteArray(Charsets.UTF_8))}
            val status=connection.responseCode
            if(BuildConfig.DEBUG) android.util.Log.d("DoctorNative","operation=$operation http=$status")
            if(status !in 200..299) {
                val body=connection.errorStream?.bufferedReader()?.use {it.readText()}.orEmpty()
                val error=runCatching {JSONObject(body)}.getOrNull()
                val code=error?.value("code").orEmpty()
                throw DoctorFailure(when {
                    code=="PGRST202" || code=="42883" -> R.string.doc_contract_missing
                    code=="40001" || code=="23505" -> R.string.doc_version_conflict
                    code=="42501" || status==401 || status==403 -> R.string.doc_access_error
                    code in setOf("22023","P0001","23514","22P02") -> R.string.doc_validation
                    else->R.string.remote_operation_error
                },when(code) {
                    "PGRST202","42883"->"doctor_native_$operation"
                    "22023","P0001"->error?.value("message")?.take(2000).orEmpty()
                    else->""
                })
            }
            val body=connection.inputStream.bufferedReader().use {it.readText()}
            return if(body.isBlank()) JSONObject.NULL else JSONTokener(body).nextValue()
        } catch(e:java.io.IOException) {throw AuthException(R.string.auth_network_error)}
        finally {connection.disconnect()}
    }
}

/** Borradores y envíos solo en RAM, separados por la identidad de sesión. Nunca se guardan en disco. */
class DoctorNativeContext {
    private var identity:SessionManager.User?=null
    var appointment:JSONObject?=null
    var patient:JSONObject?=null
    var chart:JSONObject?=null
    var draft=JSONObject()
    var baseline=JSONObject()
    var encounter:String?=null
    var version=0
    var pending:DoctorPending?=null
    var prescriptionLines=org.json.JSONArray()
    fun bind() {
        if(identity!==SessionManager.currentUser) {
            clear();identity=SessionManager.currentUser
        }
    }
    fun clear() {
        identity=null;appointment=null;patient=null;chart=null;draft=JSONObject();baseline=JSONObject()
        encounter=null;version=0;pending=null;prescriptionLines=org.json.JSONArray()
    }
    fun selectPatient(value:JSONObject) {
        require(pending==null && prescriptionLines.length()==0)
        patient=value;appointment=null;chart=null;draft=JSONObject();baseline=JSONObject()
        encounter=value.value("active_encounter_id").ifBlank {null};version=0
    }
    fun selectAppointment(value:JSONObject) {
        require(pending==null && prescriptionLines.length()==0)
        appointment=value
        patient=args("id" to value.value("patient_id"),"full_name" to value.value("patient_name"),
            "record_number" to value.value("record_number"),"active_encounter_id" to value.value("encounter_id"))
        chart=null;draft=JSONObject();baseline=JSONObject()
        encounter=value.value("encounter_id").ifBlank {null};version=0
    }
    fun applyChart(value:JSONObject,replaceDraft:Boolean=true) {
        chart=value
        val active=value.optJSONArray("history")?.objects()?.firstOrNull {
            it.value("id")==encounter || encounter==null && it.value("status")=="open"
        }
        if(active!=null) {
            encounter=active.value("id")
            if(replaceDraft) version=active.optInt("row_version")
            if(replaceDraft) {
                draft=JSONObject().apply {
                    listOf("subjective","objective","assessment","plan","diagnosis_code").forEach {put(it,active.value(it))}
                    put("vitals",active.optJSONObject("vitals")?:JSONObject())
                }
                baseline=JSONObject(draft.toString())
            }
        }
    }
    fun openEncounter():Boolean=chart?.optJSONArray("history")?.objects()?.any {
        it.value("id")==encounter && it.value("status")=="open"
    }==true
    fun dirty():Boolean=draft.toString()!=baseline.toString()
    fun accepted(request:DoctorPending,result:Any) {
        when(request.operation) {
            "start" -> { encounter=result.toString();appointment?.put("encounter_id",encounter) }
            "save" -> {
                version=(result as? Number)?.toInt()?:result.toString().toInt()
                baseline=JSONObject(request.values.getJSONObject("p_values").toString())
            }
            "prescribe" -> prescriptionLines=org.json.JSONArray()
        }
        pending=null
    }
}
data class DoctorPending(val operation:String,val values:JSONObject,val key:String)
object DoctorDates {
    fun start(a:JSONObject):java.time.OffsetDateTime?=runCatching {
        val raw=a.value("period").drop(1).substringBefore(',').trim().trim('"').replace(" ","T")
        val fixed=raw.replace(Regex("([+-][0-9]{2})$"),"$1:00")
        java.time.OffsetDateTime.parse(fixed)
    }.getOrNull()
}
