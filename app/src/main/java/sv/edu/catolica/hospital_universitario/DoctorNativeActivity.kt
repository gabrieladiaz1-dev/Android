package sv.edu.catolica.hospital_universitario

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import sv.edu.catolica.hospital_universitario.databinding.ActivityDoctorNativeBinding
import sv.edu.catolica.hospital_universitario.databinding.ItemReceptionCardBinding
import sv.edu.catolica.hospital_universitario.databinding.ItemReceptionFieldBinding
import sv.edu.catolica.hospital_universitario.databinding.ItemReceptionTileBinding
import sv.edu.catolica.hospital_universitario.databinding.ItemReceptionMetricBinding
import sv.edu.catolica.hospital_universitario.databinding.ItemReceptionRecordBinding
import sv.edu.catolica.hospital_universitario.databinding.ItemReceptionAppointmentBinding
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Base visual nativa; una Activity por sección, sin WebView ni datos de demostración. */
abstract class DoctorNativeActivity : AppCompatActivity() {
    lateinit var binding: ActivityDoctorNativeBinding
    val repo: DoctorNativeRepository get() = AppContainer.doctorNativeRepository
    val ctx get() = AppContainer.doctorNativeContext
    private var generation = 0
    var busy = false
        private set
    var retry: (() -> Unit)? = null
    abstract val titleRes: Int
    abstract fun render()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (SessionManager.currentRole != SessionManager.Role.DOCTOR) {
            Navigator.goToLogin(this, true)
            return
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityDoctorNativeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.title.setText(titleRes)
        ctx.bind()
        binding.subtitle.setText(R.string.doc_subtitle)
        binding.navHome.setOnClickListener { if (!busy) Navigator.goToDoctorHome(this,true) }
        binding.navRequests.setOnClickListener { if (!busy) Navigator.goToDoctorAppointments(this,true) }
        binding.navAgenda.setOnClickListener { if (!busy) Navigator.goToDoctorConsult(this,true) }
        binding.navWalkin.setOnClickListener { if (!busy) Navigator.goToDoctorRecords(this,true) }
        binding.navMore.setOnClickListener { if (!busy) Navigator.goToDoctorMore(this,true) }
        val active=when(this) {
            is DoctorNativeHomeActivity->binding.navHome
            is DoctorNativeAgendaActivity->binding.navRequests
            is DoctorNativeConsultActivity->binding.navAgenda
            is DoctorNativePatientsActivity,is DoctorNativeChartActivity->binding.navWalkin
            else->binding.navMore
        }
        active.setBackgroundResource(R.drawable.bg_nav_selected)
        binding.error.setOnClickListener { if (!busy) retry?.invoke() }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && SessionManager.currentRole != SessionManager.Role.DOCTOR) {
            finish()
        }
    }
    fun clear() {
        generation++
        binding.content.removeAllViews()
        binding.error.visibility = View.GONE
        binding.scroll.scrollTo(0, 0)
    }
    fun card(title: String, detail: String = "", target: LinearLayout = binding.content): LinearLayout {
        val c = ItemReceptionCardBinding.inflate(layoutInflater, target, false)
        c.heading.text = title
        c.detail.text = detail
        c.detail.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
        target.addView(c.root)
        return c.body
    }
    fun note(text: String, target: LinearLayout = binding.content) {
        target.addView(TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.text_secondary))
            textSize = 14f
            setPadding(0, dp(8), 0, dp(12))
        })
    }
    fun action(label: Int, target: LinearLayout = binding.content, run: () -> Unit): Button {
        val button=action(getString(label),target,run)
        if(label in setOf(R.string.desk_create,R.string.desk_schedule,R.string.desk_availability,
            R.string.desk_review_action,R.string.desk_link,R.string.desk_create_link,R.string.desk_assessment_send,
            R.string.desk_assign_confirm,R.string.desk_exit,R.string.desk_start_walkin)) {
            button.backgroundTintList=android.content.res.ColorStateList.valueOf(getColor(R.color.wine_primary))
            button.setTextColor(getColor(R.color.white))
        }
        return button
    }
    fun action(label: String, target: LinearLayout = binding.content, run: () -> Unit): Button {
        val button = com.google.android.material.button.MaterialButton(this).apply {
            text=label;textSize=14f;isAllCaps=false;minHeight=dp(48);minimumHeight=dp(48)
            cornerRadius=dp(12);insetTop=0;insetBottom=0;strokeWidth=dp(1)
            strokeColor=android.content.res.ColorStateList.valueOf(getColor(R.color.pink_caption))
            setTextColor(getColor(R.color.wine_primary))
            backgroundTintList=android.content.res.ColorStateList.valueOf(getColor(R.color.white))
            setPadding(dp(14),dp(12),dp(14),dp(12))
            layoutParams=LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(10)}
            setOnClickListener {if(!busy) run()}
        }
        target.addView(button)
        return button
    }
    fun field(label: Int, target: LinearLayout = binding.content, value: String = "", max: Int = 2000): EditText {
        val f = ItemReceptionFieldBinding.inflate(layoutInflater, target, false)
        f.label.setText(label)
        f.input.contentDescription=getString(label)
        f.input.setText(value)
        if(label==R.string.desk_search) {f.label.visibility=View.GONE;f.input.hint=getString(label)}
        f.input.filters = arrayOf(android.text.InputFilter.LengthFilter(max))
        if(max>254) {
            f.input.inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            f.input.minLines=3;f.input.gravity=android.view.Gravity.TOP
        } else f.input.setSingleLine(true)
        f.input.setSaveEnabled(false)
        target.addView(f.root)
        return f.input
    }
    fun check(label: Int = R.string.desk_identity, target: LinearLayout = binding.content): CheckBox {
        val c = CheckBox(this).apply { setText(label); minHeight = dp(48);textSize=13f;setTextColor(getColor(R.color.text_secondary));buttonTintList=android.content.res.ColorStateList.valueOf(getColor(R.color.wine_primary)) }
        target.addView(c)
        return c
    }
    fun picker(label: Int, options: List<Pair<String, String>>, selected: String,
               target: LinearLayout = binding.content, changed: (String) -> Unit): Button {
        val button=action(getString(label) + getString(R.string.desk_separator) +
            (options.firstOrNull { it.first == selected }?.second ?: getString(R.string.desk_all)),target) {}
        (button as? com.google.android.material.button.MaterialButton)?.apply {
            icon=androidx.appcompat.content.res.AppCompatResources.getDrawable(this@DoctorNativeActivity,android.R.drawable.arrow_down_float)
            iconGravity=com.google.android.material.button.MaterialButton.ICON_GRAVITY_END
            iconTint=android.content.res.ColorStateList.valueOf(getColor(R.color.wine_primary))
        }
        button.setOnClickListener {
            if(!busy) MaterialAlertDialogBuilder(this).setTitle(label)
                .setItems(options.map { it.second }.toTypedArray()) { _, i ->
                    button.text=getString(label)+getString(R.string.desk_separator)+options[i].second
                    changed(options[i].first)
                }.setNegativeButton(R.string.desk_cancel, null).show()
        }
        return button
    }
    fun date(day: LocalDate, target: LinearLayout = binding.content, minToday: Boolean = false, changed: (LocalDate) -> Unit) {
        action(getString(R.string.desk_date) + getString(R.string.desk_separator) + day, target) {
            DatePickerDialog(this, { _, y, m, d -> changed(LocalDate.of(y,m+1,d)) },
                day.year,day.monthValue-1,day.dayOfMonth).apply {
                if (minToday) datePicker.minDate = ReceptionClock.today().atStartOfDay(ReceptionClock.zone).toInstant().toEpochMilli()
            }.show()
        }
    }
    fun confirm(text: String = getString(R.string.desk_confirm_action), run: () -> Unit) {
        MaterialAlertDialogBuilder(this).setMessage(text)
            .setPositiveButton(R.string.desk_confirm) { _, _ -> if (!busy) run() }
            .setNegativeButton(R.string.desk_cancel, null).show()
    }
    fun reason(label: Int, submit: (String) -> Unit) {
        val form = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(12),dp(20),0) }
        val text = field(R.string.desk_reason, form)
        val dialog = MaterialAlertDialogBuilder(this).setTitle(label).setView(form)
            .setPositiveButton(R.string.desk_confirm, null).setNegativeButton(R.string.desk_cancel,null).create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                if (text.text.toString().isBlank()) text.error=getString(R.string.desk_invalid)
                else { val value=text.text.toString().trim(); dialog.dismiss(); confirm { submit(value) } }
            }
        }
        dialog.show()
    }
    fun <T> work(operation: () -> T, success: (T) -> Unit) {
        if (busy) return
        busy = true
        val version = generation
        val user = SessionManager.currentUser
        binding.loading.visibility=View.VISIBLE
        binding.error.visibility=View.GONE
        enable(binding.content,false)
        binding.navHome.isEnabled=false; binding.navRequests.isEnabled=false
        binding.navAgenda.isEnabled=false; binding.navWalkin.isEnabled=false;binding.navMore.isEnabled=false
        AppContainer.ioExecutor.execute {
            val result=runCatching {
                if (SessionManager.currentUser !== user || user?.role != SessionManager.Role.DOCTOR)
                    throw AuthException(R.string.auth_access_denied)
                operation()
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy=false
                binding.loading.visibility=View.GONE
                enable(binding.content,true)
                binding.navHome.isEnabled=true; binding.navRequests.isEnabled=true
                binding.navAgenda.isEnabled=true; binding.navWalkin.isEnabled=true;binding.navMore.isEnabled=true
                if (version != generation || SessionManager.currentUser !== user) return@runOnUiThread
                result.fold(success) { showError(it) }
            }
        }
    }
    fun showError(error: Throwable) {
        binding.error.text=when(error) {
            is AuthException -> getString(error.messageRes)
            is DoctorFailure -> when {
                error.resource == R.string.doc_contract_missing -> getString(error.resource,error.detail)
                error.resource == R.string.doc_validation && error.detail.isNotBlank() -> error.detail
                else -> getString(error.resource)
            }
            else -> getString(R.string.desk_response)
        } + getString(R.string.desk_retry_hint)
        binding.error.visibility=View.VISIBLE
    }
    fun status(code: String): String = getString(when(code) {
        "scheduled" -> R.string.desk_programmed; "confirmed" -> R.string.desk_confirmed
        "in_progress" -> R.string.desk_in_care; "completed" -> R.string.desk_completed
        "cancelled" -> R.string.desk_cancelled; "no_show" -> R.string.desk_no_show
        "open" -> R.string.doc_open; "closed" -> R.string.desk_closed
        "pendiente" -> R.string.desk_pending; "en_revision" -> R.string.desk_review
        "agendada" -> R.string.desk_scheduled; "rechazada","rejected" -> R.string.desk_rejected
        "cancelada" -> R.string.desk_cancelled; "programada" -> R.string.desk_programmed
        "confirmada" -> R.string.desk_confirmed; "en_atencion" -> R.string.desk_in_care
        "completada" -> R.string.desk_completed; "no_asistio" -> R.string.desk_no_show
        "solicitado" -> R.string.desk_requested; "asignado" -> R.string.desk_assigned
        "hospitalizado" -> R.string.desk_hospitalized; "alta_medica" -> R.string.desk_discharge
        "cerrado" -> R.string.desk_closed; "cancelado" -> R.string.desk_admission_cancelled
        "disponible" -> R.string.desk_available; "ocupada" -> R.string.desk_occupied
        "limpieza" -> R.string.desk_cleaning; "mantenimiento" -> R.string.desk_maintenance
        "fuera_servicio" -> R.string.desk_out; "vip" -> R.string.desk_vip; "semiprivada" -> R.string.desk_semi
        "manana" -> R.string.desk_period_morning; "tarde" -> R.string.desk_period_afternoon; "indiferente" -> R.string.desk_period_any
        "normal" -> R.string.desk_normal; "urgente" -> R.string.desk_urgent
        "pending" -> R.string.desk_assessment_pending; "approved" -> R.string.desk_assessment_approved
        "expired" -> R.string.desk_assessment_expired; else -> R.string.desk_unknown
    })
    fun time(value: String): String = runCatching {
        OffsetDateTime.parse(value).atZoneSameInstant(ReceptionClock.zone)
            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm a", java.util.Locale.US))
            .lowercase(java.util.Locale.US)
    }.getOrDefault(value)
    fun required(input: EditText): String {
        val value=input.text.toString().trim()
        if(value.isBlank()) { input.error=getString(R.string.desk_invalid); throw DoctorFailure(R.string.desk_invalid) }
        return value
    }
    private fun enable(view: View, enabled: Boolean) {
        view.isEnabled=enabled
        if(view is ViewGroup) for(i in 0 until view.childCount) enable(view.getChildAt(i),enabled)
    }
    fun filterRow(target:LinearLayout=binding.content):LinearLayout {
        val row=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;isBaselineAligned=false}
        target.addView(row)
        return row
    }
    fun compact(button:Button) {
        button.layoutParams=LinearLayout.LayoutParams(0,-2,1f).apply {setMargins(dp(3),0,dp(3),dp(8))}
        button.textSize=12f;button.maxLines=2
    }
    fun chipStrip(options:List<Pair<String,String>>,selected:String,target:LinearLayout=binding.content,change:(String)->Unit) {
        val scroll=HorizontalScrollView(this).apply {isHorizontalScrollBarEnabled=false}
        val row=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
        scroll.addView(row)
        target.addView(scroll)
        options.forEach { (id,label) ->
            row.addView(TextView(this).apply {
                text=label;textSize=12f;gravity=android.view.Gravity.CENTER
                minHeight=dp(40);setPadding(dp(12),dp(8),dp(12),dp(8))
                setTextColor(getColor(if(id==selected) R.color.wine_primary else R.color.text_secondary))
                setBackgroundResource(if(id==selected) R.drawable.bg_pill_pink else R.drawable.bg_dashboard_card)
                layoutParams=LinearLayout.LayoutParams(-2,-2).apply {setMargins(0,0,dp(7),dp(10))}
                isClickable=true;isFocusable=true
                setOnClickListener {if(!busy) change(id)}
            })
        }
    }
    fun recordCard(name:String,badge:String,detail:String,date:String,target:LinearLayout=binding.content,open:()->Unit) {
        val row=ItemReceptionRecordBinding.inflate(layoutInflater,target,false)
        row.name.text=name;row.badge.text=badge;row.detail.text=detail;row.date.text=date
        row.root.setOnClickListener {if(!busy) open()}
        target.addView(row.root)
    }
    fun appointmentCard(a:org.json.JSONObject,target:LinearLayout) {
        fun hour(v:String)=runCatching {
            OffsetDateTime.parse(v).atZoneSameInstant(ReceptionClock.zone)
                .format(DateTimeFormatter.ofPattern("hh:mm a", java.util.Locale.US))
                .lowercase(java.util.Locale.US)
        }.getOrDefault(v)
        val row=ItemReceptionAppointmentBinding.inflate(layoutInflater,target,false)
        row.start.text=hour(a.value("starts_at"));row.end.text=hour(a.value("ends_at"))
        row.patient.text=a.value("patient_name");row.doctor.text=a.value("doctor_name")
        row.room.text=getString(R.string.desk_consulting_room,a.value("room_code"))
        row.badge.text=status(a.value("status"))
        target.addView(row.root)
    }
    fun tileGrid(items: List<Triple<Int,Int,Pair<Int,()->Unit>>>) {
        items.chunked(2).forEach { pair ->
            val row=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
            binding.content.addView(row)
            pair.forEach { (title,icon,detail) ->
                val tile=ItemReceptionTileBinding.inflate(layoutInflater,row,false)
                tile.icon.setImageResource(icon);tile.title.setText(title);tile.caption.setText(detail.first)
                tile.root.layoutParams=LinearLayout.LayoutParams(0,-2,1f).apply {setMargins(dp(4),0,dp(4),dp(12))}
                tile.root.setOnClickListener {if(!busy) detail.second()}
                row.addView(tile.root)
            }
            if(pair.size==1) row.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
        }
    }
    fun metricGrid(states: List<String>): Map<String,TextView> {
        val fields=linkedMapOf<String,TextView>()
        states.chunked(2).forEach { pair ->
            val row=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
            binding.content.addView(row)
            pair.forEach { state ->
                val metric=ItemReceptionMetricBinding.inflate(layoutInflater,row,false)
                metric.label.text=status(state)
                metric.root.layoutParams=LinearLayout.LayoutParams(0,-2,1f).apply {setMargins(dp(4),0,dp(4),dp(12))}
                fields[state]=metric.amount
                row.addView(metric.root)
            }
            if(pair.size==1) row.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
        }
        return fields
    }
    fun section(label:Int) {
        binding.content.addView(TextView(this).apply {
            setText(label);textSize=17f;setTypeface(typeface,android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary));setPadding(dp(4),dp(14),0,dp(14))
        })
    }
    fun selectedPatient(): String? = ctx.patient?.value("id")?.ifBlank { null }
    fun choosePatient(p:org.json.JSONObject, open:()->Unit) {
        if(ctx.pending!=null || ctx.prescriptionLines.length()>0) { showError(DoctorFailure(R.string.doc_pending));return }
        val select={ctx.selectPatient(p);open()}
        if(ctx.dirty()) confirm(getString(R.string.doc_discard),select) else select()
    }
    fun send(operation:String, values:org.json.JSONObject, done:(Any)->Unit) {
        val existing=ctx.pending
        if(existing!=null && existing.operation!=operation) {showError(DoctorFailure(R.string.doc_pending));return}
        val pending=existing?:DoctorPending(operation,org.json.JSONObject(values.toString()),java.util.UUID.randomUUID().toString())
        if(operation!="finish") pending.values.put("p_key",pending.key)
        ctx.pending=pending
        retry={send(operation,pending.values,done)}
        val user=SessionManager.currentUser
        work({
            try {
                repo.call(operation,pending.values).also {if(SessionManager.currentUser===user) ctx.accepted(pending,it)}
            } catch(e:DoctorFailure) {
                if(SessionManager.currentUser===user && e.resource!=R.string.remote_operation_error) ctx.pending=null
                throw e
            }
        }) { done(it) }
    }
    fun pendingAction(done:(Any)->Unit):Boolean {
        val pending=ctx.pending?:return false
        note(getString(R.string.doc_pending))
        action(R.string.doc_retry) {send(pending.operation,pending.values,done)}
        return true
    }
    fun dp(v: Int) = (v*resources.displayMetrics.density).toInt()
}
